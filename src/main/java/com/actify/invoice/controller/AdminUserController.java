package com.actify.invoice.controller;

import com.actify.invoice.dto.CreateUserRequest;
import com.actify.invoice.dto.FieldErrorDto;
import com.actify.invoice.dto.UserResponse;
import com.actify.invoice.dto.UserUpdateRequest;
import com.actify.invoice.exception.InvalidRequestException;
import com.actify.invoice.user.AppUser;
import com.actify.invoice.user.AppUserService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Minimal user management. Everything under /api/admin/** is ADMIN-only (SecurityConfig).
 * Changing a role or disabling a user raises the user's token version, so their existing logins end at once.
 */
@RestController
@RequestMapping("/api/admin/users")
public class AdminUserController {

    private final AppUserService userService;

    public AdminUserController(AppUserService userService) {
        this.userService = userService;
    }

    @GetMapping
    public List<UserResponse> list() {
        List<UserResponse> result = new ArrayList<>();
        for (AppUser user : userService.listUsers()) {
            result.add(UserResponse.from(user));
        }
        return result;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<UserResponse> create(@RequestBody CreateUserRequest body) {
        AppUser user = userService.createUser(body.getEmail(), body.getDisplayName(), body.getRole(), body.getPassword());
        return ResponseEntity.status(HttpStatus.CREATED).body(UserResponse.from(user));
    }

    @PutMapping(value = "/{id}/role", consumes = MediaType.APPLICATION_JSON_VALUE)
    public UserResponse changeRole(@PathVariable Long id, @RequestBody UserUpdateRequest body) {
        return UserResponse.from(userService.changeRole(id, body.getRole()));
    }

    /** ADMIN only (/api/admin/**). Body {"displayName":"Sam"}; blank = the e-mail before the "@". */
    @PutMapping(value = "/{id}/display-name", consumes = MediaType.APPLICATION_JSON_VALUE)
    public UserResponse changeDisplayName(@PathVariable Long id, @RequestBody UserUpdateRequest body) {
        return UserResponse.from(userService.changeDisplayName(id, body.getDisplayName()));
    }

    @PutMapping(value = "/{id}/enabled", consumes = MediaType.APPLICATION_JSON_VALUE)
    public UserResponse setEnabled(@PathVariable Long id, @RequestBody UserUpdateRequest body) {
        if (body.getEnabled() == null) {
            throw new InvalidRequestException("enabled is required (true or false)", Collections.singletonList(
                    new FieldErrorDto("enabled", "enabled is required (true or false)")));
        }
        return UserResponse.from(userService.setEnabled(id, body.getEnabled()));
    }
}
