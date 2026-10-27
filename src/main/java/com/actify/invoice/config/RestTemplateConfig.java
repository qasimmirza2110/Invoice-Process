package com.actify.invoice.config;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * The shared RestTemplate used for outgoing HTTP calls - in practice only the Gemini API (GeminiInvoiceExtractor).
 * It exists to put explicit connect/read timeouts on those calls; without them one stuck provider request could
 * block a worker or request thread indefinitely.
 */
@Configuration
public class RestTemplateConfig {

    // Without timeouts a stuck Gemini call would hold the HTTP request thread forever.
    // Reading a multi-page PDF can take a while, so the read timeout is generous.
    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder
                .setConnectTimeout(Duration.ofSeconds(10))
                .setReadTimeout(Duration.ofSeconds(120))
                .build();
    }
}
