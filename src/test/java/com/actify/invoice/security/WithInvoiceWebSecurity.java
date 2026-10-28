package com.actify.invoice.security;

import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * For @WebMvcTest classes: loads the REAL security configuration (filter chain, Bearer JWT filter,
 * access policy) with a fixed test-only signing key. A @WebMvcTest does not pick these up by itself.
 *
 * The test class still declares @MockBean AppUserRepository and @MockBean InvoiceRepository
 * (no database in these tests) - see TestLogins for creating Bearer tokens.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Import({SecurityConfig.class, JwtTokenService.class, CurrentUserService.class,
        InvoiceAccessPolicy.class})
@TestPropertySource(properties = "invoice.jwt.secret=" + TestLogins.TEST_SECRET)
public @interface WithInvoiceWebSecurity {
}
