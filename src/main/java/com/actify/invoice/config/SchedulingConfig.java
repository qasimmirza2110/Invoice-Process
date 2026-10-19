package com.actify.invoice.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on Spring's @Scheduled support (part of spring-context, no extra library). Spring Boot runs scheduled
 * methods on ONE thread by default, so the extraction worker never processes two jobs at the same time.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
