package com.actify.invoice.cloud;

import com.actify.invoice.entity.BatchSource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The honest provider states. Nothing here talks to Google or Microsoft. */
class CloudSourceSettingsTest {

    private static final String GOOGLE_CLIENT = "123-abc.apps.googleusercontent.com";
    private static final String GUID = "11111111-2222-3333-4444-555555555555";

    private static CloudSourceSettings settings(String gClient, String gKey, String gApp, boolean gLive,
                                                String accountType, String msClient, String tenant, String spUrl,
                                                boolean msLive) {
        return new CloudSourceSettings(gClient, gKey, gApp, gLive, msClient, accountType, tenant, spUrl, msLive);
    }

    private static CloudSourceSettings nothingSet() {
        return settings("", "", "", false, "", "", "", "", false);
    }

    @Test
    void withNothingSetBothProvidersAreNotConfiguredAndExposeNoBrowserValues() {
        CloudSourceSettings s = nothingSet();
        List<Map<String, Object>> all = s.describeAll();

        assertThat(all).extracting(m -> m.get("source")).containsExactly("GOOGLE_DRIVE", "ONEDRIVE");
        assertThat(all).extracting(m -> m.get("status")).containsOnly("NOT_CONFIGURED");
        assertThat(all).allSatisfy(m -> assertThat(m).doesNotContainKey("browserConfig"));
        assertThat(s.isUsable(BatchSource.LOCAL)).isTrue();
        assertThat(s.isUsable(BatchSource.GOOGLE_DRIVE)).isFalse();
        assertThat(s.isUsable(BatchSource.ONEDRIVE)).isFalse();
    }

    @Test
    void oneDriveAccountTypeIsTheOwnersDecisionAndIsNeverGuessed() {
        CloudSourceSettings s = settings("", "", "", false, "", GUID, "", "", false);
        assertThat(s.describeOneDrive().get("status")).isEqualTo("NOT_CONFIGURED");
        assertThat(s.oneDriveProblems()).anySatisfy(p -> assertThat(p).contains("owner decision"));
    }

    @Test
    void completeGoogleValuesAreConfiguredButNotLiveTestedUntilTheOwnerSaysSo() {
        CloudSourceSettings s = settings(GOOGLE_CLIENT, "browser-key", "987654321", false, "", "", "", "", false);
        Map<String, Object> google = s.describeGoogle();

        assertThat(google.get("status")).isEqualTo("CONFIGURED_NOT_LIVE_TESTED");
        assertThat(google.get("mockTested")).isEqualTo(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> browser = (Map<String, Object>) google.get("browserConfig");
        assertThat(browser).containsEntry("scope", "https://www.googleapis.com/auth/drive.file")
                .containsEntry("appId", "987654321");

        assertThat(settings(GOOGLE_CLIENT, "browser-key", "987654321", true, "", "", "", "", false)
                .describeGoogle().get("status")).isEqualTo("LIVE_TESTED");
    }

    @Test
    void malformedGoogleValuesAreReportedNotUsed() {
        CloudSourceSettings s = settings("not-a-client-id", "k", "project-name", true, "", "", "", "", false);
        assertThat(s.describeGoogle().get("status")).isEqualTo("NOT_CONFIGURED");
        assertThat(s.googleProblems()).hasSize(2);
    }

    @Test
    void personalOneDriveUsesTheConsumerAuthorityAndPicker() {
        CloudSourceSettings s = settings("", "", "", false, "personal", GUID, "", "", false);
        @SuppressWarnings("unchecked")
        Map<String, Object> browser = (Map<String, Object>) s.describeOneDrive().get("browserConfig");

        assertThat(s.isUsable(BatchSource.ONEDRIVE)).isTrue();
        assertThat(browser).containsEntry("authority", "https://login.microsoftonline.com/consumers")
                .containsEntry("pickerBaseUrl", "https://onedrive.live.com/picker")
                .containsEntry("accountType", "PERSONAL");
    }

    @Test
    void workOrSchoolOneDriveNeedsTenantAndAnHttpsSharePointAddress() {
        assertThat(settings("", "", "", false, "WORK_OR_SCHOOL", GUID, "", "", false).oneDriveProblems()).hasSize(2);
        assertThat(settings("", "", "", false, "WORK_OR_SCHOOL", GUID, GUID, "http://contoso-my.sharepoint.com", false)
                .oneDriveProblems()).hasSize(1);
        assertThat(settings("", "", "", false, "WORK_OR_SCHOOL", GUID, GUID, "https://evil.example.com", false)
                .oneDriveProblems()).hasSize(1);

        CloudSourceSettings ok = settings("", "", "", false, "WORK_OR_SCHOOL", GUID, GUID,
                "https://contoso-my.sharepoint.com/", false);
        @SuppressWarnings("unchecked")
        Map<String, Object> browser = (Map<String, Object>) ok.describeOneDrive().get("browserConfig");
        assertThat(browser).containsEntry("authority", "https://login.microsoftonline.com/" + GUID)
                .containsEntry("pickerBaseUrl", "https://contoso-my.sharepoint.com");
    }
}
