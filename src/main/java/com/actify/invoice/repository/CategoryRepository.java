package com.actify.invoice.repository;

import com.actify.invoice.entity.Category;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Invoice categories (e.g. "FURNITURE"). Reviewers are matched to invoices by category, and inactive categories
 * stay in the table so historical invoices keep a valid code. Managed through CategoryCodes.
 */
public interface CategoryRepository extends JpaRepository<Category, String> {

    List<Category> findAllByOrderByCodeAsc();

    List<Category> findAllByActiveTrueOrderByCodeAsc();
}
