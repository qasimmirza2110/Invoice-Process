package com.actify.invoice.controller;

import com.actify.invoice.dashboard.DashboardService;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.actify.invoice.security.CurrentUserService;
import com.actify.invoice.user.Role;

import java.util.Map;

/**
 * Role dashboards. Each user may only open the dashboard of their own role (403 otherwise); every number inside is
 * already scoped to that user (vendor: own uploads, reviewer: own assignments, admin: everything).
 * ?days=N: the last N business days in Asia/Kolkata including today (1-90, default 7).
 */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final DashboardService dashboards;
    private final CurrentUserService currentUser;

    public DashboardController(DashboardService dashboards, CurrentUserService currentUser) {
        this.dashboards = dashboards;
        this.currentUser = currentUser;
    }

    @GetMapping("/vendor")
    public Map<String, Object> vendor(@RequestParam(value = "days", defaultValue = "7") int days) {
        requireRole(Role.VENDOR);
        return dashboards.vendor(clamp(days));
    }

    @GetMapping("/reviewer")
    public Map<String, Object> reviewer(@RequestParam(value = "days", defaultValue = "7") int days) {
        requireRole(Role.REVIEWER);
        return dashboards.reviewer(clamp(days));
    }

    @GetMapping("/admin")
    public Map<String, Object> admin(@RequestParam(value = "days", defaultValue = "7") int days) {
        requireRole(Role.ADMIN);
        return dashboards.admin(clamp(days));
    }

    private void requireRole(Role role) {
        if (currentUser.require().getRole() != role) {
            throw new AccessDeniedException("This dashboard belongs to another role.");
        }
    }

    private static int clamp(int days) {
        return Math.max(1, Math.min(days, 90));
    }
}
