package com.actify.invoice.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Every unsafe free-demo configuration refuses to start - before any bean or connection exists - and the
 * production profiles can never use the ephemeral demo folder. No database, no web server.
 */
class RenderFreeDemoGuardTest {

    /** Exactly what application-render-free-demo.properties produces, with nothing overridden. */
    private static MockEnvironment demo() {
        MockEnvironment env = new MockEnvironment()
                .withProperty(RenderFreeDemoGuard.PROFILE_FILE_MARKER, "true")
                .withProperty("invoice.storage.dir", RenderFreeDemoGuard.STORAGE_ROOT)
                .withProperty("invoice.storage.verify-on-startup", "true")
                .withProperty("invoice.auth.cookie-secure", "true")
                .withProperty("invoice.jwt.allow-ephemeral-secret", "false")
                .withProperty("invoice.worker.enabled", "false")
                .withProperty("invoice.legacy-extract.enabled", "false")
                .withProperty("gemini.api-key", "")
                .withProperty(FreeDemoUploadPolicy.ALLOWLIST_PROPERTY, HASH_A + ", " + HASH_B);
        env.setActiveProfiles(RenderFreeDemoGuard.PROFILE);
        return env;
    }

    private static final String HASH_A = "85a802e3f35521ac1ee674e5cc807f9f43cbd62716ba8586c15135d0ea05a323";
    private static final String HASH_B = "82a4a2ae9865fd6aedc2b56d52e7da717e5e6dc574fd13987a2300efd547e8a8";

    @Test
    void uploadsWithoutAWellFormedFixtureAllowlistAreRefused() {
        for (String bad : new String[]{"", " ", HASH_A + ",not-a-hash", HASH_A + ",," + HASH_B, "abc", HASH_A.substring(1)}) {
            refused(demo().withProperty(FreeDemoUploadPolicy.ALLOWLIST_PROPERTY, bad), "allowed-upload-sha256");
        }
    }

    private static void refused(MockEnvironment env, String expected) {
        assertThatThrownBy(() -> RenderFreeDemoGuard.check(env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("RenderFreeDemoGuard: REFUSING")
                .hasMessageContaining(expected);
    }

    @Test
    void theUnmodifiedDemoProfileStarts() {
        assertThatCode(() -> RenderFreeDemoGuard.check(demo())).doesNotThrowAnyException();
        assertThatCode(() -> RenderFreeDemoGuard.check(demo().withProperty("invoice.storage.dir",
                RenderFreeDemoGuard.STORAGE_ROOT + "/sub"))).doesNotThrowAnyException();
        assertThatCode(() -> RenderFreeDemoGuard.check(demo().withProperty("invoice.bootstrap.admin-email",
                " Admin@Example.TEST "))).doesNotThrowAnyException();
    }

    @Test
    void theDemoNeverRunsTogetherWithAnotherProfileOrWithoutItsFile() {
        MockEnvironment withRender = demo();
        withRender.setActiveProfiles("render", RenderFreeDemoGuard.PROFILE);
        refused(withRender, "exactly [render-free-demo]");

        MockEnvironment withoutFile = demo().withProperty(RenderFreeDemoGuard.PROFILE_FILE_MARKER, "");
        refused(withoutFile, "was not loaded");
    }

    @Test
    void aGeminiKeyIsRefusedWithoutEverPrintingIt() {
        String key = "test-only-not-a-real-gemini-key";
        assertThatThrownBy(() -> RenderFreeDemoGuard.check(demo().withProperty("gemini.api-key", key)))
                .hasMessageContaining("Gemini API key is set")
                .hasMessageNotContaining(key);
    }

    @Test
    void backgroundProcessingAndCloudImportAreRefused() {
        refused(demo().withProperty("invoice.worker.enabled", "true"), "invoice.worker.enabled must be false");
        refused(demo().withProperty("invoice.legacy-extract.enabled", "TRUE"), "invoice.legacy-extract.enabled");
        refused(demo().withProperty("invoice.cloud.google.client-id", "x"), "invoice.cloud.google.client-id");
        refused(demo().withProperty("invoice.cloud.onedrive.client-id", "x"), "invoice.cloud.onedrive.client-id");
    }

    @Test
    void onlyTheEphemeralDemoFolderIsAcceptedForPdfs() {
        for (String bad : new String[]{"/var/data/invoice-documents", "/tmp", "/tmp/other",
                "/tmp/invoice-demo-documents-EPHEMERAL-x", "/tmp/invoice-demo-documents-EPHEMERAL/../etc",
                "/tmp/invoice-demo-documents-EPHEMERAL/./x", "/tmp//invoice-demo-documents-EPHEMERAL",
                "C:\\tmp\\invoice-demo-documents-EPHEMERAL", "relative/invoice-demo-documents-EPHEMERAL", ""}) {
            refused(demo().withProperty("invoice.storage.dir", bad), "invoice.storage.dir must be");
        }
        refused(demo().withProperty("invoice.storage.verify-on-startup", "false"), "verify-on-startup");
    }

    @Test
    void productionSecuritySettingsCannotBeRelaxed() {
        refused(demo().withProperty("invoice.auth.cookie-secure", "false"), "cookie-secure must stay true");
        refused(demo().withProperty("invoice.jwt.allow-ephemeral-secret", "true"), "allow-ephemeral-secret");
    }

    @Test
    void aRealLookingBootstrapAdminIsRefused() {
        refused(demo().withProperty("invoice.bootstrap.admin-email", "owner@real-domain.example"), "@example.test");
    }

    @Test
    void syntheticAddressesAreOnlyTheReservedTestDomain() {
        assertThat(RenderFreeDemoGuard.isSyntheticEmail("vendor.one@example.test")).isTrue();
        assertThat(RenderFreeDemoGuard.isSyntheticEmail(" Vendor@EXAMPLE.test ")).isTrue();
        for (String real : new String[]{"@example.test", "a@example.test.evil.com", "a@notexample.test",
                "a@example.com", "a@real-domain.example", "", null}) {
            assertThat(RenderFreeDemoGuard.isSyntheticEmail(real)).as(String.valueOf(real)).isFalse();
        }
    }

    @Test
    void otherProfilesMayNeverUseTheDemoFolder() {
        MockEnvironment render = new MockEnvironment()
                .withProperty("invoice.storage.dir", RenderFreeDemoGuard.STORAGE_ROOT);
        render.setActiveProfiles("render");
        assertThatThrownBy(() -> RenderFreeDemoGuard.check(render)).hasMessageContaining("not render-free-demo");

        MockEnvironment durable = new MockEnvironment().withProperty("invoice.storage.dir", "/var/data/invoice-documents");
        durable.setActiveProfiles("render");
        assertThatCode(() -> RenderFreeDemoGuard.check(durable)).doesNotThrowAnyException();

        // An unresolved INVOICE_STORAGE_DIR is the storage bean's job to refuse; the guard must not mask it.
        MockEnvironment unresolved = new MockEnvironment().withProperty("invoice.storage.dir", "${INVOICE_STORAGE_DIR}");
        unresolved.setActiveProfiles("render");
        assertThatCode(() -> RenderFreeDemoGuard.check(unresolved)).doesNotThrowAnyException();
    }
}
