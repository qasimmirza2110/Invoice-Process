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
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.web.client.RestTemplate;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * One provider, one mode: Gemini through a free-tier key. Until the operator confirms the free-tier queue the worker
 * never claims a job (the invoice stays QUEUED, not FAILED), the admin sees why, and the extractor itself refuses to send
 * anything. There is no paid mode, no second LLM implementation and no other route to Gemini.
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

    private static GeminiConfig config(boolean confirmed) {
        return new GeminiConfig("test-key", "gemini-3.5-flash-lite", "http://127.0.0.1:9", confirmed);
    }

    @Test
    void withoutTheConfirmationTheWorkerStaysIdleAndTouchesNoJob() {
        ExtractionWorker w = worker(config(false));
        assertThat(w.isRunning()).isFalse();
        w.poll();
        verifyNoInteractions(jobs, extractor, quotaGuard);

        QuotaStatus quota = mock(QuotaStatus.class);
        when(quota.isConfigured()).thenReturn(true);
        WorkerStatusService status = new WorkerStatusService(w, jobs, quotaGuard);
        assertThat(status.isWorkerRunning(quota)).isFalse();
        assertThat(status.idleReason(quota)).isEqualTo(GeminiConfig.NOT_CONFIRMED_REASON)
                .contains("INVOICE_GEMINI_FREE_TIER_AUTO_QUEUE_CONFIRMED").contains("billing")
                .doesNotContain("test-key");
        assertThat(status.workerState(quota, 0)).isEqualTo(WorkerStatusService.WorkerState.MODE_NOT_CONFIRMED);
        assertThat(status.geminiMode()).isEqualTo(GeminiConfig.Mode.NOT_CONFIRMED);
    }

    @Test
    void theConfirmationLetsTheWorkerRunAndIsReportedAsTheFreeTierQueue() {
        ExtractionWorker w = worker(config(true));
        assertThat(w.isRunning()).isTrue();
        assertThat(new WorkerStatusService(w, jobs, quotaGuard).geminiMode()).isEqualTo(GeminiConfig.Mode.FREE_TIER_AUTO_QUEUE);
    }

    @Test
    void paidModeDoesNotExistInTheRuntimeConfiguration() {
        assertThat(Arrays.stream(GeminiConfig.Mode.values()).map(Enum::name).collect(Collectors.toList()))
                .containsExactly("NOT_CONFIRMED", "FREE_TIER_AUTO_QUEUE");
        assertThat(Arrays.stream(GeminiConfig.class.getDeclaredMethods()).map(m -> m.getName().toLowerCase()))
                .noneMatch(name -> name.contains("paid"));
        assertThat(Arrays.stream(GeminiConfig.class.getDeclaredFields()).map(f -> f.getName().toLowerCase()))
                .noneMatch(name -> name.contains("paid") || name.contains("fallback") || name.contains("allowlist"));
    }

    @Test
    void geminiIsTheOnlyLlmImplementationOnTheClasspath() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(LlmInvoiceExtractor.class));
        Set<String> implementations = scanner.findCandidateComponents("com.actify.invoice").stream()
                .map(BeanDefinition::getBeanClassName).collect(Collectors.toSet());
        assertThat(implementations).containsExactly(GeminiInvoiceExtractor.class.getName());
    }

    @Test
    void theExtractorRefusesToSendAnythingWithoutTheConfirmation() {
        RestTemplate rest = mock(RestTemplate.class);
        GeminiInvoiceExtractor gemini = new GeminiInvoiceExtractor(rest, config(false), new ObjectMapper());
        assertThatThrownBy(() -> gemini.extract(new byte[]{1, 2, 3}))
                .isInstanceOf(InvoiceExtractionException.class)
                .hasMessage(GeminiConfig.NOT_CONFIRMED_REASON);
        verifyNoInteractions(rest);
    }
}
