package com.actify.invoice.pgtest;

import com.actify.invoice.config.GeminiConfig;
import com.actify.invoice.dto.TokenUsage;
import com.actify.invoice.entity.GeminiCallAttempt;
import com.actify.invoice.entity.Invoice;
import com.actify.invoice.entity.InvoiceStatus;
import com.actify.invoice.exception.InvoiceExtractionException;
import com.actify.invoice.exception.InvoiceExtractionException.Kind;
import com.actify.invoice.quota.GeminiQuotaGuard;
import com.actify.invoice.quota.QuotaSettings;
import com.actify.invoice.repository.GeminiCallAttemptRepository;
import com.actify.invoice.repository.InvoiceRepository;
import com.actify.invoice.user.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 3 against the TEST database: every Gemini call is recorded; transient errors are retried with back-off and
 * stop after max attempts; permanent errors fail at once; the minute/day budgets are enforced from the attempts table.
 * This class uses its own (fake) model id, so its quota windows never mix with other tests' attempts.
 */
class QuotaPgIntegrationTest extends AbstractQueuePgTest {

    private static final String MODEL = "pgtest-quota-model-" + UUID.randomUUID().toString().substring(0, 8);

    @DynamicPropertySource
    static void ownModel(DynamicPropertyRegistry registry) {
        registry.add("gemini.model", () -> MODEL);
    }

    @Autowired private GeminiCallAttemptRepository attempts;
    @Autowired private InvoiceRepository invoiceRepository;
    @Autowired private TransactionTemplate transactionTemplate;
    @PersistenceContext private EntityManager entityManager;

    @BeforeEach
    void emptyQueueAsVendor() {
        drainQueue();
        actAs(createUser(Role.VENDOR));
    }

    @AfterEach
    void leaveTheQueueEmpty() {
        drainQueue();
    }

    @Test
    void aSuccessfulCallIsRecordedWithTheTokensGeminiReported() throws Exception {
        long id = queueOneInvoice("tokens");
        TokenUsage usage = new TokenUsage();
        usage.setModelId(MODEL);
        usage.setModelVersion(MODEL + "-001");
        usage.setPromptTokenCount(1234L);
        usage.setCandidatesTokenCount(321L);
        usage.setTotalTokenCount(1555L);
        extractorReturns(validResult(unique("TOK")), usage);

        assertThat(worker.processNextJob()).isTrue();

        Map<String, Object> row = jdbc.queryForMap("select caller, outcome, http_status, model_id, model_version, "
                + "prompt_token_count, total_token_count, estimated_input_tokens, finished_at is not null as finished "
                + "from gemini_call_attempts where invoice_id = ?", id);
        assertThat(row.get("caller")).isEqualTo("WORKER");
        assertThat(row.get("outcome")).isEqualTo("SUCCESS");
        assertThat(row.get("http_status")).isEqualTo(200);
        assertThat(row.get("model_id")).isEqualTo(MODEL);
        assertThat(row.get("model_version")).isEqualTo(MODEL + "-001");
        assertThat(((Number) row.get("prompt_token_count")).longValue()).isEqualTo(1234L);
        assertThat(((Number) row.get("estimated_input_tokens")).intValue()).isEqualTo(1000); // configured initial estimate
        assertThat(row.get("finished")).isEqualTo(true);
    }

    @Test
    void transientErrorsAreRetriedWithBackoffAndFailAfterMaxAttempts() throws Exception {
        long id = queueOneInvoice("retry");
        extractorThrows(new InvoiceExtractionException("Gemini request failed with HTTP 503", Kind.HTTP_ERROR, 503,
                "UNAVAILABLE", null, null));

        assertThat(worker.processNextJob()).isTrue();
        assertThat(status(id)).isEqualTo("QUEUED");
        assertThat(jdbc.queryForObject("select extract(epoch from (next_attempt_at - now() at time zone 'UTC')) "
                + "from extraction_jobs where invoice_id = ?", Double.class, id)).isBetween(15.0, 40.0); // ~30 s +- 20 %
        assertThat(worker.processNextJob()).as("not eligible before its back-off time").isFalse();

        for (int attempt = 2; attempt <= 3; attempt++) {
            makeEligibleNow(id);
            assertThat(worker.processNextJob()).isTrue();
        }

        assertThat(status(id)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("select attempt_count from extraction_jobs where invoice_id = ?", Integer.class, id)).isEqualTo(3);
        assertThat(jdbc.queryForList("select outcome from gemini_call_attempts where invoice_id = ? order by attempt_number",
                String.class, id)).containsExactly("RETRYABLE_ERROR", "RETRYABLE_ERROR", "RETRYABLE_ERROR");
        assertThat(jdbc.queryForObject("select failure_reason from invoices where id = ?", String.class, id))
                .contains("gave up after 3 attempts");
    }

