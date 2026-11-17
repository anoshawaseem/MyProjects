package com.micomm.email.channel;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.util.HtmlUtils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.micomm.common.audit.AuditActions;
import com.micomm.common.audit.AuditEvent;
import com.micomm.common.security.ClubAccessGuard;
import com.micomm.email.audit.EmailAuditLog;
import com.micomm.email.channel.dto.SendNotificationRequest;
import com.micomm.email.channel.dto.SendNotificationRequest.NotificationRecipient;
import com.micomm.email.channel.dto.SendNotificationResponse;
import com.micomm.email.client.TenantManagementClient;
import com.micomm.email.client.TenantResolutionException;
import com.micomm.email.client.dto.ResolvedProviderDto;
import com.micomm.email.client.dto.TenantDatabaseDto;
import com.micomm.email.kafka.ChannelSendMessage;
import com.micomm.email.kafka.EmailSendMessage;
import com.micomm.email.notification.NotificationType;
import com.micomm.email.security.ClientScopeFilter;
import com.micomm.email.send.Idempotency;
import com.micomm.email.send.IdempotencyStore;
import com.micomm.email.send.QueueUnavailableException;
import com.micomm.email.send.SendNotQueuedException;
import com.micomm.email.template.TemplateRenderer;
import com.micomm.email.tenant.TenantModules;

/**
 * Sends one message to the same people on several channels (EMAIL, SMS, WHATSAPP) of a club's
 * product. The send is one notification with a delivery per channel (layouts "5" and "6"). EMAIL
 * goes through the email pipeline (email.send.v1); SMS and WHATSAPP through notification.send.v1.
 * Every channel's content, recipients, merge fields and provider are checked before anything is
 * queued, so a bad request is a 4xx rather than a partly sent notification.
 */
@Service
public class MultiChannelSendService {

    static final String EMAIL = "EMAIL";
    static final List<String> TEXT_CHANNELS = List.of("SMS", "WHATSAPP");

    private static final int MAX_REPORTED_RECIPIENTS = 5;
    private static final Duration PUBLISH_TIMEOUT = Duration.ofSeconds(15);

    private final KafkaTemplate<String, EmailSendMessage> emailKafkaTemplate;
    private final KafkaTemplate<String, ChannelSendMessage> channelKafkaTemplate;
    private final String emailSendTopic;
    private final String channelSendTopic;
    private final int chunkSize;
    private final int maxMessageBytes;
    private final ObjectMapper objectMapper;
    private final ClubAccessGuard clubAccessGuard;
    private final TenantManagementClient tenantManagementClient;
    private final TextMessageProviders textProviders;
    private final IdempotencyStore idempotencyStore;
    private final EmailAuditLog auditLog;

    public MultiChannelSendService(
            KafkaTemplate<String, EmailSendMessage> emailKafkaTemplate,
            KafkaTemplate<String, ChannelSendMessage> channelKafkaTemplate,
            @Value("${kafka.topics.email-send}") String emailSendTopic,
            @Value("${kafka.topics.channel-send}") String channelSendTopic,
            @Value("${micomm.email.send-chunk-size:500}") int chunkSize,
            @Value("${micomm.email.max-send-message-bytes:900000}") int maxMessageBytes,
            ObjectMapper objectMapper,
            ClubAccessGuard clubAccessGuard,
            TenantManagementClient tenantManagementClient,
            TextMessageProviders textProviders,
            IdempotencyStore idempotencyStore,
            EmailAuditLog auditLog) {
        this.emailKafkaTemplate = emailKafkaTemplate;
        this.channelKafkaTemplate = channelKafkaTemplate;
        this.emailSendTopic = emailSendTopic;
        this.channelSendTopic = channelSendTopic;
        if (chunkSize < 1) {
            throw new IllegalArgumentException("micomm.email.send-chunk-size must be at least 1");
        }
        this.chunkSize = chunkSize;
        this.maxMessageBytes = maxMessageBytes;
        this.objectMapper = objectMapper;
        this.clubAccessGuard = clubAccessGuard;
        this.tenantManagementClient = tenantManagementClient;
        this.textProviders = textProviders;
        this.idempotencyStore = idempotencyStore;
        this.auditLog = auditLog;
    }

    public record Submission(SendNotificationResponse response, boolean replayed) {
    }

    /**
     * One channel of the send: its content (EMAIL: subject + html; text channels: text only),
     * the recipients with an address on it (their addresses and indexes in the request) and the
     * provider account it goes out through.
     */
    record ChannelPlan(String channel, String subject, String content, List<String> addresses,
            List<Integer> recipientIndexes, ResolvedProviderDto provider, String fromEmail) {
    }

