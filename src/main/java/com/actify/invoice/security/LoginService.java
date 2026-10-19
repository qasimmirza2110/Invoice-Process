package com.actify.invoice.security;

import com.actify.invoice.exception.AuthenticationFailedException;
import com.actify.invoice.exception.LoginLockedException;
import com.actify.invoice.user.AppUser;
import com.actify.invoice.user.AppUserRepository;
import com.actify.invoice.user.AppUserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Checks an email + password. Every failure looks the same to the caller (AuthenticationFailedException),
 * whether the email is unknown, the password is wrong, the account is disabled or the password is longer
 * than BCrypt can check - so nobody can find out which emails have accounts.
 */
@Service
public class LoginService {

    private static final Logger log = LoggerFactory.getLogger(LoginService.class);

    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final LoginAttemptLimiter limiter;

    // A real BCrypt hash of a random value: an unknown email still costs one full BCrypt check, so the
    // response time does not reveal whether the email exists.
    private final String dummyHash;

    public LoginService(AppUserRepository userRepository, PasswordEncoder passwordEncoder, LoginAttemptLimiter limiter) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.limiter = limiter;
        this.dummyHash = passwordEncoder.encode(java.util.UUID.randomUUID().toString());
    }

    @Transactional(readOnly = true)
    public AppUser login(String email, String password, String clientAddress) {
        String normalizedEmail = AppUserService.normalizeEmail(email);
        if (limiter.isLocked(normalizedEmail, clientAddress)) {
            throw new LoginLockedException();
        }

        AppUser user = null;
        boolean ok = false;
        // Over 72 bytes: rejected, never truncated (BCrypt would silently ignore the rest).
        if (!normalizedEmail.isEmpty() && AppUserService.fitsBcrypt(password)) {
            user = userRepository.findByEmail(normalizedEmail).orElse(null);
            boolean matches = passwordEncoder.matches(password, user != null ? user.getPasswordHash() : dummyHash);
            ok = user != null && matches && user.isEnabled();
        }

        if (!ok) {
            limiter.recordFailure(normalizedEmail, clientAddress);
            log.info("Failed login attempt"); // no email, no password in the log
            throw new AuthenticationFailedException();
        }
        limiter.recordSuccess(normalizedEmail);
        log.info("User {} logged in", user.getId());
        return user;
    }
}
