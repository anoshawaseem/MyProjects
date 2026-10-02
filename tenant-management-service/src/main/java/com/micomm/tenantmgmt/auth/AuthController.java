package com.micomm.tenantmgmt.auth;

import com.micomm.common.dto.ApiResponse;
import com.micomm.tenantmgmt.auth.dto.LoginRequest;
import com.micomm.tenantmgmt.auth.dto.LoginResponse;
import com.micomm.tenantmgmt.auth.dto.RefreshRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.ok(authService.login(request));
    }

    @PostMapping("/refresh")
    public ApiResponse<LoginResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        return ApiResponse.ok(authService.refresh(request));
    }
}