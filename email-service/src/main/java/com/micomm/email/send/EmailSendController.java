package com.micomm.email.send;

import com.micomm.common.dto.ApiResponse;
import com.micomm.email.send.dto.SendBulkEmailRequest;
import com.micomm.email.send.dto.SendBulkEmailResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/emails")
public class EmailSendController {

    /** Set on responses that repeat an earlier send's answer instead of sending again. */
    static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private final EmailSendService emailSendService;

    public EmailSendController(EmailSendService emailSendService) {
        this.emailSendService = emailSendService;
    }

    /**
     * An optional Idempotency-Key header makes retries safe: repeating the same request with the
     * same key returns the original send (with Idempotent-Replayed: true) instead of sending again.
     */
    @PostMapping("/send-bulk")
    public ResponseEntity<ApiResponse<SendBulkEmailResponse>> sendBulk(
            @RequestHeader(value = Idempotency.HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody SendBulkEmailRequest request) {
        EmailSendService.Submission submission = emailSendService.submit(request, idempotencyKey);
        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.ACCEPTED);
        if (submission.replayed()) {
            response.header(REPLAYED_HEADER, "true");
        }
        return response.body(ApiResponse.ok(submission.response()));
    }
}
