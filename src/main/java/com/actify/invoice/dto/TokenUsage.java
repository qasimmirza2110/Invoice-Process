package com.actify.invoice.dto;

/**
 * Model and token usage of ONE extraction, as reported by Gemini in the usageMetadata of the
 * same generateContent response (no extra countTokens request).
 *
 * Every field is a nullable wrapper on purpose:
 *  - Gemini may leave a count out (e.g. thoughtsTokenCount when the model did not think);
 *  - invoices stored before this feature have no metrics at all.
 * null means "not reported", never 0. We never compute or guess a count ourselves.
 */
public class TokenUsage {

    private String modelId;                 // the model we asked for (GEMINI_MODEL)
    private String modelVersion;            // the version Gemini says it actually used
    private Long promptTokenCount;          // input: prompt text + PDF pages
    private Long candidatesTokenCount;      // output: the JSON answer
    private Long thoughtsTokenCount;        // internal "thinking" tokens, if any
    private Long totalTokenCount;           // Gemini's own total for the request
    private Long extractionDurationMs;      // measured locally around the Gemini call

    public TokenUsage() {
    }

    public TokenUsage(String modelId, String modelVersion, Long promptTokenCount, Long candidatesTokenCount,
                      Long thoughtsTokenCount, Long totalTokenCount, Long extractionDurationMs) {
        this.modelId = modelId;
        this.modelVersion = modelVersion;
        this.promptTokenCount = promptTokenCount;
        this.candidatesTokenCount = candidatesTokenCount;
        this.thoughtsTokenCount = thoughtsTokenCount;
        this.totalTokenCount = totalTokenCount;
        this.extractionDurationMs = extractionDurationMs;
    }

    public String getModelId() { return modelId; }
    public void setModelId(String modelId) { this.modelId = modelId; }

    public String getModelVersion() { return modelVersion; }
    public void setModelVersion(String modelVersion) { this.modelVersion = modelVersion; }

    public Long getPromptTokenCount() { return promptTokenCount; }
    public void setPromptTokenCount(Long promptTokenCount) { this.promptTokenCount = promptTokenCount; }

    public Long getCandidatesTokenCount() { return candidatesTokenCount; }
    public void setCandidatesTokenCount(Long candidatesTokenCount) { this.candidatesTokenCount = candidatesTokenCount; }

    public Long getThoughtsTokenCount() { return thoughtsTokenCount; }
    public void setThoughtsTokenCount(Long thoughtsTokenCount) { this.thoughtsTokenCount = thoughtsTokenCount; }

    public Long getTotalTokenCount() { return totalTokenCount; }
    public void setTotalTokenCount(Long totalTokenCount) { this.totalTokenCount = totalTokenCount; }

    public Long getExtractionDurationMs() { return extractionDurationMs; }
    public void setExtractionDurationMs(Long extractionDurationMs) { this.extractionDurationMs = extractionDurationMs; }
}
