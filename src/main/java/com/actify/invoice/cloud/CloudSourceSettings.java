package com.actify.invoice.cloud;

import com.actify.invoice.entity.BatchSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Google Drive / OneDrive picker settings, read from invoice.cloud.* (environment variables INVOICE_GOOGLE_* and
 * INVOICE_ONEDRIVE_*). Only PUBLIC browser values live here: OAuth client ids, the Google project number and the
 * Google browser API key (which Google expects to be visible in web pages and restricted by HTTP referrer).
 * There is no client secret anywhere: both pickers run in the browser with the user's own delegated login.
 *
 * HOW THE FILES FLOW (both providers): the browser opens the provider's picker, downloads each chosen file with a
 * short-lived token that stays in browser memory, and sends the bytes to the SAME endpoint as a local file
 * (POST /api/batches/{id}/files). The server therefore applies the exact same PDF, size, SHA-256 duplicate and
 * storage rules, and never sees, stores or logs a provider token.
 *
 * Status is reported truthfully: NOT_CONFIGURED (values missing or invalid), CONFIGURED_NOT_LIVE_TESTED (values
 * present, the code is only mock-tested) or LIVE_TESTED (the owner set invoice.cloud.*.live-tested=true after an
 * approved real test with their own app registration).
 *
 * Since the backend became API-only, the picker UI itself (Google Picker, MSAL sign-in, the OAuth redirect page) is
 * the frontend's job. This class only publishes the public configuration a frontend needs; the old vanilla-JS
 * implementation is archived outside the repository and must be re-implemented in the new frontend.
 */
@Component
public class CloudSourceSettings {

    public enum Status { NOT_CONFIGURED, CONFIGURED_NOT_LIVE_TESTED, LIVE_TESTED }

    public enum OneDriveAccountType { PERSONAL, WORK_OR_SCHOOL }

    /** OneDrive personal accounts (Microsoft docs, "OneDrive Consumer Configuration"). */
    static final String PERSONAL_AUTHORITY = "https://login.microsoftonline.com/consumers";
    static final String PERSONAL_PICKER_BASE_URL = "https://onedrive.live.com/picker";
    static final String SETUP_GUIDE = "docs/PROVIDER_SETUP_GOOGLE_DRIVE_ONEDRIVE.md";

    private final String googleClientId;
    private final String googleApiKey;
    private final String googleAppId;
    private final boolean googleLiveTested;
    private final String oneDriveClientId;
    private final String oneDriveAccountType;
    private final String oneDriveTenantId;
    private final String oneDriveSharePointBaseUrl;
    private final boolean oneDriveLiveTested;

    public CloudSourceSettings(@Value("${invoice.cloud.google.client-id:}") String googleClientId,
                               @Value("${invoice.cloud.google.api-key:}") String googleApiKey,
                               @Value("${invoice.cloud.google.app-id:}") String googleAppId,
                               @Value("${invoice.cloud.google.live-tested:false}") boolean googleLiveTested,
                               @Value("${invoice.cloud.onedrive.client-id:}") String oneDriveClientId,
                               @Value("${invoice.cloud.onedrive.account-type:}") String oneDriveAccountType,
                               @Value("${invoice.cloud.onedrive.tenant-id:}") String oneDriveTenantId,
                               @Value("${invoice.cloud.onedrive.sharepoint-base-url:}") String oneDriveSharePointBaseUrl,
                               @Value("${invoice.cloud.onedrive.live-tested:false}") boolean oneDriveLiveTested) {
        this.googleClientId = trim(googleClientId);
        this.googleApiKey = trim(googleApiKey);
        this.googleAppId = trim(googleAppId);
        this.googleLiveTested = googleLiveTested;
        this.oneDriveClientId = trim(oneDriveClientId);
        this.oneDriveAccountType = trim(oneDriveAccountType).toUpperCase(Locale.ROOT);
        this.oneDriveTenantId = trim(oneDriveTenantId);
        this.oneDriveSharePointBaseUrl = trim(oneDriveSharePointBaseUrl);
        this.oneDriveLiveTested = oneDriveLiveTested;
    }

    /** One entry per cloud provider, in a fixed order. */
    public List<Map<String, Object>> describeAll() {
        List<Map<String, Object>> result = new ArrayList<>();
        result.add(describeGoogle());
        result.add(describeOneDrive());
        return result;
    }

    public boolean isUsable(BatchSource source) {
        switch (source) {
            case LOCAL:
                return true;
            case GOOGLE_DRIVE:
                return googleProblems().isEmpty();
            case ONEDRIVE:
                return oneDriveProblems().isEmpty();
            default:
                return false;
        }
    }

    Map<String, Object> describeGoogle() {
        List<String> problems = googleProblems();
        Map<String, Object> m = base(BatchSource.GOOGLE_DRIVE, "Google Drive", problems, googleLiveTested);
        if (problems.isEmpty()) {
            Map<String, Object> browser = new LinkedHashMap<>();
            browser.put("clientId", googleClientId);
            browser.put("apiKey", googleApiKey);
            browser.put("appId", googleAppId);
            browser.put("scope", "https://www.googleapis.com/auth/drive.file");
            m.put("browserConfig", browser);
        }
        m.put("setupHint", "Set INVOICE_GOOGLE_CLIENT_ID (OAuth web client), INVOICE_GOOGLE_API_KEY (browser key, "
                + "restricted to this site plus https://docs.google.com/* and to the Picker and Drive APIs) and "
                + "INVOICE_GOOGLE_APP_ID (Cloud project number); add this app's address as an authorised JavaScript "
                + "origin. See " + SETUP_GUIDE + ".");
        return m;
    }

