package com.actify.invoice.queue;

import com.actify.invoice.config.GeminiConfig;
import com.actify.invoice.exception.InvoiceExtractionException;
import com.actify.invoice.llm.GeminiInvoiceExtractor;
import com.actify.invoice.llm.LlmInvoiceExtractor;
import com.actify.invoice.quota.GeminiQuotaGuard;
import com.actify.invoice.quota.QuotaSettings;
import com.actify.invoice.quota.QuotaStatus;
import com.actify.invoice.storage.InvoiceDocumentStorage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Real invoices go only to a paid Gemini project: until the owner confirms it, the worker never claims a job (the
 * invoice stays QUEUED, not FAILED), the admin sees why, and the extractor itself refuses to send anything.
 */
class GeminiDataPolicyTest {

    private final ExtractionJobService jobs = mock(ExtractionJobService.class);
    private final GeminiQuotaGuard quotaGuard = mock(GeminiQuotaGuard.class);
    private final LlmInvoiceExtractor extractor = mock(LlmInvoiceExtractor.class);
    private final QuotaSettings limits = new QuotaSettings("10", "100000", "100", "3000", 0.8);

    private ExtractionWorker worker(GeminiConfig config) {
        return new ExtractionWorker(jobs, mock(ExtractionOutcomeService.class), mock(InvoiceDocumentStorage.class),
                extractor, quotaGuard, limits, config, true);
    }

    private static GeminiConfig config(boolean required, boolean confirmed) {
        return new GeminiConfig("test-key", "test-model", "http://127.0.0.1:9", required, confirmed);
    }

    @Test
    void withoutTheConfirmationTheWorkerStaysIdleAndTouchesNoJob() {
        ExtractionWorker w = worker(config(true, false));
        assertThat(w.isRunning()).isFalse();
        w.poll();
        verifyNoInteractions(jobs, extractor, quotaGuard);

        QuotaStatus quota = mock(QuotaStatus.class);
        when(quota.isConfigured()).thenReturn(true);
        WorkerStatusService status = new WorkerStatusService(w, jobs, quotaGuard);
        assertThat(status.isWorkerRunning(quota)).isFalse();
        assertThat(status.idleReason(quota)).isEqualTo(GeminiConfig.PAID_TIER_BLOCKED_REASON)
                .contains("INVOICE_GEMINI_PAID_TIER_CONFIRMED");
    }

    @Test
    void theConfirmationOrANonRequiringProfileLetsTheWorkerRun() {
        assertThat(worker(config(true, true)).isRunning()).isTrue();
        assertThat(worker(config(false, false)).isRunning()).isTrue();
    }

    @Test
    void theExtractorRefusesToSendAnythingWithoutTheConfirmation() {
        RestTemplate rest = mock(RestTemplate.class);
        GeminiInvoiceExtractor gemini = new GeminiInvoiceExtractor(rest, config(true, false), new ObjectMapper());
        assertThatThrownBy(() -> gemini.extract(new byte[]{1, 2, 3}))
                .isInstanceOf(InvoiceExtractionException.class)
                .hasMessage(GeminiConfig.PAID_TIER_BLOCKED_REASON);
        verifyNoInteractions(rest);
    }
}