    public Submission submit(SendNotificationRequest request, String idempotencyKey) {
        clubAccessGuard.requireClubAccess(request.clubId());
        List<String> channels = channels(request);
        ClientScopeFilter.requireSendScopes(channels);

        UUID requestId = UUID.randomUUID();
        String key = Idempotency.normalizeKey(idempotencyKey);
        if (key == null) {
            return audited(request, channels, null, new Submission(queue(request, channels, requestId), false));
        }
        String requestHash = Idempotency.requestHash(objectMapper, request);
        Optional<IdempotencyStore.Entry> earlier = idempotencyStore.claim(request.clubId(), key, requestHash,
                requestId);
        if (earlier.isPresent()) {
            var replay = Idempotency.replay(earlier.get(), requestHash);
            return audited(request, channels, key, new Submission(
                    new SendNotificationResponse(replay.requestId(), replay.status(), replay.recipientCount(), null),
                    true));
        }
        try {
            SendNotificationResponse response = queue(request, channels, requestId);
            idempotencyStore.complete(request.clubId(), key, response.recipientCount());
            return audited(request, channels, key, new Submission(response, false));
        } catch (SendNotQueuedException ex) {
            idempotencyStore.fail(request.clubId(), key, request.recipients().size());
            throw ex;
        } catch (RuntimeException ex) {
            idempotencyStore.release(request.clubId(), key);
            throw ex;
        }
    }

    private Submission audited(SendNotificationRequest request, List<String> channels, String idempotencyKey,
            Submission submission) {
        auditLog.record(AuditEvent.of(request.clubId(), request.productId(), AuditActions.NOTIFICATION_SEND_QUEUED,
                        "NOTIFICATION_SEND", submission.response().requestId())
                .with("channels", channels)
                .with("recipientCount", submission.response().recipientCount())
                .with("channelRecipients", submission.response().channels())
                .with("type", NotificationType.parse(request.type()).name())
                .with("module", TenantModules.normalize(request.moduleCode()))
                .with("idempotencyKey", idempotencyKey)
                .with("replayed", submission.replayed() ? Boolean.TRUE : null));
        return submission;
    }

    /** Upper-cased, without repeats; PUSH is accepted by the schema but has no sender yet. */
    static List<String> channels(SendNotificationRequest request) {
        List<String> channels = request.channels().stream().map(c -> c.toUpperCase(Locale.ROOT)).distinct().toList();
        if (channels.contains("PUSH")) {
            throw new IllegalArgumentException("PUSH is not available yet; use EMAIL, SMS or WHATSAPP");
        }
        return channels;
    }

    private SendNotificationResponse queue(SendNotificationRequest request, List<String> channels, UUID requestId) {
        String moduleCode = TenantModules.normalize(request.moduleCode());
        TenantDatabaseDto tenant = tenantManagementClient.resolveTenantDatabase(request.clubId(),
                request.productId(), moduleCode);
        if (!tenant.usesDeliveries()) {
            throw new IllegalArgumentException("This club's notification tables keep one channel per send (schema"
                    + " layout " + tenant.schemaVersion() + "); multi-channel sends need a club created with layout"
                    + " 5 or later. Use POST /api/v1/emails/send-bulk for email.");
        }
        if (moduleCode != null) {
            TenantModules.requireEnabled(tenant, moduleCode);
        }

        requireEveryRecipientReachable(request.recipients(), channels);
        Map<String, String> variables = sendVariables(tenant, request);
        List<ChannelPlan> plans = new ArrayList<>(channels.size());
        for (String channel : channels) {
            ChannelPlan plan = plan(request, channel, moduleCode);
            requireAllMergeFields(plan, variables, request.recipients());
            plans.add(plan);
        }

        OffsetDateTime submittedAt = OffsetDateTime.now();
        String type = NotificationType.parse(request.type()).name();
        int people = request.recipients().size();
        List<EmailSendMessage> emailChunks = new ArrayList<>();
        List<ChannelSendMessage> textChunks = new ArrayList<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (ChannelPlan plan : plans) {
            int total = plan.addresses().size();
            counts.put(plan.channel(), total);
            List<Map<String, String>> recipientVariables = recipientVariables(request.recipients(),
                    plan.recipientIndexes());
            int chunkCount = (total + chunkSize - 1) / chunkSize;
            for (int i = 0; i < chunkCount; i++) {
                int from = i * chunkSize;
                int to = Math.min(from + chunkSize, total);
                List<Map<String, String>> chunkVariables = recipientVariables != null
                        ? recipientVariables.subList(from, to) : null;
                if (EMAIL.equals(plan.channel())) {
                    emailChunks.add(requireFits(new EmailSendMessage(requestId, "email-" + requestId + "-" + i,
                            request.clubId(), request.productId(), plan.fromEmail(), plan.subject(), plan.content(),
                            plan.addresses().subList(from, to), submittedAt, moduleCode, null, variables,
                            chunkVariables, i, chunkCount, total, type, plans.size(), people)));
                } else {
                    textChunks.add(requireFits(new ChannelSendMessage(requestId, request.clubId(),
                            request.productId(), plan.channel(), moduleCode, type, plan.content(),
                            plan.addresses().subList(from, to), variables, chunkVariables, submittedAt, i,
                            chunkCount, total, plans.size(), people)));
                }
            }
        }

        publish(requestId, emailChunks, textChunks);
        return new SendNotificationResponse(requestId, "QUEUED", people, counts);
    }

