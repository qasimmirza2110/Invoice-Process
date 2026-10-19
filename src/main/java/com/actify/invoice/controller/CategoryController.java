package com.actify.invoice.controller;

import com.actify.invoice.dto.FieldErrorDto;
import com.actify.invoice.entity.Category;
import com.actify.invoice.exception.InvalidRequestException;
import com.actify.invoice.intake.CategoryCodes;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * GET /api/categories: active categories, for anyone logged in (vendors choose a batch default).
 * /api/admin/categories: ADMIN manages them (SecurityConfig protects /api/admin/**).
 */
@RestController
public class CategoryController {

    private final CategoryCodes categories;

    public CategoryController(CategoryCodes categories) {
        this.categories = categories;
    }

    @GetMapping("/api/categories")
    public List<Category> listActive() {
        return categories.listActive();
    }

    @GetMapping("/api/admin/categories")
    public List<Category> listAll() {
        return categories.listAll();
    }

    /** Body: {"code":"ELECTRONICS","displayName":"Electronics"} */
    @PostMapping(value = "/api/admin/categories", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Category> create(@RequestBody Map<String, String> body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(categories.create(body.get("code"), body.get("displayName")));
    }

    /** Body: {"active":false} */
    @PutMapping(value = "/api/admin/categories/{code}/active", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Category setActive(@PathVariable String code, @RequestBody Map<String, Boolean> body) {
        Boolean active = body.get("active");
        if (active == null) {
            throw new InvalidRequestException("active is required (true or false)",
                    Collections.singletonList(new FieldErrorDto("active", "active is required (true or false)")));
        }
        return categories.setActive(code, active);
    }
}
