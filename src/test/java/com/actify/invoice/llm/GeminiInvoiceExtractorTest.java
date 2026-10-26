package com.actify.invoice.llm;

import com.actify.invoice.config.GeminiConfig;
import com.actify.invoice.dto.InvoiceExtractionResult;
import com.actify.invoice.dto.LlmExtractionResponse;
import com.actify.invoice.dto.TokenUsage;
import com.actify.invoice.exception.InvoiceExtractionException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * MockRestServiceServer intercepts RestTemplate calls, so these tests never hit the real
 * Gemini API (no quota used, no network needed) but still check the real request/response JSON.
 */
class GeminiInvoiceExtractorTest {

    private static final String URL =
            "https://gemini.test/v1beta/models/gemini-3.5-flash-lite:generateContent";
    private static final byte[] PDF = "%PDF-1.4 fake".getBytes(StandardCharsets.US_ASCII);

    private MockRestServiceServer server;
    private GeminiInvoiceExtractor extractor;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        GeminiConfig config = new GeminiConfig("test-key", "gemini-3.5-flash-lite", "https://gemini.test/v1beta");
        extractor = new GeminiInvoiceExtractor(restTemplate, config, new ObjectMapper());
    }

    @Test
    void sendsPdfAndParsesStructuredResponse() {
        // The model's JSON arrives as an escaped string inside the Gemini envelope.
        String geminiResponse = "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":"
                + "\"{\\\"invoiceNumber\\\":\\\"INV-1001\\\",\\\"invoiceDate\\\":\\\"2026-10-06\\\","
                + "\\\"currency\\\":\\\"INR\\\",\\\"subtotal\\\":10000.00,\\\"taxAmount\\\":1800.00,"
                + "\\\"totalAmount\\\":11800.00,\\\"items\\\":[{\\\"description\\\":\\\"Laptop Stand\\\","
                + "\\\"quantity\\\":2,\\\"unitPrice\\\":5000.00,\\\"taxRate\\\":18,\\\"lineTotal\\\":10000.00}]}\""
                + "}]},\"finishReason\":\"STOP\"}]}";

        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("x-goog-api-key", "test-key"))
                .andExpect(jsonPath("$.contents[0].parts[1].inlineData.mimeType").value("application/pdf"))
                .andExpect(jsonPath("$.contents[0].parts[1].inlineData.data").value("JVBERi0xLjQgZmFrZQ=="))
                .andExpect(jsonPath("$.generationConfig.responseFormat.text.mimeType").value("APPLICATION_JSON"))
                .andExpect(jsonPath("$.generationConfig.responseFormat.text.schema.properties.items").exists())
                .andRespond(withSuccess(geminiResponse, MediaType.APPLICATION_JSON));

        InvoiceExtractionResult result = extractor.extract(PDF).getInvoice();

        server.verify();
        assertThat(result.getInvoiceNumber()).isEqualTo("INV-1001");
        assertThat(result.getTotalAmount()).isEqualByComparingTo(new BigDecimal("11800.00"));
        assertThat(result.getItems()).hasSize(1);
        assertThat(result.getItems().get(0).getDescription()).isEqualTo("Laptop Stand");
        assertThat(result.getRawResponse()).contains("INV-1001");
    }

    @Test
    void providerFailureBecomesExtractionException() {
        server.expect(requestTo(URL))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"code\":500,\"message\":\"Internal error\"}}"));

        assertThatThrownBy(() -> extractor.extract(PDF))
                .isInstanceOf(InvoiceExtractionException.class)
                .hasMessageContaining("HTTP 500")
                .hasMessageNotContaining("Internal error")   // provider text stays in the server log
                .hasMessageNotContaining("test-key");
    }

    @Test
    void rateLimitGivesClearMessage() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThatThrownBy(() -> extractor.extract(PDF))
                .isInstanceOf(InvoiceExtractionException.class)
                .hasMessageContaining("429");
    }

    /** Phase 3: the retry decision needs the status and the quota id, never the provider's free text. */
    @Test
    void dailyQuota429CarriesStatusAndQuotaIdButNoProviderText() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":{\"code\":429,\"status\":\"RESOURCE_EXHAUSTED\",\"message\":\"You exceeded your quota. Secret detail.\","
                        + "\"details\":[{\"@type\":\"type.googleapis.com/google.rpc.QuotaFailure\",\"violations\":"
                        + "[{\"quotaId\":\"GenerateRequestsPerDayPerProjectPerModel-FreeTier\"}]}]}}"));

        assertThatThrownBy(() -> extractor.extract(PDF))
                .isInstanceOfSatisfying(InvoiceExtractionException.class, e -> {
                    org.assertj.core.api.Assertions.assertThat(e.getKind()).isEqualTo(InvoiceExtractionException.Kind.HTTP_ERROR);
                    org.assertj.core.api.Assertions.assertThat(e.getHttpStatus()).isEqualTo(429);
                    org.assertj.core.api.Assertions.assertThat(e.getProviderCode())
                            .contains("RESOURCE_EXHAUSTED").contains("PerDay").doesNotContain("Secret");
                    org.assertj.core.api.Assertions.assertThat(com.actify.invoice.quota.FailureClassifier.classify(e))
                            .isEqualTo(com.actify.invoice.quota.FailureClassifier.Decision.WAIT_FOR_DAILY_RESET);
                });
    }

    @Test
    void theProvidersRetryHintIsReadFromTheRetryAfterHeader() {
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.set(org.springframework.http.HttpHeaders.RETRY_AFTER, "42");
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).headers(headers));
        assertThatThrownBy(() -> extractor.extract(PDF)).isInstanceOfSatisfying(InvoiceExtractionException.class,
                e -> assertThat(e.getRetryAfterSeconds()).isEqualTo(42L));
    }

    @Test
    void theProvidersRetryHintIsReadFromRetryInfoAndRoundedUp() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":{\"code\":429,\"status\":\"RESOURCE_EXHAUSTED\",\"details\":["
                        + "{\"@type\":\"type.googleapis.com/google.rpc.QuotaFailure\",\"violations\":[{\"quotaId\":\"GenerateRequestsPerMinutePerProjectPerModel-FreeTier\"}]},"
                        + "{\"@type\":\"type.googleapis.com/google.rpc.RetryInfo\",\"retryDelay\":\"36.4s\"}]}}"));
        assertThatThrownBy(() -> extractor.extract(PDF)).isInstanceOfSatisfying(InvoiceExtractionException.class, e -> {
            assertThat(e.getRetryAfterSeconds()).isEqualTo(37L);
            // a per-MINUTE 429 is retried (not the daily wait)
            assertThat(com.actify.invoice.quota.FailureClassifier.classify(e))
                    .isEqualTo(com.actify.invoice.quota.FailureClassifier.Decision.RETRY);
        });
    }

    @Test
    void noRetryHintMeansNullNotAGuess() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).body("not json"));
        assertThatThrownBy(() -> extractor.extract(PDF)).isInstanceOfSatisfying(InvoiceExtractionException.class,
                e -> assertThat(e.getRetryAfterSeconds()).isNull());
    }

    @Test
    void permanentProviderErrorsAreNeverRetried() {
        for (HttpStatus status : new HttpStatus[]{HttpStatus.BAD_REQUEST, HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN, HttpStatus.NOT_FOUND}) {
            server.reset();
            server.expect(requestTo(URL)).andRespond(withStatus(status));
            assertThatThrownBy(() -> extractor.extract(PDF)).as(status.toString()).isInstanceOfSatisfying(InvoiceExtractionException.class,
                    e -> assertThat(com.actify.invoice.quota.FailureClassifier.classify(e))
                            .isEqualTo(com.actify.invoice.quota.FailureClassifier.Decision.FAIL));
        }
    }

    @Test
    void networkErrorsAndUnusableAnswersAreClassifiedForTheRetryDecision() {
        server.expect(requestTo(URL)).andRespond(request -> {
            throw new java.net.SocketTimeoutException("Read timed out");
        });
        assertThatThrownBy(() -> extractor.extract(PDF)).isInstanceOfSatisfying(InvoiceExtractionException.class,
                e -> org.assertj.core.api.Assertions.assertThat(e.getKind()).isEqualTo(InvoiceExtractionException.Kind.NETWORK));
    }

    @Test
    void aSafetyBlockedPromptIsReportedWithItsBlockReasonAndNeverAsData() {
        server.expect(requestTo(URL)).andRespond(withSuccess(
                "{\"promptFeedback\":{\"blockReason\":\"SAFETY\"},\"usageMetadata\":{\"promptTokenCount\":812}}",
                MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> extractor.extract(PDF)).isInstanceOfSatisfying(InvoiceExtractionException.class, e -> {
            assertThat(e.getKind()).isEqualTo(InvoiceExtractionException.Kind.BLOCKED);
            assertThat(e.getMessage()).contains("block reason: SAFETY");
            assertThat(e.getTokenUsage().getPromptTokenCount()).isEqualTo(812L); // reported, not invented
        });
    }

    @Test
    void aTruncatedEmptyAnswerNamesTheFinishReason() {
        server.expect(requestTo(URL)).andRespond(withSuccess(
                "{\"candidates\":[{\"content\":{\"parts\":[]},\"finishReason\":\"MAX_TOKENS\"}]}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> extractor.extract(PDF)).isInstanceOfSatisfying(InvoiceExtractionException.class, e -> {
            assertThat(e.getKind()).isEqualTo(InvoiceExtractionException.Kind.INVALID_RESPONSE);
            assertThat(e.getMessage()).contains("finish reason: MAX_TOKENS");
            assertThat(e.getTokenUsage().getTotalTokenCount()).isNull(); // absent metadata stays absent
        });
    }

    @Test
    void nonJsonAnswerBecomesExtractionException() {
        String geminiResponse = "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Sorry, I cannot read this.\"}]}}]}";
        server.expect(requestTo(URL)).andRespond(withSuccess(geminiResponse, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> extractor.extract(PDF))
                .isInstanceOf(InvoiceExtractionException.class)
                .hasMessageContaining("does not match the expected invoice JSON");
    }

    @Test
    void missingApiKeyFailsWithoutCallingGemini() {
        GeminiConfig noKey = new GeminiConfig("", "gemini-3.5-flash-lite", "https://gemini.test/v1beta");
        GeminiInvoiceExtractor extractorWithoutKey =
                new GeminiInvoiceExtractor(new RestTemplate(), noKey, new ObjectMapper());

        assertThatThrownBy(() -> extractorWithoutKey.extract(PDF))
                .isInstanceOf(InvoiceExtractionException.class)
                .hasMessageContaining("GEMINI_API_KEY");
    }

    // ---------------- multi-page + token usage ----------------

    /** Model answer for the synthetic 2-page invoice: Laptop Stand on page 1, USB-C Cable only on page 2. */
    private static final String TWO_PAGE_INVOICE_JSON = "{\"invoiceNumber\":\"INV-2026-0042\",\"invoiceDate\":\"2026-10-06\","
            + "\"currency\":\"INR\",\"subtotal\":10500.00,\"taxAmount\":1890.00,\"totalAmount\":12390.00,\"items\":["
            + "{\"description\":\"Laptop Stand\",\"quantity\":2,\"unitPrice\":5000.00,\"taxRate\":18,\"lineTotal\":10000.00},"
            + "{\"description\":\"USB-C Cable\",\"quantity\":1,\"unitPrice\":500.00,\"taxRate\":18,\"lineTotal\":500.00}]}";

    /** Builds a Gemini response envelope; extraTopLevelJson is e.g. "\"usageMetadata\":{...}". */
    private static String envelope(String modelAnswer, String extraTopLevelJson) throws Exception {
        String quotedAnswer = new ObjectMapper().writeValueAsString(modelAnswer);
        String extra = extraTopLevelJson == null ? "" : "," + extraTopLevelJson;
        return "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":" + quotedAnswer + "}]},"
                + "\"finishReason\":\"STOP\"}]" + extra + "}";
    }

    @Test
    void readsInvoiceAndUsageMetadataFromTheSameResponse() throws Exception {
        String response = envelope(TWO_PAGE_INVOICE_JSON,
                "\"usageMetadata\":{\"promptTokenCount\":1290,\"candidatesTokenCount\":180,"
                        + "\"thoughtsTokenCount\":35,\"totalTokenCount\":1505,"
                        + "\"promptTokensDetails\":[{\"modality\":\"IMAGE\",\"tokenCount\":516}]},"
                        + "\"modelVersion\":\"gemini-3.5-flash-lite-test-version\",\"responseId\":\"abc\"");

        // Exactly ONE request is expected - there is no separate countTokens call.
        server.expect(requestTo(URL))
                .andExpect(jsonPath("$.contents[0].parts.length()").value(2))
                .andExpect(jsonPath("$.contents[0].parts[0].text").value(containsString("Inspect every page")))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));

        LlmExtractionResponse result = extractor.extract(PDF);

        server.verify();
        assertThat(result.getInvoice().getItems()).extracting("description")
                .containsExactly("Laptop Stand", "USB-C Cable");
        TokenUsage usage = result.getTokenUsage();
        assertThat(usage.getModelId()).isEqualTo("gemini-3.5-flash-lite");
        assertThat(usage.getModelVersion()).isEqualTo("gemini-3.5-flash-lite-test-version");
        assertThat(usage.getPromptTokenCount()).isEqualTo(1290L);
        assertThat(usage.getCandidatesTokenCount()).isEqualTo(180L);
        assertThat(usage.getThoughtsTokenCount()).isEqualTo(35L);
        assertThat(usage.getTotalTokenCount()).isEqualTo(1505L);
        assertThat(usage.getExtractionDurationMs()).isNull();   // measured by the service, not here
    }

    @Test
    void absentOptionalCountsStayNullAndTotalIsNotRecomputed() throws Exception {
        // No thoughtsTokenCount; total deliberately != prompt + candidates to prove we keep Gemini's own number.
        String response = envelope(TWO_PAGE_INVOICE_JSON,
                "\"usageMetadata\":{\"promptTokenCount\":1000,\"candidatesTokenCount\":200,\"totalTokenCount\":1234}");
        server.expect(requestTo(URL)).andRespond(withSuccess(response, MediaType.APPLICATION_JSON));

        TokenUsage usage = extractor.extract(PDF).getTokenUsage();

        assertThat(usage.getThoughtsTokenCount()).isNull();
        assertThat(usage.getModelVersion()).isNull();
        assertThat(usage.getTotalTokenCount()).isEqualTo(1234L);
    }

    @Test
    void missingUsageMetadataDoesNotFailAValidExtraction() throws Exception {
        server.expect(requestTo(URL))
                .andRespond(withSuccess(envelope(TWO_PAGE_INVOICE_JSON, null), MediaType.APPLICATION_JSON));

        LlmExtractionResponse result = extractor.extract(PDF);

        assertThat(result.getInvoice().getItems()).hasSize(2);
        TokenUsage usage = result.getTokenUsage();
        assertThat(usage.getModelId()).isEqualTo("gemini-3.5-flash-lite");
        assertThat(usage.getPromptTokenCount()).isNull();       // null, never an invented 0
        assertThat(usage.getCandidatesTokenCount()).isNull();
        assertThat(usage.getThoughtsTokenCount()).isNull();
        assertThat(usage.getTotalTokenCount()).isNull();
    }

    @Test
    void unusableAnswerStillReportsTheTokensThatWereUsed() throws Exception {
        String response = envelope("Sorry, I cannot read this.",
                "\"usageMetadata\":{\"promptTokenCount\":900,\"candidatesTokenCount\":8,\"totalTokenCount\":908}");
        server.expect(requestTo(URL)).andRespond(withSuccess(response, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> extractor.extract(PDF))
                .isInstanceOf(InvoiceExtractionException.class)
                .hasMessageContaining("does not match the expected invoice JSON")
                .satisfies(e -> {
                    TokenUsage usage = ((InvoiceExtractionException) e).getTokenUsage();
                    assertThat(usage.getPromptTokenCount()).isEqualTo(900L);
                    assertThat(usage.getTotalTokenCount()).isEqualTo(908L);
                });
    }

    @Test
    void httpErrorHasNoTokenUsage() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> extractor.extract(PDF))
                .isInstanceOf(InvoiceExtractionException.class)
                .satisfies(e -> assertThat(((InvoiceExtractionException) e).getTokenUsage()).isNull());
    }
}
