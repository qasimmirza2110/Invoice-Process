package com.actify.invoice.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    /** The email must already be normalized (lower-case, trimmed). */
    Optional<AppUser> findByEmail(String email);

    boolean existsByEmail(String email);

    boolean existsByRole(Role role);

    long countByRoleAndEnabledTrue(Role role);

    List<AppUser> findAllByOrderByIdAsc();
}
