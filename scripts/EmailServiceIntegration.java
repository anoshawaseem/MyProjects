import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class EmailServiceIntegration {

    private static final String AUTH_BASE_URL = "http://localhost:8081";
    private static final String EMAIL_BASE_URL = "http://localhost:8082/api/v1/emails";

    private final HttpClient client = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    public String login(String email, String password) throws Exception {
        String payload = mapper.writeValueAsString(new LoginRequest(email, password));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(AUTH_BASE_URL + "/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("Login failed: " + response.statusCode() + " - " + response.body());
        }

        JsonNode root = mapper.readTree(response.body());
        return root.path("data").path("accessToken").asText();
    }

    public String sendBulkEmail(String accessToken,
                                 String clubId,
                                 String productId,
                                 String fromEmail,
                                 String subject,
                                 String htmlBody,
                                 String[] recipients) throws Exception {

        SendBulkEmailRequest req = new SendBulkEmailRequest(
                clubId, productId, fromEmail, subject, htmlBody, recipients);
        String payload = mapper.writeValueAsString(req);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(EMAIL_BASE_URL + "/send-bulk"))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 202) {
            throw new RuntimeException("Send email failed: " + response.statusCode() + " - " + response.body());
        }

        return response.body();
    }

    public static void main(String[] args) throws Exception {
        EmailServiceIntegration client = new EmailServiceIntegration();

        // Step 1: Login
        String accessToken = client.login("integration@micomm.com", "your-password");
        System.out.println("Access Token: " + accessToken);

        // Step 2: Call email-service using the token
        String result = client.sendBulkEmail(
                accessToken,
                "a7834cee-fb18-4ff1-863a-8206d7d0de8a",   // clubId
                "11111111-2222-3333-4444-555555555555",   // productId
                "noreply@club.com",                        // fromEmail
                "Hello from Java client",                  // subject
                "<p>Hi there!</p>",                         // htmlBody
                new String[]{"jane@example.com"}            // recipients
        );

        System.out.println("Email send response: " + result);
    }

    record LoginRequest(String email, String password) {}

    record SendBulkEmailRequest(
            String clubId,
            String productId,
            String fromEmail,
            String subject,
            String htmlBody,
            String[] recipients
    ) {}
}