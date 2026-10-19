package com.actify.invoice.controller;

import com.actify.invoice.dto.LoginRequest;
import com.actify.invoice.dto.MeResponse;
import com.actify.invoice.security.AuthCookies;
import com.actify.invoice.security.AuthenticatedUser;
import com.actify.invoice.security.CurrentUserService;
import com.actify.invoice.security.JwtTokenService;
import com.actify.invoice.security.LoginService;
import com.actify.invoice.user.AppUser;
import com.actify.invoice.user.AppUserService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Login, logout and "who am I". The token travels only in the HttpOnly TASK8_AUTH cookie;
 * it is never part of a response body, so page JavaScript never sees it.
 * All POSTs here need the X-XSRF-TOKEN header (CSRF), including login.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final LoginService loginService;
    private final JwtTokenService tokenService;
    private final AuthCookies authCookies;
    private final CurrentUserService currentUser;
    private final AppUserService userService;

    public AuthController(LoginService loginService, JwtTokenService tokenService, AuthCookies authCookies,
                          CurrentUserService currentUser, AppUserService userService) {
        this.loginService = loginService;
        this.tokenService = tokenService;
        this.authCookies = authCookies;
        this.currentUser = currentUser;
        this.userService = userService;
    }

    /** 200 + cookie on success; 401 with one generic message on any failure; 429 while locked. */
    @PostMapping(value = "/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    public MeResponse login(@RequestBody LoginRequest body, HttpServletRequest request, HttpServletResponse response) {
        // getRemoteAddr() only: X-Forwarded-For could be forged by anyone, and there is no trusted proxy.
        AppUser user = loginService.login(body.getEmail(), body.getPassword(), request.getRemoteAddr());
        authCookies.setToken(request, response, tokenService.issue(user), tokenService.getLifetime());
        return MeResponse.from(AuthenticatedUser.from(user));
    }

    /** Deletes the cookie in this browser. Works even when the session already expired. */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        authCookies.clear(request, response);
        return ResponseEntity.noContent().build();
    }

    /** "Log out everywhere": every token of this user, in every browser, stops working. */
    @PostMapping("/logout-all")
    public ResponseEntity<Void> logoutEverywhere(HttpServletRequest request, HttpServletResponse response) {
        userService.revokeAllTokens(currentUser.require().getId());
        authCookies.clear(request, response);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public MeResponse me() {
        return MeResponse.from(currentUser.require());
    }
}
