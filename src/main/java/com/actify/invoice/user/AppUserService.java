package com.actify.invoice.user;

import com.actify.invoice.config.RenderFreeDemoGuard;
import com.actify.invoice.dto.FieldErrorDto;
import com.actify.invoice.exception.InvalidRequestException;
import com.actify.invoice.exception.UserConflictException;
import com.actify.invoice.exception.UserNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Creating users and changing their role / enabled flag. Every rule about emails and passwords lives here,
 * so the bootstrap admin, the admin API and the login check all apply exactly the same rules.
 */
@Service
public class AppUserService {

    private static final Logger log = LoggerFactory.getLogger(AppUserService.class);

    /**
     * BCrypt only uses the first 72 BYTES of a password. Spring Security 5.7.11 (CVE-2025-22228) can even
     * accept a different password that shares those 72 bytes. So longer passwords are rejected, never truncated.
     * Bytes, not characters: "é" is 2 bytes in UTF-8, an emoji is 4.
     */
    public static final int MAX_PASSWORD_BYTES = 72;
    public static final int MIN_PASSWORD_LENGTH = 12;
    static final int MAX_EMAIL_LENGTH = 254;
    static final int MAX_DISPLAY_NAME_LENGTH = 100;

    // Deliberately simple: something@something.something, no spaces. The real check is that the person can log in.
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    // Free demo only (RenderFreeDemoGuard): every account must use a reserved, never-deliverable @example.test address.
    private final boolean syntheticAccountsOnly;

    @Autowired
    public AppUserService(AppUserRepository userRepository, PasswordEncoder passwordEncoder, Environment environment) {
        this(userRepository, passwordEncoder, RenderFreeDemoGuard.isActive(environment));
    }

    public AppUserService(AppUserRepository userRepository, PasswordEncoder passwordEncoder) {
        this(userRepository, passwordEncoder, false);
    }

