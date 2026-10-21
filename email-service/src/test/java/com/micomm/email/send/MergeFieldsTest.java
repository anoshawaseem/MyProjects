package com.micomm.email.send;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.micomm.email.kafka.EmailSendMessage;
import com.micomm.email.send.dto.EmailRecipient;
import com.micomm.email.send.dto.SendBulkEmailRequest;

class MergeFieldsTest {

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void recipientsMayBePlainAddressesOrObjectsWithVariables() throws Exception {
        SendBulkEmailRequest request = objectMapper.readValue("""
                {"clubId": "%s", "productId": "%s", "fromEmail": "club@x.com", "subject": "s", "htmlBody": "b",
                 "recipients": ["a@x.com", {"email": "b@x.com", "variables": {"memberName": "Sara"}}]}
                """.formatted(UUID.randomUUID(), UUID.randomUUID()), SendBulkEmailRequest.class);

        assertThat(request.recipients()).containsExactly(
                new EmailRecipient("a@x.com", null),
                new EmailRecipient("b@x.com", Map.of("memberName", "Sara")));
        assertThat(request.recipients().get(0).variables()).isEmpty();
        assertThat(request.hasVariables()).isTrue();
    }

    @Test
    void recipientValuesOverrideSendLevelValues() {
        EmailSendMessage message = message(Map.of("clubName", "KCC", "memberName", "Member"),
                Arrays.asList(Map.of("memberName", "Ali"), null));

        assertThat(message.merges()).isTrue();
        assertThat(message.variablesFor(0)).containsEntry("memberName", "Ali").containsEntry("clubName", "KCC");
        assertThat(message.variablesFor(1)).containsEntry("memberName", "Member");
    }

    @Test
    void messagesWithoutVariablesAreSentAsIs() throws Exception {
        // Shape of messages published before merge fields existed.
        EmailSendMessage old = objectMapper.readValue("""
                {"requestId": "%s", "clubId": "%s", "productId": "%s", "fromEmail": "club@x.com",
                 "subject": "Hi {{x}}", "htmlBody": "b", "recipients": ["a@x.com"]}
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()), EmailSendMessage.class);

        assertThat(old.merges()).isFalse();
    }

    private static EmailSendMessage message(Map<String, String> variables, List<Map<String, String>> perRecipient) {
        return new EmailSendMessage(UUID.randomUUID(), "k", UUID.randomUUID(), UUID.randomUUID(), "club@x.com",
                "Hi {{memberName}}", "<p>{{clubName}}</p>", List.of("a@x.com", "b@x.com"), null, null, null,
                variables, perRecipient, 0, 1, 2);
    }

    @Test
    void messagesFromBeforeChunkingCoverTheirWholeSend() throws Exception {
        EmailSendMessage old = objectMapper.readValue("""
                {"requestId": "%s", "clubId": "%s", "productId": "%s", "fromEmail": "club@x.com",
                 "subject": "s", "htmlBody": "b", "recipients": ["a@x.com", "b@x.com"]}
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()), EmailSendMessage.class);

        assertThat(old.chunkCount()).isZero();
        assertThat(old.sendRecipientCount()).isEqualTo(2);
    }
}
