package com.micomm.email.provider;

import com.sendgrid.Method;
import com.sendgrid.Request;
import com.sendgrid.Response;
import com.sendgrid.SendGrid;
import com.sendgrid.helpers.mail.Mail;
import com.sendgrid.helpers.mail.objects.Content;
import com.sendgrid.helpers.mail.objects.Email;
import org.springframework.stereotype.Component;

@Component
public class SendGridEmailProvider implements EmailProvider {

    @Override
    public String providerTypeCode() {
        return "SENDGRID";
    }

    @Override
    public EmailSendResult send(EmailSendRequest request) {
        try {
            Email from = new Email(request.fromEmail());
            Email to = new Email(request.toEmail());
            Content content = new Content("text/html", request.htmlBody());
            Mail mail = new Mail(from, request.subject(), to, content);

            SendGrid sg = new SendGrid(request.secretReference());
            Request sgRequest = new Request();
            sgRequest.setMethod(Method.POST);
            sgRequest.setEndpoint("mail/send");
            sgRequest.setBody(mail.build());

            Response response = sg.api(sgRequest);

            if (response.getStatusCode() >= 200 && response.getStatusCode() < 300) {
                String providerMessageId = response.getHeaders().getOrDefault("X-Message-Id", "sendgrid-unknown");
                return EmailSendResult.success(providerMessageId, String.valueOf(response.getStatusCode()));
            } else {
                return EmailSendResult.failure(
                        "SENDGRID_ERROR_" + response.getStatusCode(),
                        response.getBody()
                );
            }

        } catch (Exception ex) {
            return EmailSendResult.failure("SENDGRID_SEND_FAILED", ex.getMessage());
        }
    }
}