    AppUserService(AppUserRepository userRepository, PasswordEncoder passwordEncoder, boolean syntheticAccountsOnly) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.syntheticAccountsOnly = syntheticAccountsOnly;
    }

    /** Lower-case + trim, with a fixed locale so e.g. a Turkish default locale cannot change "I". */
    public static String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    /** True when BCrypt would read the whole password (at most 72 UTF-8 bytes). */
    public static boolean fitsBcrypt(String password) {
        return password != null && password.getBytes(StandardCharsets.UTF_8).length <= MAX_PASSWORD_BYTES;
    }

    @Transactional
    public AppUser createUser(String email, String displayName, Role role, String password) {
        String normalizedEmail = normalizeEmail(email);
        List<FieldErrorDto> errors = new ArrayList<>();
        if (normalizedEmail.isEmpty() || normalizedEmail.length() > MAX_EMAIL_LENGTH || !EMAIL.matcher(normalizedEmail).matches()) {
            errors.add(new FieldErrorDto("email", "email must be a valid email address"));
        } else if (syntheticAccountsOnly && !RenderFreeDemoGuard.isSyntheticEmail(normalizedEmail)) {
            errors.add(new FieldErrorDto("email", "this temporary demo accepts only synthetic "
                    + RenderFreeDemoGuard.SYNTHETIC_EMAIL_SUFFIX + " addresses"));
        }
        // Optional: when left blank it defaults to the e-mail before the "@" - the name the UI shows anyway.
        String name = displayName == null ? "" : displayName.trim();
        if (name.isEmpty()) {
            String fromEmail = UserNames.fromEmail(normalizedEmail);
            name = fromEmail == null ? "" : fromEmail;
        }
        if (name.isEmpty() || name.length() > MAX_DISPLAY_NAME_LENGTH) {
            errors.add(new FieldErrorDto("displayName", "displayName must be 1-" + MAX_DISPLAY_NAME_LENGTH
                    + " characters (leave it blank to use the e-mail before the \"@\")"));
        }
        if (role == null) {
            errors.add(new FieldErrorDto("role", "role is required (ADMIN, VENDOR or REVIEWER)"));
        }
        checkPassword(password, errors);
        if (!errors.isEmpty()) {
            throw new InvalidRequestException(joinMessages(errors), errors);
        }
        if (userRepository.existsByEmail(normalizedEmail)) {
            throw new UserConflictException("A user with this email already exists.");
        }
        AppUser user = userRepository.save(new AppUser(normalizedEmail, name, passwordEncoder.encode(password), role));
        log.info("User {} created with role {}", user.getId(), role);
        return user;
    }

    @Transactional(readOnly = true)
    public List<AppUser> listUsers() {
        return userRepository.findAllByOrderByIdAsc();
    }

    @Transactional
    public AppUser changeRole(Long userId, Role newRole) {
        if (newRole == null) {
            List<FieldErrorDto> errors = new ArrayList<>();
            errors.add(new FieldErrorDto("role", "role is required (ADMIN, VENDOR or REVIEWER)"));
            throw new InvalidRequestException("role is required", errors);
        }
        AppUser user = find(userId);
        if (user.getRole() == newRole) {
            return user;
        }
        if (user.getRole() == Role.ADMIN && user.isEnabled()) {
            requireAnotherEnabledAdmin();
        }
        user.setRole(newRole);
        user.bumpTokenVersion(); // tokens issued with the old role stop working immediately
        log.info("User {} role changed to {}", userId, newRole);
        return user;
    }

    /**
     * Renames a user (the label shown everywhere, see UserNames.label). Blank = back to the e-mail before the "@".
     * Same 1-100 character rule as createUser; login, role and tokens are untouched.
     */
    @Transactional
    public AppUser changeDisplayName(Long userId, String displayName) {
        AppUser user = find(userId);
        String name = displayName == null ? "" : displayName.trim();
        if (name.isEmpty()) {
            String fromEmail = UserNames.fromEmail(user.getEmail());
            name = fromEmail == null ? "" : fromEmail;
        }
        if (name.isEmpty() || name.length() > MAX_DISPLAY_NAME_LENGTH) {
            throw new InvalidRequestException("displayName must be 1-" + MAX_DISPLAY_NAME_LENGTH + " characters",
                    java.util.Collections.singletonList(new FieldErrorDto("displayName", "displayName must be 1-"
                            + MAX_DISPLAY_NAME_LENGTH + " characters (leave it blank to use the e-mail before the \"@\")")));
        }
        user.setDisplayName(name);
        log.info("User {} display name changed", userId);
        return user;
    }

    @Transactional
    public AppUser setEnabled(Long userId, boolean enabled) {
        AppUser user = find(userId);
        if (user.isEnabled() == enabled) {
            return user;
        }
        if (!enabled && user.getRole() == Role.ADMIN) {
            requireAnotherEnabledAdmin();
        }
        user.setEnabled(enabled);
        user.bumpTokenVersion();
        log.info("User {} {}", userId, enabled ? "enabled" : "disabled");
        return user;
    }

    /** "Log out everywhere": every token issued before now stops working. */
    @Transactional
    public void revokeAllTokens(Long userId) {
        find(userId).bumpTokenVersion();
    }

    private AppUser find(Long userId) {
        return userRepository.findById(userId).orElseThrow(() -> new UserNotFoundException(userId));
    }

    // Never leave the application without an administrator who can log in.
    private void requireAnotherEnabledAdmin() {
        if (userRepository.countByRoleAndEnabledTrue(Role.ADMIN) <= 1) {
            throw new UserConflictException("This is the last enabled ADMIN. Create or enable another ADMIN first.");
        }
    }

    static void checkPassword(String password, List<FieldErrorDto> errors) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            errors.add(new FieldErrorDto("password", "password must be at least " + MIN_PASSWORD_LENGTH + " characters"));
        } else if (!fitsBcrypt(password)) {
            errors.add(new FieldErrorDto("password", "password must be at most " + MAX_PASSWORD_BYTES
                    + " bytes in UTF-8 (accented letters and emoji count as 2-4 bytes)"));
        }
    }

    private static String joinMessages(List<FieldErrorDto> errors) {
        List<String> messages = new ArrayList<>();
        for (FieldErrorDto error : errors) {
            messages.add(error.getMessage());
        }
        return String.join("; ", messages);
    }
}
