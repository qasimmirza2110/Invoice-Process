package com.actify.invoice.config;

import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Free demo only: every response says "X-Invoice-Demo: ephemeral-synthetic-only", so a frontend developer (and their
 * browser's network tab) can always tell the temporary demo from a real environment.
 *
 * Ordered before Spring Security and set BEFORE the request is processed, so 401/403 answers written by the security
 * filters carry it too. It only adds a header: bodies (GET /api/health stays exactly {"status":"UP"}), cookies, CSRF
 * and authentication are untouched.
 */
@Component
@Profile(RenderFreeDemoGuard.PROFILE)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class FreeDemoResponseHeaderFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader(RenderFreeDemoGuard.HEADER_NAME, RenderFreeDemoGuard.HEADER_VALUE);
        chain.doFilter(request, response);
    }
}
