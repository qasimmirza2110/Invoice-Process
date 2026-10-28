package com.actify.invoice.controller;

import com.actify.invoice.dto.LoginRequest;
import com.actify.invoice.dto.LoginResponse;
import com.actify.invoice.dto.MeResponse;
import com.actify.invoice.security.AuthenticatedUser;
import com.actify.invoice.security.CurrentUserService;
import com.actify.invoice.security.JwtTokenService;
import com.actify.invoice.security.LoginService;
import com.actify.invoice.user.AppUser;
import com.actify.invoice.user.AppUserService;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;

/**
 * Login, "who am I" and "log out everywhere".
 *
 * Login returns the access token in the JSON body; the client sends it back as "Authorization: Bearer <token>".
 * No cookie is ever set. There is no "log out this one token" endpoint: a token is not stored on the server, so
 * logging out in one browser means the frontend forgets its in-memory token. POST /api/auth/logout-all is the real,
 * server-side revocation: it raises the user's token version, so EVERY token issued to that user so far stops working.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final LoginService loginService;
    private final JwtTokenService tokenService;
    private final CurrentUserService currentUser;
    private final AppUserService userService;

    public AuthController(LoginService loginService, JwtTokenService tokenService, CurrentUserService currentUser,
                          AppUserService userService) {
        this.loginService = loginService;
        this.tokenService = tokenService;
        this.currentUser = currentUser;
        this.userService = userService;
    }

    /** 200 + token on success; 401 with one generic message on any failure; 429 while locked. */
    @PostMapping(value = "/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<LoginResponse> login(@RequestBody LoginRequest body, HttpServletRequest request) {
        // getRemoteAddr() only - this code never reads X-Forwarded-For itself (anyone could forge it). In the render
        // profile Tomcat may replace the address with the client's, but only for an internal proxy (forward headers).
        AppUser user = loginService.login(body.getEmail(), body.getPassword(), request.getRemoteAddr());
        JwtTokenService.IssuedToken token = tokenService.issueWithExpiry(user);
        LoginResponse response = new LoginResponse(token.getValue(), tokenService.getLifetime().getSeconds(),
                token.getExpiresAt(), MeResponse.from(AuthenticatedUser.from(user)));
        // A response holding a credential must never be stored by a browser or proxy cache.
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Pragma", "no-cache").body(response);
    }

    /** "Log out everywhere": every token of this user, in every browser, stops working on its next request. */
    @PostMapping("/logout-all")
    public ResponseEntity<Void> logoutEverywhere() {
        userService.revokeAllTokens(currentUser.require().getId());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public MeResponse me() {
        return MeResponse.from(currentUser.require());
    }
}
