package com.micomm.tenantmgmt.auth;

import com.micomm.common.security.JwtTokenProvider;
import com.micomm.tenantmgmt.auth.dto.LoginRequest;
import com.micomm.tenantmgmt.auth.dto.LoginResponse;
import com.micomm.tenantmgmt.auth.dto.RefreshRequest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.UUID;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public AuthService(UserRepository userRepository,
                        RefreshTokenRepository refreshTokenRepository,
                        JwtTokenProvider jwtTokenProvider) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @Transactional
    public LoginResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new IllegalArgumentException("Invalid credentials"));

        if (!"ACTIVE".equals(user.getStatus())) {
            throw new IllegalStateException("User account is disabled");
        }

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new IllegalArgumentException("Invalid credentials");
        }

          String accessToken = jwtTokenProvider.generateAccessToken(
                user.getId(), user.getRole().name(), user.getClubId());

        String rawRefreshToken = UUID.randomUUID().toString();
        RefreshToken refreshToken = new RefreshToken();
        refreshToken.setUserId(user.getId());
        refreshToken.setTokenHash(sha256(rawRefreshToken));
        refreshToken.setExpiresAt(OffsetDateTime.now().plusDays(7));
        refreshTokenRepository.save(refreshToken);

        user.setLastLoginAt(OffsetDateTime.now());
        userRepository.save(user);

        return new LoginResponse(
                accessToken, rawRefreshToken, user.getRole().name(), user.getClubId());
    }

    @Transactional
    public LoginResponse refresh(RefreshRequest request) {
        String hash = sha256(request.refreshToken());

        RefreshToken stored = refreshTokenRepository.findByTokenHashAndRevokedFalse(hash)
                .orElseThrow(() -> new IllegalArgumentException("Invalid refresh token"));

        if (stored.getExpiresAt().isBefore(OffsetDateTime.now())) {
            throw new IllegalStateException("Refresh token expired");
        }

        User user = userRepository.findById(stored.getUserId())
                .orElseThrow(() -> new IllegalStateException("User no longer exists"));

        String accessToken = jwtTokenProvider.generateAccessToken(
                user.getId(), user.getRole().name(), user.getClubId());

        stored.setRevoked(true);
        refreshTokenRepository.save(stored);

        String newRawRefreshToken = UUID.randomUUID().toString();
        RefreshToken newRefreshToken = new RefreshToken();
        newRefreshToken.setUserId(user.getId());
        newRefreshToken.setTokenHash(sha256(newRawRefreshToken));
        newRefreshToken.setExpiresAt(OffsetDateTime.now().plusDays(7));
        refreshTokenRepository.save(newRefreshToken);

        return new LoginResponse(
                accessToken, newRawRefreshToken, user.getRole().name(), user.getClubId());
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes());
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}