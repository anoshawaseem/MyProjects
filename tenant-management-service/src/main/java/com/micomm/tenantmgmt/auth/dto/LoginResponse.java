package com.micomm.tenantmgmt.auth.dto;

import java.util.UUID;

public record LoginResponse(
        String accessToken,
        String refreshToken,
        String role,
        UUID clubId
) {}