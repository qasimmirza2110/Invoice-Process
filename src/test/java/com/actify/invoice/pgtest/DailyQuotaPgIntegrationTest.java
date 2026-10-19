package com.actify.invoice.pgtest;

import com.actify.invoice.exception.InvoiceExtractionException;
import com.actify.invoice.exception.InvoiceExtractionException.Kind;
import com.actify.invoice.user.Role;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Daily quota (TEST database, own fake model id): a 429 "per day" from Gemini pauses the whole queue until the
 * next midnight Pacific time WITHOUT using up the invoice's attempts; the admin status shows the pause, and the
 * legacy /extract endpoint answers 429 without calling Gemini. Everything happens in one test because the pause
 * then lasts for the rest of the (fake) model's day.
 */
class DailyQuotaPgIntegrationTest extends AbstractQueuePgTest {

    private static final String MODEL = "pgtest-daily-model-" + UUID.randomUUID().toString().substring(0, 8);

    @DynamicPropertySource
    static void ownModel(DynamicPropertyRegistry registry) {
        registry.add("gemini.model", () -> MODEL);
    }

    @Test
    void dailyQuotaPausesTheQueueAndTheLegacyEndpointUntilTheReset() throws Exception {
        drainQueue();
        actAs(createUser(Role.VENDOR));
        long first = queueOneInvoice("daily-1");
        long second = queueOneInvoice("daily-2");
        extractorThrows(new InvoiceExtractionException("Gemini free-tier rate limit or quota reached (HTTP 429). Wait a minute and try again.",
                Kind.HTTP_ERROR, 429, "RESOURCE_EXHAUSTED GenerateRequestsPerDayPerProjectPerModel-FreeTier", null, null));

        assertThat(worker.processNextJob()).isTrue();

        // The job waits for the reset and keeps all its attempts.
        assertThat(status(first)).isEqualTo("QUEUED");
        assertThat(jdbc.queryForObject("select attempt_count from extraction_jobs where invoice_id = ?", Integer.class, first)).isZero();
        assertThat(jdbc.queryForObject("select outcome from gemini_call_attempts where invoice_id = ?", String.class, first))
                .isEqualTo("DAILY_QUOTA");
        // No further call today: the other queued invoice is not started.
        assertThat(worker.processNextJob()).isFalse();
        assertThat(status(second)).isEqualTo("QUEUED");
        assertThat(jdbc.queryForObject("select count(*) from gemini_call_attempts where invoice_id = ?", Integer.class, second)).isZero();

        actAs(testAdmin());
        JsonNode status = json(restTemplate.getForObject("/api/admin/queue/status", String.class));
        assertThat(status.at("/quota/model").asText()).isEqualTo(MODEL);
        assertThat(status.at("/quota/pausedUntil").isNull()).isFalse();
        assertThat(status.at("/quota/requestsSinceDailyReset").asInt()).isEqualTo(1);

        // The legacy endpoint shares the same guard: no call, 429, upload marked FAILED.
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(syntheticPdf("legacy-daily")) {
            @Override
            public String getFilename() {
                return "legacy.pdf";
            }
        });
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        int before = jdbc.queryForObject("select count(*) from gemini_call_attempts where model_id = ?", Integer.class, MODEL);
        ResponseEntity<String> legacy = restTemplate.postForEntity("/api/invoices/extract", new HttpEntity<>(body, headers), String.class);
        assertThat(legacy.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(json(legacy.getBody()).get("message").asText()).contains("Daily Gemini quota");
        assertThat(jdbc.queryForObject("select count(*) from gemini_call_attempts where model_id = ?", Integer.class, MODEL))
                .isEqualTo(before);

        // Leave nothing QUEUED behind for other test classes (they use another model, so this pause does not affect them).
        jdbc.update("update extraction_jobs set next_attempt_at = next_attempt_at - interval '3 days' where invoice_id in (?, ?)",
                first, second);
    }
}