    Map<String, Object> describeOneDrive() {
        List<String> problems = oneDriveProblems();
        Map<String, Object> m = base(BatchSource.ONEDRIVE, "Microsoft OneDrive", problems, oneDriveLiveTested);
        if (problems.isEmpty()) {
            boolean personal = OneDriveAccountType.PERSONAL.name().equals(oneDriveAccountType);
            Map<String, Object> browser = new LinkedHashMap<>();
            browser.put("clientId", oneDriveClientId);
            browser.put("accountType", oneDriveAccountType);
            browser.put("authority", personal ? PERSONAL_AUTHORITY
                    : "https://login.microsoftonline.com/" + oneDriveTenantId);
            browser.put("pickerBaseUrl", personal ? PERSONAL_PICKER_BASE_URL : stripSlash(oneDriveSharePointBaseUrl));
            m.put("browserConfig", browser);
        }
        m.put("setupHint", "Choose the account type first (PERSONAL or WORK_OR_SCHOOL) - this is the owner's "
                + "decision. Then set INVOICE_ONEDRIVE_ACCOUNT_TYPE, INVOICE_ONEDRIVE_CLIENT_ID (Entra app registration, "
                + "single-page application platform, redirect URI = the sign-in redirect page of the frontend) and, for "
                + "WORK_OR_SCHOOL, INVOICE_ONEDRIVE_TENANT_ID and INVOICE_ONEDRIVE_SHAREPOINT_BASE_URL "
                + "(https://<tenant>-my.sharepoint.com). See " + SETUP_GUIDE + ".");
        return m;
    }

    List<String> googleProblems() {
        List<String> problems = new ArrayList<>();
        if (googleClientId.isEmpty()) {
            problems.add("INVOICE_GOOGLE_CLIENT_ID is not set");
        } else if (!googleClientId.endsWith(".apps.googleusercontent.com")) {
            problems.add("INVOICE_GOOGLE_CLIENT_ID must end with .apps.googleusercontent.com");
        }
        if (googleApiKey.isEmpty()) {
            problems.add("INVOICE_GOOGLE_API_KEY is not set");
        }
        if (googleAppId.isEmpty()) {
            problems.add("INVOICE_GOOGLE_APP_ID is not set");
        } else if (!googleAppId.matches("\\d{1,20}")) {
            problems.add("INVOICE_GOOGLE_APP_ID must be the numeric Cloud project number");
        }
        return problems;
    }

    List<String> oneDriveProblems() {
        List<String> problems = new ArrayList<>();
        if (oneDriveAccountType.isEmpty()) {
            problems.add("INVOICE_ONEDRIVE_ACCOUNT_TYPE is not set (PERSONAL or WORK_OR_SCHOOL - owner decision)");
        } else if (!Arrays.asList("PERSONAL", "WORK_OR_SCHOOL").contains(oneDriveAccountType)) {
            problems.add("INVOICE_ONEDRIVE_ACCOUNT_TYPE must be PERSONAL or WORK_OR_SCHOOL");
        }
        if (oneDriveClientId.isEmpty()) {
            problems.add("INVOICE_ONEDRIVE_CLIENT_ID is not set");
        } else if (!isGuid(oneDriveClientId)) {
            problems.add("INVOICE_ONEDRIVE_CLIENT_ID must be the application (client) id GUID");
        }
        if (OneDriveAccountType.WORK_OR_SCHOOL.name().equals(oneDriveAccountType)) {
            if (!isGuid(oneDriveTenantId)) {
                problems.add("INVOICE_ONEDRIVE_TENANT_ID must be the directory (tenant) id GUID");
            }
            if (!isSharePointUrl(oneDriveSharePointBaseUrl)) {
                problems.add("INVOICE_ONEDRIVE_SHAREPOINT_BASE_URL must look like https://<tenant>-my.sharepoint.com");
            }
        }
        return problems;
    }

    private static Map<String, Object> base(BatchSource source, String label, List<String> problems, boolean liveTested) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("source", source.name());
        m.put("label", label);
        Status status = !problems.isEmpty() ? Status.NOT_CONFIGURED
                : liveTested ? Status.LIVE_TESTED : Status.CONFIGURED_NOT_LIVE_TESTED;
        m.put("status", status.name());
        // The browser code for both pickers is covered by offline mock tests only, never by a real provider call.
        m.put("mockTested", true);
        m.put("problems", problems);
        return m;
    }

    static boolean isGuid(String value) {
        return value != null && value.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    static boolean isSharePointUrl(String value) {
        try {
            URI uri = new URI(stripSlash(value));
            return "https".equals(uri.getScheme()) && uri.getHost() != null
                    && uri.getHost().toLowerCase(Locale.ROOT).endsWith(".sharepoint.com")
                    && (uri.getPath() == null || uri.getPath().isEmpty()) && uri.getQuery() == null;
        } catch (Exception e) {
            return false;
        }
    }

    private static String stripSlash(String value) {
        String v = trim(value);
        return v.endsWith("/") ? v.substring(0, v.length() - 1) : v;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
