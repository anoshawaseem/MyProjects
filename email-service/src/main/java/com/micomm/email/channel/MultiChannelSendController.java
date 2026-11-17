package com.micomm.email.channel;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.micomm.common.dto.ApiResponse;
import com.micomm.email.channel.dto.SendNotificationRequest;
import com.micomm.email.channel.dto.SendNotificationResponse;
import com.micomm.email.send.Idempotency;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/notifications")
public class MultiChannelSendController {

    private final MultiChannelSendService service;

    public MultiChannelSendController(MultiChannelSendService service) {
        this.service = service;
    }

    /** Same message to the same people on several channels; Idempotency-Key works as on send-bulk. */
    @PostMapping("/send")
    public ResponseEntity<ApiResponse<SendNotificationResponse>> send(
            @RequestHeader(value = Idempotency.HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody SendNotificationRequest request) {
        MultiChannelSendService.Submission submission = service.submit(request, idempotencyKey);
        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.ACCEPTED);
        if (submission.replayed()) {
            response.header("Idempotent-Replayed", "true");
        }
        return response.body(ApiResponse.ok(submission.response()));
    }
}
