package com.actify.invoice.user;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Creates the very first ADMIN, once, from two launch-configuration variables:
 * INVOICE_BOOTSTRAP_ADMIN_EMAIL and INVOICE_BOOTSTRAP_ADMIN_PASSWORD.
 *
 *  - Runs only if BOTH are set and NO admin exists yet; otherwise it does nothing.
 *  - The password is hashed (BCrypt) like any other; it is never logged or stored in plain text.
 *  - There is no default password anywhere in the source code.
 *
 * (This is an ApplicationRunner, which runs after startup. That is fine here: it only inserts one row.
 * It is NOT a safety guard - the schema guard is SchemaSafetyGuard, which runs before any bean exists.)
 */
@Component
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final AppUserRepository userRepository;
    private final AppUserService userService;
    private final String email;
    private final String password;

    public AdminBootstrap(AppUserRepository userRepository, AppUserService userService,
                          @Value("${invoice.bootstrap.admin-email:}") String email,
                          @Value("${invoice.bootstrap.admin-password:}") String password) {
        this.userRepository = userRepository;
        this.userService = userService;
        this.email = email;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        createFirstAdminIfNeeded();
    }

    /** @return true when an admin was created. */
    public boolean createFirstAdminIfNeeded() {
        boolean configured = StringUtils.hasText(email) && StringUtils.hasText(password);
        if (userRepository.existsByRole(Role.ADMIN)) {
            if (configured) {
                log.warn("An ADMIN already exists, so INVOICE_BOOTSTRAP_ADMIN_EMAIL/PASSWORD are ignored. "
                        + "You can remove them from the launch configuration.");
            }
            return false;
        }
        if (!configured) {
            log.warn("No ADMIN user exists yet. Set INVOICE_BOOTSTRAP_ADMIN_EMAIL and INVOICE_BOOTSTRAP_ADMIN_PASSWORD "
                    + "in the launch configuration and restart to create the first one.");
            return false;
        }
        AppUser admin = userService.createUser(email, "Administrator", Role.ADMIN, password);
        log.info("Bootstrap ADMIN created (user id {}).", admin.getId());
        return true;
    }
}
