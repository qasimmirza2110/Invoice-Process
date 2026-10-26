package com.actify.invoice.intake;

import com.actify.invoice.entity.BatchSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** One source per batch: a file may declare nothing (= the batch's source) or exactly the batch's source. */
class BatchSourcePolicyTest {

    @Test
    void missingSourceMeansLocalAndUnknownSourceIsRefused() {
        assertThat(BatchIntakeService.parseSource(null)).isEqualTo(BatchSource.LOCAL);
        assertThat(BatchIntakeService.parseSource("  ")).isEqualTo(BatchSource.LOCAL);
        assertThat(BatchIntakeService.parseSource("google_drive")).isEqualTo(BatchSource.GOOGLE_DRIVE);
        assertThat(BatchIntakeService.parseSource(" ONEDRIVE ")).isEqualTo(BatchSource.ONEDRIVE);
        assertThat(BatchIntakeService.parseSource("dropbox")).isNull();
    }

    @Test
    void aFileMustMatchItsBatchSource() {
        assertThat(BatchIntakeService.sourceMatches(BatchSource.LOCAL, null)).isTrue();
        assertThat(BatchIntakeService.sourceMatches(BatchSource.GOOGLE_DRIVE, "")).isTrue();
        assertThat(BatchIntakeService.sourceMatches(BatchSource.GOOGLE_DRIVE, "GOOGLE_DRIVE")).isTrue();
        assertThat(BatchIntakeService.sourceMatches(BatchSource.LOCAL, "GOOGLE_DRIVE")).isFalse();
        assertThat(BatchIntakeService.sourceMatches(BatchSource.ONEDRIVE, "LOCAL")).isFalse();
        assertThat(BatchIntakeService.sourceMatches(BatchSource.ONEDRIVE, "unknown")).isFalse();
    }
}
