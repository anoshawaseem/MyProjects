package com.micomm.email.send;

import com.micomm.common.dto.ApiResponse;
import com.micomm.email.send.dto.SendBulkEmailRequest;
import com.micomm.email.send.dto.SendBulkEmailResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/emails")
public class EmailSendController {

    private final EmailSendService emailSendService;

    public EmailSendController(EmailSendService emailSendService) {
        this.emailSendService = emailSendService;
    }

    @PostMapping("/send-bulk")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<SendBulkEmailResponse> sendBulk(@Valid @RequestBody SendBulkEmailRequest request) {
        return ApiResponse.ok(emailSendService.submit(request));
    }
}