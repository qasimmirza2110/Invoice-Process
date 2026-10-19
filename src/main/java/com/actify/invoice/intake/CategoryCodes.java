package com.actify.invoice.intake;

import com.actify.invoice.dto.FieldErrorDto;
import com.actify.invoice.entity.Category;
import com.actify.invoice.exception.InvalidRequestException;
import com.actify.invoice.exception.UserConflictException;
import com.actify.invoice.repository.CategoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Listing, creating and checking invoice categories (ADMIN manages them; everyone logged in may list active ones). */
@Service
public class CategoryCodes {

    private final CategoryRepository categoryRepository;

    public CategoryCodes(CategoryRepository categoryRepository) {
        this.categoryRepository = categoryRepository;
    }

    /** " electronics " -> "ELECTRONICS"; blank -> null. Letters, digits and _ only. */
    public static String normalize(String code) {
        if (code == null || code.trim().isEmpty()) {
            return null;
        }
        return code.trim().toUpperCase(Locale.ROOT);
    }

    /** @return the normalized code of an ACTIVE category, or null for "no category"; 400 for anything else. */
    @Transactional(readOnly = true)
    public String requireActiveOrNull(String code) {
        String normalized = normalize(code);
        if (normalized == null) {
            return null;
        }
        Category category = categoryRepository.findById(normalized).orElse(null);
        if (category == null || !category.isActive()) {
            throw invalid("categoryCode", "Unknown or inactive category '" + normalized + "'");
        }
        return normalized;
    }

    @Transactional(readOnly = true)
    public List<Category> listActive() {
        return categoryRepository.findAllByActiveTrueOrderByCodeAsc();
    }

    @Transactional(readOnly = true)
    public List<Category> listAll() {
        return categoryRepository.findAllByOrderByCodeAsc();
    }

    @Transactional
    public Category create(String code, String displayName) {
        String normalized = normalize(code);
        if (normalized == null || !normalized.matches("[A-Z0-9_]{2,50}")) {
            throw invalid("code", "code must be 2-50 characters: letters, digits or _ (e.g. ELECTRONICS)");
        }
        String name = displayName == null ? "" : displayName.trim();
        if (name.isEmpty() || name.length() > 100) {
            throw invalid("displayName", "displayName is required (1-100 characters)");
        }
        if (categoryRepository.existsById(normalized)) {
            throw new UserConflictException("Category " + normalized + " already exists.");
        }
        return categoryRepository.save(new Category(normalized, name));
    }

    @Transactional
    public Category setActive(String code, boolean active) {
        Category category = categoryRepository.findById(normalize(code) == null ? "" : normalize(code))
                .orElseThrow(() -> invalid("code", "Unknown category"));
        category.setActive(active);
        return category;
    }

    private static InvalidRequestException invalid(String field, String message) {
        return new InvalidRequestException(message, Collections.singletonList(new FieldErrorDto(field, message)));
    }
}