    /** Content, recipients and provider of one channel; a 400 when any is missing. */
    private ChannelPlan plan(SendNotificationRequest request, String channel, String moduleCode) {
        List<String> addresses = new ArrayList<>();
        List<Integer> indexes = new ArrayList<>();
        for (int i = 0; i < request.recipients().size(); i++) {
            String address = request.recipients().get(i).address(channel);
            if (address != null) {
                addresses.add(address);
                indexes.add(i);
            }
        }
        if (addresses.isEmpty()) {
            throw new IllegalArgumentException("No recipient has an address for " + channel
                    + (EMAIL.equals(channel) ? " (email)" : " (phone" + ("WHATSAPP".equals(channel)
                            ? " or whatsapp" : "") + ")") + "; remove it from 'channels' or add the addresses");
        }

        ResolvedProviderDto provider = provider(request, channel, moduleCode);
        if (EMAIL.equals(channel)) {
            String subject = firstNonBlank(request.email() != null ? request.email().subject() : null,
                    request.title());
            String html = firstNonBlank(request.email() != null ? request.email().html() : null,
                    textToHtml(request.body()));
            if (subject == null || html == null) {
                throw new IllegalArgumentException("EMAIL needs a subject ('email.subject' or 'title') and a body"
                        + " ('email.html' or 'body')");
            }
            String fromEmail = firstNonBlank(request.email() != null ? request.email().fromEmail() : null,
                    configValue(provider.configJson(), "fromEmail"));
            if (fromEmail == null) {
                throw new IllegalArgumentException("EMAIL needs 'email.fromEmail'; the product's email provider"
                        + " account has no default from address");
            }
            return new ChannelPlan(channel, subject, html, addresses, indexes, provider, fromEmail);
        }

        if (textProviders.find(provider.providerTypeCode(), channel).isEmpty()) {
            throw new IllegalArgumentException("The " + channel + " provider account is of type "
                    + provider.providerTypeCode() + ", which cannot send " + channel);
        }
        SendNotificationRequest.TextContent override = "SMS".equals(channel) ? request.sms() : request.whatsapp();
        String text = firstNonBlank(override != null ? override.text() : null, joinLines(request.title(),
                request.body()));
        if (text == null) {
            throw new IllegalArgumentException(channel + " needs text ('" + channel.toLowerCase(Locale.ROOT)
                    + ".text', or 'title' / 'body')");
        }
        return new ChannelPlan(channel, null, text, addresses, indexes, provider, null);
    }

    /**
     * The channel's provider account (the module's own, else the product's). Resolved here as
     * well as in the worker so that a channel with no provider is refused before anything is sent.
     */
    private ResolvedProviderDto provider(SendNotificationRequest request, String channel, String moduleCode) {
        try {
            return tenantManagementClient.resolveProvider(request.clubId(), request.productId(), channel, moduleCode);
        } catch (HttpClientErrorException | TenantResolutionException ex) {
            throw new IllegalArgumentException("No " + channel + " provider is set up for this product"
                    + (moduleCode != null ? " or module " + moduleCode : "")
                    + "; assign one on the club's Provider Setup page");
        }
    }

    private static void requireEveryRecipientReachable(List<NotificationRecipient> recipients,
            List<String> channels) {
        List<String> problems = new ArrayList<>();
        int failing = 0;
        for (int i = 0; i < recipients.size(); i++) {
            NotificationRecipient r = recipients.get(i);
            if (channels.stream().allMatch(c -> r.address(c) == null) && failing++ < MAX_REPORTED_RECIPIENTS) {
                problems.add("#" + (i + 1) + (r.describe() != null ? " (" + r.describe() + ")" : ""));
            }
        }
        if (failing > 0) {
            throw new IllegalArgumentException(failing + " recipient(s) have no address on " + channels + ": "
                    + String.join(", ", problems) + (failing > MAX_REPORTED_RECIPIENTS ? ", ..." : ""));
        }
    }

