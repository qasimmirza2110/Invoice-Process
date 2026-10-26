package com.actify.invoice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point of the Actify Invoice Process backend - an API-only Spring Boot application (no HTML is served).
 *
 * Component scanning starts in this package. Before any database connection is opened, SchemaSafetyGuard checks
 * that the configured profile and database are allowed (local demo, isolated test database, or one explicitly
 * approved database). See README.md for how to start it and docs/REACT_BACKEND_HANDOFF.md for the API.
 */
@SpringBootApplication
public class InvoiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(InvoiceApplication.class, args);
    }
}