    @Test
    void permanentErrorsFailAtOnceWithoutRetry() throws Exception {
        long id = queueOneInvoice("permanent");
        extractorThrows(new InvoiceExtractionException("Gemini rejected the API key or the project has no access (HTTP 403)",
                Kind.HTTP_ERROR, 403, "PERMISSION_DENIED", null, null));

        worker.processNextJob();

        assertThat(status(id)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("select attempt_count from extraction_jobs where invoice_id = ?", Integer.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select last_error_code from extraction_jobs where invoice_id = ?", String.class, id))
                .isEqualTo("HTTP_403");
    }

    /** The minute and day budgets, checked with a guard that has small limits and its own model id. */
    @Test
    void minuteAndDayBudgetsAreEnforcedFromTheAttemptsTable() {
        Invoice invoice = transactionTemplate.execute(s -> {
            Invoice i = new Invoice();
            i.setStatus(InvoiceStatus.FAILED);
            i.setFailureReason("synthetic quota-budget test row");
            return invoiceRepository.save(i);
        });

        GeminiQuotaGuard rpm = smallGuard("2", "1000000", "1000", "100");
        assertThat(reserve(rpm, invoice)).isNull();
        assertThat(reserve(rpm, invoice)).isNull();
        assertThat(reserve(rpm, invoice)).contains("Requests-per-minute");

        GeminiQuotaGuard tpm = smallGuard("100", "1500", "1000", "1000");
        assertThat(reserve(tpm, invoice)).isNull();               // 1000 estimated tokens
        assertThat(reserve(tpm, invoice)).contains("tokens-per-minute");

        GeminiQuotaGuard rpd = smallGuard("100", "1000000", "2", "10");
        assertThat(reserve(rpd, invoice)).isNull();
        assertThat(reserve(rpd, invoice)).isNull();
        assertThat(reserve(rpd, invoice)).contains("Daily Gemini quota");
    }

    // ------------------------------------------------------------------ helpers

    private void makeEligibleNow(long invoiceId) {
        jdbc.update("update extraction_jobs set next_attempt_at = next_attempt_at - interval '1 hour' where invoice_id = ?",
                invoiceId);
    }

    private GeminiQuotaGuard smallGuard(String rpm, String tpm, String rpd, String estimate) {
        String model = "pgtest-small-guard-" + UUID.randomUUID().toString().substring(0, 8);
        GeminiQuotaGuard guard = new GeminiQuotaGuard(new QuotaSettings(rpm, tpm, rpd, estimate, 1.0), attempts,
                new GeminiConfig("not-used", model, "http://127.0.0.1:9"), Clock.systemDefaultZone());
        ReflectionTestUtils.setField(guard, "entityManager", entityManager);
        return guard;
    }

    /** @return null when the call was allowed (and recorded), otherwise the refusal reason */
    private String reserve(GeminiQuotaGuard guard, Invoice invoice) {
        return transactionTemplate.execute(s -> {
            GeminiQuotaGuard.Check check = guard.check(true);
            if (!check.isAllowed()) {
                return check.getReason();
            }
            GeminiCallAttempt attempt = guard.recordStart(entityManager.getReference(Invoice.class, invoice.getId()),
                    GeminiCallAttempt.Caller.WORKER, check.getEstimatedInputTokens());
            attempt.complete(GeminiCallAttempt.Outcome.SUCCESS, 200, null, null, 5, LocalDateTime.now());
            return null;
        });
    }
}