    private static Map<String, String> sendVariables(TenantDatabaseDto tenant, SendNotificationRequest request) {
        Map<String, String> variables = new HashMap<>();
        if (tenant.clubName() != null) {
            variables.put("clubName", tenant.clubName());
        }
        if (tenant.productName() != null) {
            variables.put("productName", tenant.productName());
        }
        if (request.variables() != null) {
            variables.putAll(request.variables());
        }
        return variables;
    }

    /** The channel's recipients' own merge values, or null when none of them has any. */
    private static List<Map<String, String>> recipientVariables(List<NotificationRecipient> recipients,
            List<Integer> indexes) {
        if (indexes.stream().allMatch(i -> recipients.get(i).variables().isEmpty())) {
            return null;
        }
        return indexes.stream().map(i -> recipients.get(i).variables()).toList();
    }

    private static void requireAllMergeFields(ChannelPlan plan, Map<String, String> sendVariables,
            List<NotificationRecipient> recipients) {
        Set<String> needed = new LinkedHashSet<>();
        if (plan.subject() != null) {
            needed.addAll(TemplateRenderer.placeholders(plan.subject()));
        }
        needed.addAll(TemplateRenderer.placeholders(plan.content()));
        needed.removeIf(name -> sendVariables.get(name) != null);
        if (needed.isEmpty()) {
            return;
        }
        List<String> problems = new ArrayList<>();
        int failing = 0;
        for (int i = 0; i < plan.recipientIndexes().size(); i++) {
            NotificationRecipient recipient = recipients.get(plan.recipientIndexes().get(i));
            List<String> missing = needed.stream().filter(name -> recipient.variables().get(name) == null).toList();
            if (!missing.isEmpty() && failing++ < MAX_REPORTED_RECIPIENTS) {
                problems.add(plan.addresses().get(i) + " (" + String.join(", ", missing) + ")");
            }
        }
        if (failing > 0) {
            throw new IllegalArgumentException("Missing merge fields for " + failing + " " + plan.channel()
                    + " recipient(s): " + String.join("; ", problems)
                    + (failing > MAX_REPORTED_RECIPIENTS ? "; ..." : ""));
        }
    }

    /**
     * Publishes every chunk of every channel, then waits for Kafka to acknowledge them all, so
     * QUEUED means the whole send is queued.
     */
    private void publish(UUID requestId, List<EmailSendMessage> emailChunks, List<ChannelSendMessage> textChunks) {
        List<CompletableFuture<?>> sends = new ArrayList<>(emailChunks.size() + textChunks.size());
        try {
            for (EmailSendMessage c : emailChunks) {
                sends.add(emailKafkaTemplate.send(emailSendTopic, requestId + ":EMAIL:" + c.chunkIndex(), c));
            }
            for (ChannelSendMessage c : textChunks) {
                sends.add(channelKafkaTemplate.send(channelSendTopic,
                        requestId + ":" + c.channel() + ":" + c.chunkIndex(), c));
            }
        } catch (KafkaException ex) {
            if (sends.isEmpty()) {
                throw new QueueUnavailableException(ex);
            }
            throw new SendNotQueuedException(requestId, ex);
        }
        try {
            CompletableFuture.allOf(sends.toArray(CompletableFuture[]::new))
                    .get(PUBLISH_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new SendNotQueuedException(requestId, ex);
        } catch (ExecutionException | TimeoutException ex) {
            throw new SendNotQueuedException(requestId, ex);
        }
    }

    private <T> T requireFits(T message) {
        int size;
        try {
            size = objectMapper.writeValueAsBytes(message).length;
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Cannot serialise send message", ex);
        }
        if (size > maxMessageBytes) {
            throw new IllegalArgumentException("Content and merge values are too large (" + size
                    + " bytes in one chunk, limit " + maxMessageBytes + "); send less data per recipient");
        }
        return message;
    }

    /** Plain text as HTML: escaped, with its line breaks kept. Placeholders pass through. */
    static String textToHtml(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        return HtmlUtils.htmlEscape(text).replace("\r\n", "\n").replace("\n", "<br>\n");
    }

    private static String joinLines(String title, String body) {
        String t = title == null || title.isBlank() ? null : title.strip();
        String b = body == null || body.isBlank() ? null : body.strip();
        if (t == null) {
            return b;
        }
        return b == null ? t : t + "\n" + b;
    }

    private String configValue(String configJson, String field) {
        if (configJson == null || configJson.isBlank()) {
            return null;
        }
        try {
            String value = objectMapper.readTree(configJson).path(field).asText(null);
            return value == null || value.isBlank() ? null : value;
        } catch (JsonProcessingException ex) {
            return null;
        }
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        return b != null && !b.isBlank() ? b : null;
    }
}
