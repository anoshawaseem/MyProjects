package com.micomm.tenantmgmt.auth;

import com.micomm.common.audit.AuditActions;
import com.micomm.common.audit.AuditEvent;
import com.micomm.common.security.JwtTokenProvider;
import com.micomm.common.security.MicommActor;
import com.micomm.tenantmgmt.audit.ClubAuditLog;
import com.micomm.tenantmgmt.auth.dto.LoginRequest;
import com.micomm.tenantmgmt.auth.dto.LoginResponse;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final ClubAuditLog clubAuditLog;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public AuthService(UserRepository userRepository,
                        JwtTokenProvider jwtTokenProvider,
                        ClubAuditLog clubAuditLog) {
        this.userRepository = userRepository;
        this.jwtTokenProvider = jwtTokenProvider;
        this.clubAuditLog = clubAuditLog;
    }

    @Transactional
    public LoginResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new IllegalArgumentException("Invalid credentials"));

        if (!"ACTIVE".equals(user.getStatus())) {
            auditLogin(user, AuditActions.USER_LOGIN_FAILED, "account disabled");
            throw new IllegalStateException("User account is disabled");
        }

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            auditLogin(user, AuditActions.USER_LOGIN_FAILED, "wrong password");
            throw new IllegalArgumentException("Invalid credentials");
        }

          String accessToken = jwtTokenProvider.generateAccessToken(
                user.getId(), user.getRole().name(), user.getClubId(), user.getEmail());
        auditLogin(user, AuditActions.USER_LOGIN, null);

        user.setLastLoginAt(OffsetDateTime.now());
        userRepository.save(user);

        return new LoginResponse(accessToken, user.getRole().name(), user.getClubId());
    }

    /** Sign-ins of club users go to their club's audit log; platform admins belong to no club. */
    private void auditLogin(User user, String action, String reason) {
        if (user.getClubId() != null) {
            clubAuditLog.record(AuditEvent.of(user.getClubId(), null, action, "USER", user.getId())
                    .with("reason", reason)
                    .by(MicommActor.user(user.getId(), user.getEmail())));
        }
    }
}