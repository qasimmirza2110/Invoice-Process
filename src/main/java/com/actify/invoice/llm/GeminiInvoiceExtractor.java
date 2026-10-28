package com.actify.invoice.llm;

import com.actify.invoice.config.GeminiConfig;
import com.actify.invoice.dto.InvoiceExtractionResult;
import com.actify.invoice.dto.LlmExtractionResponse;
import com.actify.invoice.dto.TokenUsage;
import com.actify.invoice.exception.InvoiceExtractionException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Calls the Gemini generateContent REST API with the invoice PDF.
 *
 * Keep Gemini-specific request construction here so the service layer
 * does not need to know how the external provider expects the PDF.
 *
 * Request format (from https://ai.google.dev/api/generate-content):
 *   POST {baseUrl}/models/{model}:generateContent      header: x-goog-api-key
 *   contents[0].parts = [ {text: prompt}, {inlineData: {mimeType: application/pdf, data: base64}} ]
 *   generationConfig.responseFormat.text = {mimeType: APPLICATION_JSON, schema: <JSON schema>}
 * Response: the JSON we asked for is a string in candidates[0].content.parts[].text;
 *           token usage is in usageMetadata and the model version in modelVersion.
 *
 * The whole PDF (every page) goes in one inlineData part. Gemini reads embedded text and
 * scanned/image pages itself (native PDF vision) - this app does no OCR or page splitting.
 */
@Component
public class GeminiInvoiceExtractor implements LlmInvoiceExtractor {

    private static final Logger log = LoggerFactory.getLogger(GeminiInvoiceExtractor.class);

    private static final String PROMPT_FILE = "prompts/invoice-extraction.txt";
    private static final String SCHEMA_FILE = "prompts/invoice-schema.json";

    private final RestTemplate restTemplate;
    private final GeminiConfig geminiConfig;
    private final ObjectMapper objectMapper;
    private final String prompt;
    private final JsonNode responseSchema;

    public GeminiInvoiceExtractor(RestTemplate restTemplate, GeminiConfig geminiConfig, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.geminiConfig = geminiConfig;
        this.objectMapper = objectMapper;
        // Load once at startup: a missing prompt file should fail fast, not on the first request.
        this.prompt = readClasspathFile(PROMPT_FILE);
        try {
            this.responseSchema = objectMapper.readTree(readClasspathFile(SCHEMA_FILE));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invalid JSON schema in " + SCHEMA_FILE, e);
        }
    }

    @Override
    public LlmExtractionResponse extract(byte[] pdfBytes) {
        if (!StringUtils.hasText(geminiConfig.getApiKey())) {
            throw new InvoiceExtractionException(
                    "Gemini API key is not configured. Set the GEMINI_API_KEY environment variable.",
                    InvoiceExtractionException.Kind.CONFIGURATION, null, null, null, null);
        }
        // Backstop for every caller (worker and legacy endpoint): nothing leaves this server before the data policy
        // allows it (GeminiConfig).
        if (!geminiConfig.isDocumentSendingAllowed()) {
            throw new InvoiceExtractionException(GeminiConfig.PAID_TIER_BLOCKED_REASON,
                    InvoiceExtractionException.Kind.CONFIGURATION, null, null, null, null);
        }

        String url = geminiConfig.getBaseUrl() + "/models/" + geminiConfig.getModel() + ":generateContent";

        // Key goes in a header, not in the URL, so it can't leak through URL/access logs.
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-goog-api-key", geminiConfig.getApiKey());
        HttpEntity<String> request = new HttpEntity<>(buildRequestBody(pdfBytes), headers);

        String responseBody;
        try {
            log.info("Sending invoice PDF ({} bytes) to Gemini model {}", pdfBytes.length, geminiConfig.getModel());
            responseBody = restTemplate.postForObject(url, request, String.class);
        } catch (HttpStatusCodeException e) {
            throw new InvoiceExtractionException(describeProviderError(e), InvoiceExtractionException.Kind.HTTP_ERROR, e.getRawStatusCode(),
                    readProviderCode(e), null, readRetryAfterSeconds(e), e);
        } catch (RestClientException e) {
            // Timeouts, DNS problems, connection refused... Details stay in the server log.
            log.warn("Could not reach Gemini: {}", e.getMessage());
            throw new InvoiceExtractionException("Could not reach Gemini (network error or timeout)", InvoiceExtractionException.Kind.NETWORK, null, null,
                    null, e);
        }

        JsonNode root = readResponseEnvelope(responseBody);

        // Read the usage first: Gemini has already counted these tokens, even if the
        // answer below turns out to be unusable.
        TokenUsage tokenUsage = readTokenUsage(root);
        try {
            String invoiceJson = extractModelText(root);
            return new LlmExtractionResponse(parseInvoiceJson(invoiceJson), tokenUsage);
        } catch (InvoiceExtractionException e) {
            throw new InvoiceExtractionException(e.getMessage(), e.getKind(), null, null, tokenUsage, e);
        }
    }

    /**
     * Token usage from the SAME generateContent response (usageMetadata + modelVersion).
     * We do not call countTokens separately - that would be a second request and would only
     * count the input. A missing field stays null; we never add counts up ourselves.
     */
    private TokenUsage readTokenUsage(JsonNode root) {
        JsonNode usage = root.path("usageMetadata");
        TokenUsage tokenUsage = new TokenUsage();
        tokenUsage.setModelId(geminiConfig.getModel());
        tokenUsage.setModelVersion(root.path("modelVersion").isTextual() ? root.path("modelVersion").asText() : null);
        tokenUsage.setPromptTokenCount(longOrNull(usage, "promptTokenCount"));
        tokenUsage.setCandidatesTokenCount(longOrNull(usage, "candidatesTokenCount"));
        tokenUsage.setThoughtsTokenCount(longOrNull(usage, "thoughtsTokenCount"));
        tokenUsage.setTotalTokenCount(longOrNull(usage, "totalTokenCount"));
        return tokenUsage;
    }

    private static Long longOrNull(JsonNode parent, String field) {
        JsonNode value = parent.path(field);
        return value.isIntegralNumber() ? value.asLong() : null;
    }

    private String buildRequestBody(byte[] pdfBytes) {
        ObjectNode body = objectMapper.createObjectNode();

        ArrayNode parts = body.putArray("contents").addObject().put("role", "user").putArray("parts");
        parts.addObject().put("text", prompt);
        ObjectNode inlineData = parts.addObject().putObject("inlineData");
        inlineData.put("mimeType", "application/pdf");
        inlineData.put("data", Base64.getEncoder().encodeToString(pdfBytes));

        // Structured output: Gemini must answer with JSON matching our schema.
        // This guarantees the SHAPE of the answer, not that the values are correct.
        ObjectNode textFormat = body.putObject("generationConfig")
                .putObject("responseFormat")
                .putObject("text");
        textFormat.put("mimeType", "APPLICATION_JSON");
        textFormat.set("schema", responseSchema);

        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new InvoiceExtractionException("Could not build Gemini request", InvoiceExtractionException.Kind.CONFIGURATION, null, null, null, e);
        }
    }

    private JsonNode readResponseEnvelope(String responseBody) {
        try {
            return objectMapper.readTree(responseBody == null ? "" : responseBody);
        } catch (JsonProcessingException e) {
            throw new InvoiceExtractionException("Gemini returned a response that is not valid JSON", InvoiceExtractionException.Kind.INVALID_RESPONSE, null,
                    null, null, e);
        }
    }

    /** Pulls the model's text answer out of the Gemini response envelope. */
    private String extractModelText(JsonNode root) {
        JsonNode candidate = root.path("candidates").path(0);
        if (candidate.isMissingNode()) {
            // No candidate usually means the prompt was blocked by a safety filter.
            String blockReason = root.path("promptFeedback").path("blockReason").asText("unknown");
            throw new InvoiceExtractionException("Gemini returned no result (block reason: " + blockReason + ")",
                    InvoiceExtractionException.Kind.BLOCKED, null, null, null, null);
        }

        StringBuilder text = new StringBuilder();
        for (JsonNode part : candidate.path("content").path("parts")) {
            // Skip "thinking" parts - only the final answer contains our JSON.
            if (part.path("thought").asBoolean(false)) {
                continue;
            }
            text.append(part.path("text").asText(""));
        }

        if (text.length() == 0) {
            String finishReason = candidate.path("finishReason").asText("unknown");
            throw new InvoiceExtractionException("Gemini returned an empty answer (finish reason: " + finishReason + ")",
                    InvoiceExtractionException.Kind.INVALID_RESPONSE, null, null, null, null);
        }
        return text.toString().trim();
    }

    private InvoiceExtractionResult parseInvoiceJson(String invoiceJson) {
        try {
            InvoiceExtractionResult result = objectMapper.readValue(invoiceJson, InvoiceExtractionResult.class);
            result.setRawResponse(invoiceJson);
            return result;
        } catch (JsonProcessingException e) {
            // Don't log the JSON itself: it contains invoice (financial) data.
            throw new InvoiceExtractionException("Gemini answer does not match the expected invoice JSON format",
                    InvoiceExtractionException.Kind.INVALID_RESPONSE, null, null, null, e);
        }
    }

    /**
     * Builds a safe client message from a Gemini HTTP error. The provider's own error text
     * is logged for us to debug, but never passed on to the API client.
     */
    private String describeProviderError(HttpStatusCodeException e) {
        int status = e.getRawStatusCode();

        String providerMessage = "";
        try {
            providerMessage = objectMapper.readTree(e.getResponseBodyAsString()).path("error").path("message").asText("");
        } catch (JsonProcessingException ignored) {
            // Body was not JSON - nothing extra to log.
        }
        if (providerMessage.length() > 300) {
            providerMessage = providerMessage.substring(0, 300) + "...";
        }
        log.warn("Gemini returned HTTP {}: {}", status, providerMessage);

        if (status == 429) {
            return "Gemini free-tier rate limit or quota reached (HTTP 429). Wait a minute and try again.";
        }
        if (status == 401 || status == 403) {
            return "Gemini rejected the API key or the project has no access (HTTP " + status + ")";
        }
        if (status == 400) {
            return "Gemini rejected the request (HTTP 400). See server log for details.";
        }
        return "Gemini request failed with HTTP " + status;
    }

    /**
     * A short, safe code for the retry decision: the provider's error status (e.g. RESOURCE_EXHAUSTED) plus, when
     * present, the quota id of a QuotaFailure detail (e.g. one containing "PerDay" for the daily limit).
     * Never the provider's free-text message, never invoice content.
     */
    String readProviderCode(HttpStatusCodeException e) {
        try {
            JsonNode error = objectMapper.readTree(e.getResponseBodyAsString()).path("error");
            StringBuilder code = new StringBuilder(error.path("status").asText(""));
            for (JsonNode detail : error.path("details")) {
                for (JsonNode violation : detail.path("violations")) {
                    String quotaId = violation.path("quotaId").asText("");
                    if (!quotaId.isEmpty()) {
                        code.append(code.length() > 0 ? " " : "").append(quotaId);
                    }
                }
                String reason = detail.path("reason").asText("");
                if (!reason.isEmpty()) {
                    code.append(code.length() > 0 ? " " : "").append(reason);
                }
            }
            String result = code.toString().replaceAll("[^A-Za-z0-9_ .-]", "");
            return result.isEmpty() ? null : (result.length() > 200 ? result.substring(0, 200) : result);
        } catch (JsonProcessingException | RuntimeException ignored) {
            return null; // body was not JSON - classification then relies on the HTTP status alone
        }
    }

    /**
     * How long the provider asked us to wait before the next call: the standard Retry-After header (whole seconds),
     * otherwise a google.rpc.RetryInfo detail in the error body ("retryDelay": "37s" / "1.5s"), rounded up.
     * null when neither is present or readable. Bounded to one day so a strange value cannot park a job forever.
     */
    Long readRetryAfterSeconds(HttpStatusCodeException e) {
        Long seconds = null;
        String header = e.getResponseHeaders() == null ? null : e.getResponseHeaders().getFirst(HttpHeaders.RETRY_AFTER);
        if (header != null && header.trim().matches("\\d{1,9}")) {
            seconds = Long.valueOf(header.trim());
        } else {
            try {
                for (JsonNode detail : objectMapper.readTree(e.getResponseBodyAsString()).path("error").path("details")) {
                    String delay = detail.path("retryDelay").asText("");
                    if (detail.path("@type").asText("").endsWith("google.rpc.RetryInfo") && delay.matches("\\d{1,9}(\\.\\d+)?s")) {
                        seconds = (long) Math.ceil(Double.parseDouble(delay.substring(0, delay.length() - 1)));
                    }
                }
            } catch (JsonProcessingException | RuntimeException ignored) {
                // no usable hint - the worker's own back-off applies
            }
        }
        return seconds == null ? null : Math.min(seconds, 24L * 60 * 60);
    }

    private static String readClasspathFile(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return StreamUtils.copyToString(in, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read classpath file " + path, e);
        }
    }
}
