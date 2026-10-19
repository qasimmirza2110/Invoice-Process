package com.actify.invoice.intake;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Folder uploads: only PDFs directly inside the chosen folder are accepted (webkitRelativePath = "Folder/file.pdf"). */
class FolderPolicyTest {

    @Test
    void topLevelFilesAreAcceptedAndSubFolderFilesAreNot() {
        assertThat(BatchIntakeService.isInSubFolder(null)).isFalse();
        assertThat(BatchIntakeService.isInSubFolder("")).isFalse();
        assertThat(BatchIntakeService.isInSubFolder("invoice.pdf")).isFalse();
        assertThat(BatchIntakeService.isInSubFolder("October/invoice.pdf")).isFalse();
        assertThat(BatchIntakeService.isInSubFolder("/October/invoice.pdf")).isFalse();
        assertThat(BatchIntakeService.isInSubFolder("October/archive/invoice.pdf")).isTrue();
        assertThat(BatchIntakeService.isInSubFolder("October\\archive\\invoice.pdf")).isTrue();
    }
}
