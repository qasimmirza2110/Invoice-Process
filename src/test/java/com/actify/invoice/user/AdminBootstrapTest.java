package com.actify.invoice.user;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminBootstrapTest {

    private static final String PASSWORD = "bootstrap-password-for-tests";

    private final AppUserRepository repository = mock(AppUserRepository.class);
    private final AppUserService userService = new AppUserService(repository, new BCryptPasswordEncoder(4));

    // Captures every log line of the bootstrap and the user service, at INFO and above.
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final List<Logger> loggers = new ArrayList<>();

    @BeforeEach
    void captureLogs() {
        logs.start();
        for (Class<?> c : new Class<?>[]{AdminBootstrap.class, AppUserService.class}) {
            Logger logger = (Logger) LoggerFactory.getLogger(c);
            logger.setLevel(Level.INFO);
            logger.addAppender(logs);
            loggers.add(logger);
        }
    }

    @AfterEach
    void stopCapturing() {
        for (Logger logger : loggers) {
            logger.detachAppender(logs);
            logger.setLevel(null);
        }
    }

    private String allLogText() {
        StringBuilder text = new StringBuilder();
        for (ILoggingEvent event : logs.list) {
            text.append(event.getFormattedMessage()).append('\n');
        }
        return text.toString();
    }

    @Test
    void createsTheFirstAdminOnceAndNeverLogsThePassword() {
        when(repository.existsByRole(Role.ADMIN)).thenReturn(false);
        when(repository.save(any(AppUser.class))).thenAnswer(i -> {
            AppUser u = i.getArgument(0);
            u.setIdForTest(1L);
            return u;
        });

        boolean created = new AdminBootstrap(repository, userService, "Boss@Example.test", PASSWORD).createFirstAdminIfNeeded();

        assertThat(created).isTrue();
        verify(repository).save(any(AppUser.class));
        assertThat(allLogText()).contains("Bootstrap ADMIN created").doesNotContain(PASSWORD);
    }

    @Test
    void doesNothingWhenAnAdminAlreadyExists() {
        when(repository.existsByRole(Role.ADMIN)).thenReturn(true);

        boolean created = new AdminBootstrap(repository, userService, "boss@example.test", PASSWORD).createFirstAdminIfNeeded();

        assertThat(created).isFalse();
        verify(repository, never()).save(any());
        assertThat(allLogText()).contains("already exists").doesNotContain(PASSWORD);
    }

    @Test
    void doesNothingWhenNotConfigured() {
        when(repository.existsByRole(Role.ADMIN)).thenReturn(false);

        assertThat(new AdminBootstrap(repository, userService, "", "").createFirstAdminIfNeeded()).isFalse();
        assertThat(new AdminBootstrap(repository, userService, "boss@example.test", "").createFirstAdminIfNeeded()).isFalse();
        verify(repository, never()).save(any());
    }
}
