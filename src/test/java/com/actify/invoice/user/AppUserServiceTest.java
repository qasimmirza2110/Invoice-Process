package com.actify.invoice.user;

import com.actify.invoice.exception.InvalidRequestException;
import com.actify.invoice.exception.UserConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * User management rules: normalized e-mails, BCrypt hashes only, the 12-72 byte password rule (bytes, not characters;
 * never truncated), display-name rules, duplicate e-mail conflicts, token-version raises on role change / disable /
 * "log out everywhere", and that the last enabled admin can never be disabled or demoted.
 */
@ExtendWith(MockitoExtension.class)
class AppUserServiceTest {

    @Mock
    private AppUserRepository repository;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4); // fast cost for tests
    private AppUserService service;

    @BeforeEach
    void setUp() {
        service = new AppUserService(repository, encoder);
    }

    @Test
    void theFreeDemoAcceptsOnlySyntheticExampleTestAccounts() {
        AppUserService demo = new AppUserService(repository, encoder, true);
        assertThatThrownBy(() -> demo.createUser("owner@real-domain.example", "Owner", Role.ADMIN, "a-valid-password-1"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("only synthetic @example.test");

        when(repository.existsByEmail("vendor.one@example.test")).thenReturn(false);
        when(repository.save(any(AppUser.class))).thenAnswer(invocation -> invocation.getArgument(0));
        assertThat(demo.createUser(" Vendor.One@Example.TEST ", "Vendor One", Role.VENDOR, "a-valid-password-1")
                .getEmail()).isEqualTo("vendor.one@example.test");
    }

    @Test
    void outsideTheDemoRealDomainsStayAllowed() {
        when(repository.existsByEmail("owner@company.example")).thenReturn(false);
        when(repository.save(any(AppUser.class))).thenAnswer(invocation -> invocation.getArgument(0));
        assertThat(service.createUser("owner@company.example", "Owner", Role.ADMIN, "a-valid-password-1").getEmail())
                .isEqualTo("owner@company.example");
    }

    @Test
    void emailIsTrimmedAndLowerCasedWithAFixedLocale() {
        assertThat(AppUserService.normalizeEmail("  Admin.ONE@Example.TEST ")).isEqualTo("admin.one@example.test");
        assertThat(AppUserService.normalizeEmail(null)).isEmpty();
    }

    @Test
    void passwordByteLimitCountsUtf8BytesNotCharacters() {
        String ascii72 = repeat("a", 72);
        String ascii73 = repeat("a", 73);
        String twoByte36 = repeat("é", 36);   // 36 characters, 72 bytes
        String twoByte37 = repeat("é", 37);   // 37 characters, 74 bytes
        String emoji18 = repeat("😀", 18); // 18 emoji, 72 bytes
        String emoji19 = repeat("😀", 19); // 76 bytes

        assertThat(twoByte37.getBytes(StandardCharsets.UTF_8)).hasSize(74);
        assertThat(AppUserService.fitsBcrypt(ascii72)).isTrue();
        assertThat(AppUserService.fitsBcrypt(ascii73)).isFalse();
        assertThat(AppUserService.fitsBcrypt(twoByte36)).isTrue();
        assertThat(AppUserService.fitsBcrypt(twoByte37)).isFalse();
        assertThat(AppUserService.fitsBcrypt(emoji18)).isTrue();
        assertThat(AppUserService.fitsBcrypt(emoji19)).isFalse();
    }

    @Test
    void createStoresNormalizedEmailAndABcryptHashNeverThePassword() {
        when(repository.existsByEmail("new.user@example.test")).thenReturn(false);
        when(repository.save(any(AppUser.class))).thenAnswer(i -> i.getArgument(0));

        AppUser user = service.createUser(" New.User@Example.test", " New User ", Role.VENDOR, "correct horse battery");

        assertThat(user.getEmail()).isEqualTo("new.user@example.test");
        assertThat(user.getDisplayName()).isEqualTo("New User");
        assertThat(user.getPasswordHash()).startsWith("$2a$").doesNotContain("correct horse battery");
        assertThat(encoder.matches("correct horse battery", user.getPasswordHash())).isTrue();
        assertThat(user.getTokenVersion()).isZero();
        assertThat(user.isEnabled()).isTrue();
    }

    @Test
    void passwordLongerThan72BytesIsRejectedNotTruncated() {
        String tooLong = repeat("é", 37); // 74 bytes, only 37 characters

        assertThatThrownBy(() -> service.createUser("x@example.test", "X", Role.VENDOR, tooLong))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("72 bytes");
        verify(repository, never()).save(any());
    }

    @Test
    void shortPasswordBadEmailMissingNameAndRoleAreAllReportedTogether() {
        assertThatThrownBy(() -> service.createUser("", " ", null, "short"))
                .isInstanceOf(InvalidRequestException.class)
                .satisfies(e -> assertThat(((InvalidRequestException) e).getFieldErrors())
                        .extracting("field").containsExactly("email", "displayName", "role", "password"));
    }

    @Test
    void blankDisplayNameDefaultsToTheEmailBeforeTheAt() {
        when(repository.save(any(AppUser.class))).thenAnswer(inv -> inv.getArgument(0));
        AppUser user = service.createUser("Alex@example.test", "  ", Role.REVIEWER, "a-long-enough-password");
        assertThat(user.getDisplayName()).isEqualTo("alex");
        assertThat(user.getName()).isEqualTo("alex");
    }

    @Test
    void tooLongDisplayNameIsStillRejected() {
        char[] longName = new char[101];
        java.util.Arrays.fill(longName, 'x');
        assertThatThrownBy(() -> service.createUser("x@example.test", new String(longName), Role.VENDOR,
                "a-long-enough-password"))
                .isInstanceOf(InvalidRequestException.class)
                .satisfies(e -> assertThat(((InvalidRequestException) e).getFieldErrors())
                        .extracting("field").containsExactly("displayName"));
    }

    @Test
    void anAdminCanRenameAUserBlankMeansTheEmailNameAndTokensStayValid() {
        AppUser admin = new AppUser("admin@example.test", "Administrator", "hash", Role.ADMIN);
        admin.setIdForTest(1L);
        when(repository.findById(1L)).thenReturn(Optional.of(admin));

        service.changeDisplayName(1L, "  Actify Admin  ");
        assertThat(admin.getDisplayName()).isEqualTo("Actify Admin");
        assertThat(admin.getName()).isEqualTo("Actify Admin");
        assertThat(admin.getTokenVersion()).isZero(); // a rename does not log anyone out

        service.changeDisplayName(1L, " ");
        assertThat(admin.getName()).isEqualTo("admin");

        char[] longName = new char[101];
        java.util.Arrays.fill(longName, 'x');
        assertThatThrownBy(() -> service.changeDisplayName(1L, new String(longName)))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void duplicateEmailIsAConflict() {
        when(repository.existsByEmail("dup@example.test")).thenReturn(true);

        assertThatThrownBy(() -> service.createUser("DUP@example.test", "Dup", Role.VENDOR, "long enough password"))
                .isInstanceOf(UserConflictException.class);
    }

    @Test
    void roleChangeAndDisableRaiseTheTokenVersion() {
        AppUser vendor = user(7L, Role.VENDOR);
        when(repository.findById(7L)).thenReturn(Optional.of(vendor));

        service.changeRole(7L, Role.REVIEWER);
        assertThat(vendor.getRole()).isEqualTo(Role.REVIEWER);
        assertThat(vendor.getTokenVersion()).isEqualTo(1);

        service.setEnabled(7L, false);
        assertThat(vendor.isEnabled()).isFalse();
        assertThat(vendor.getTokenVersion()).isEqualTo(2);

        service.setEnabled(7L, false); // no change -> no new version
        assertThat(vendor.getTokenVersion()).isEqualTo(2);
    }

    @Test
    void theLastEnabledAdminCannotBeDisabledOrDemoted() {
        AppUser admin = user(1L, Role.ADMIN);
        when(repository.findById(1L)).thenReturn(Optional.of(admin));
        when(repository.countByRoleAndEnabledTrue(Role.ADMIN)).thenReturn(1L);

        assertThatThrownBy(() -> service.setEnabled(1L, false)).isInstanceOf(UserConflictException.class);
        assertThatThrownBy(() -> service.changeRole(1L, Role.VENDOR)).isInstanceOf(UserConflictException.class);
        assertThat(admin.isEnabled()).isTrue();
        assertThat(admin.getRole()).isEqualTo(Role.ADMIN);
        assertThat(admin.getTokenVersion()).isZero();
    }

    @Test
    void revokeAllTokensRaisesTheTokenVersion() {
        AppUser user = user(3L, Role.REVIEWER);
        when(repository.findById(3L)).thenReturn(Optional.of(user));

        service.revokeAllTokens(3L);

        assertThat(user.getTokenVersion()).isEqualTo(1);
    }

    private static AppUser user(Long id, Role role) {
        AppUser user = new AppUser("u" + id + "@example.test", "U" + id, "hash", role);
        user.setIdForTest(id);
        return user;
    }

    private static String repeat(String s, int times) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < times; i++) {
            b.append(s);
        }
        return b.toString();
    }
}
