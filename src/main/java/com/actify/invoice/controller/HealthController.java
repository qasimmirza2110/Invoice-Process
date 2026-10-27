package com.actify.invoice.controller;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.Map;

/**
 * GET /api/health - an unauthenticated liveness check for the hosting platform (e.g. Render's health check path).
 *
 * It answers 200 {"status":"UP"} whenever the web server and the Spring context are running. On purpose it does NOT
 * query the database, call Gemini or read configuration: the answer is identical for every caller and reveals no
 * version, user, invoice, database or secret information. A database outage therefore shows up as failing API calls
 * and in the server log, not here - restarting the container would not fix a database outage anyway.
 *
 * Public in SecurityConfig.PUBLIC_REQUESTS (GET only); the login cookie is ignored on it.
 */
@RestController
public class HealthController {

    public static final String PATH = "/api/health";

    @GetMapping(PATH)
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Collections.singletonMap("status", "UP"));
    }
}
