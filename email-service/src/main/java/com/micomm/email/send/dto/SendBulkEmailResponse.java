package com.micomm.email.send.dto;

import java.util.UUID;

public record SendBulkEmailResponse(
        UUID requestId,
        String status,
        int recipientCount
) {}