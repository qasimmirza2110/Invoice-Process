package com.actify.invoice.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Fail-closed rules for the TEMPORARY free demo (profile "render-free-demo", docs/RENDER_DEPLOYMENT.md part A).
 *
 * Runs before any bean exists - like SchemaSafetyGuard, which still applies in full (database approval, no datasource
 * redirects, ddl-auto=validate). With the demo profile the start is refused unless:
 *  - the demo profile is the ONLY active profile and its properties file was really loaded;
 *  - no Gemini key is effective, the worker and the legacy extraction are off, no cloud picker is configured
 *    (environment variables override the profile file, so the EFFECTIVE values are checked);
 *  - PDFs go to the ephemeral demo folder and nowhere else (never /var/data, never a path with "..");
 *  - uploads are limited to the synthetic fixture PDFs (FreeDemoUploadPolicy; the SHA-256 list must be well-formed);
 *  - the login cookie stays Secure and a real JWT secret stays mandatory;
 *  - a configured bootstrap admin uses a synthetic address (AppUserService enforces it for every account).
 * Every other profile is refused when it points its PDF storage at the demo folder, so demo storage can never be
 * mistaken for durable storage.
 *
 * The demo only ADDS restrictions; nothing here relaxes authentication, CSRF or the database guard.
 */
@Component
public class RenderFreeDemoGuard implements BeanFactoryPostProcessor, EnvironmentAware, PriorityOrdered {

    private static final Logger log = LoggerFactory.getLogger(RenderFreeDemoGuard.class);

    public static final String PROFILE = "render-free-demo";
    public static final String STORAGE_ROOT = "/tmp/invoice-demo-documents-EPHEMERAL";
    /** The only e-mail domain the demo accepts for accounts (RFC 6761 reserves .test - it can never be real). */
    public static final String SYNTHETIC_EMAIL_SUFFIX = "@example.test";
    public static final String HEADER_NAME = "X-Invoice-Demo";
    public static final String HEADER_VALUE = "ephemeral-synthetic-only";
    static final String PROFILE_FILE_MARKER = "invoice.free-demo.profile-file-loaded";

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        check(environment);
        if (isActive(environment)) {
            log.warn("FREE DEMO MODE ({}): synthetic data only - no real customers, invoices or credentials. "
                    + "Uploaded PDFs are EPHEMERAL ({}) and disappear on restart, redeploy and spin-down. "
                    + "The free database expires 30 days after creation and has no backups. "
                    + "Gemini, the extraction worker and cloud pickers are disabled.", PROFILE, STORAGE_ROOT);
        }
    }

    /** True when the demo profile is active (with or without others - check() refuses the "with others" case). */
    public static boolean isActive(Environment env) {
        return Arrays.asList(env.getActiveProfiles()).contains(PROFILE);
    }

    /** Static and side-effect free, so every rule can be unit-tested with a MockEnvironment. */
    public static void check(Environment env) {
        if (!isActive(env)) {
            refuseDemoStorageOutsideTheDemo(env);
            return;
        }
        List<String> problems = new ArrayList<>();
        if (!Arrays.equals(env.getActiveProfiles(), new String[]{PROFILE})) {
            problems.add("active profiles are " + Arrays.toString(env.getActiveProfiles())
                    + "; the demo must run with exactly [" + PROFILE + "] (never together with render)");
        }
        if (!"true".equals(env.getProperty(PROFILE_FILE_MARKER))) {
            problems.add("application-" + PROFILE + ".properties was not loaded");
        }
        if (hasText(env, "gemini.api-key")) {
            problems.add("a Gemini API key is set (GEMINI_API_KEY) - the demo never calls Gemini; remove it");
        }
        requireFalse(env, "invoice.worker.enabled", "INVOICE_WORKER_ENABLED", problems);
        requireFalse(env, "invoice.legacy-extract.enabled", "INVOICE_LEGACY_EXTRACT_ENABLED", problems);
        for (String picker : Arrays.asList("invoice.cloud.google.client-id", "invoice.cloud.google.api-key",
                "invoice.cloud.google.app-id", "invoice.cloud.onedrive.client-id", "invoice.cloud.onedrive.tenant-id",
                "invoice.cloud.onedrive.sharepoint-base-url")) {
            if (hasText(env, picker)) {
                problems.add(picker + " is set - cloud pickers could import real documents; remove it");
            }
        }
        String storage = env.getProperty("invoice.storage.dir", "").trim();
        if (!isUnderDemoRoot(storage)) {
            problems.add("invoice.storage.dir must be " + STORAGE_ROOT + " (or a folder below it); "
                    + "the demo has no persistent disk and must never look like it has one");
        }
        if (!"true".equalsIgnoreCase(env.getProperty("invoice.storage.verify-on-startup", "").trim())) {
            problems.add("invoice.storage.verify-on-startup must stay true");
        }
        String allowlist = env.getProperty(FreeDemoUploadPolicy.ALLOWLIST_PROPERTY, "");
        String[] entries = allowlist.trim().isEmpty() ? new String[0] : allowlist.split(",");
        if (entries.length == 0 || FreeDemoUploadPolicy.parse(allowlist).size() != entries.length) {
            problems.add(FreeDemoUploadPolicy.ALLOWLIST_PROPERTY + " must list only the fixture SHA-256 values "
                    + "(64 lower-case hex each) - an empty or malformed list would make uploads unusable or unclear");
        }
        if (!"true".equalsIgnoreCase(env.getProperty("invoice.auth.cookie-secure", "").trim())) {
            problems.add("invoice.auth.cookie-secure must stay true (INVOICE_COOKIE_SECURE)");
        }
        if (!"false".equalsIgnoreCase(env.getProperty("invoice.jwt.allow-ephemeral-secret", "").trim())) {
            problems.add("invoice.jwt.allow-ephemeral-secret must stay false - set a throw-away INVOICE_JWT_SECRET");
        }
        String adminEmail = env.getProperty("invoice.bootstrap.admin-email", "").trim();
        if (!adminEmail.isEmpty() && !isSyntheticEmail(adminEmail)) {
            problems.add("INVOICE_BOOTSTRAP_ADMIN_EMAIL must end with " + SYNTHETIC_EMAIL_SUFFIX);
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("RenderFreeDemoGuard: REFUSING to start the free demo - "
                    + String.join("; ", problems) + ".");
        }
    }

    /** Accounts in the demo: only reserved, never-deliverable addresses (the value itself is never logged). */
    public static boolean isSyntheticEmail(String email) {
        String normalized = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        return normalized.endsWith(SYNTHETIC_EMAIL_SUFFIX) && normalized.length() > SYNTHETIC_EMAIL_SUFFIX.length();
    }

    /** POSIX path check on the string itself, so it behaves the same on the Windows build machine and in Linux. */
    static boolean isUnderDemoRoot(String path) {
        if (path.contains("\\") || path.contains("/../") || path.endsWith("/..") || path.contains("/./")
                || path.endsWith("/.") || path.contains("//")) {
            return false;
        }
        String trimmed = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        return trimmed.equals(STORAGE_ROOT) || trimmed.startsWith(STORAGE_ROOT + "/");
    }

    private static void refuseDemoStorageOutsideTheDemo(Environment env) {
        String storage;
        try {
            storage = env.getProperty("invoice.storage.dir", "").trim();
        } catch (IllegalArgumentException unresolved) {
            return; // e.g. render without INVOICE_STORAGE_DIR: the storage bean refuses that start itself
        }
        if (storage.replace('\\', '/').toLowerCase(Locale.ROOT).contains(STORAGE_ROOT.toLowerCase(Locale.ROOT))) {
            throw new IllegalStateException("RenderFreeDemoGuard: REFUSING to start - invoice.storage.dir points at "
                    + "the ephemeral free-demo folder, but the profile is not " + PROFILE
                    + ". Production PDFs need durable storage (docs/RENDER_DEPLOYMENT.md, section 6).");
        }
    }

    private static void requireFalse(Environment env, String property, String variable, List<String> problems) {
        if (!"false".equalsIgnoreCase(env.getProperty(property, "false").trim())) {
            problems.add(property + " must be false (" + variable + ")");
        }
    }

    private static boolean hasText(Environment env, String property) {
        String value = env.getProperty(property);
        return value != null && !value.trim().isEmpty();
    }

    @Override
    public int getOrder() {
        // Right after SchemaSafetyGuard (HIGHEST_PRECEDENCE); both run before any bean - and any connection - exists.
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }
}
