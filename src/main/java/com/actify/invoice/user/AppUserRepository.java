package com.actify.invoice.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Application users (app_users). E-mail addresses are stored normalized (lower-case, trimmed) and are unique in the
 * database itself. Passwords are only ever stored as BCrypt hashes; the role and token version read from these rows
 * on every request are what authorization is based on (JwtCookieAuthenticationFilter), never values from the client.
 */
public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    /** The email must already be normalized (lower-case, trimmed). */
    Optional<AppUser> findByEmail(String email);

    boolean existsByEmail(String email);

    boolean existsByRole(Role role);

    long countByRoleAndEnabledTrue(Role role);

    List<AppUser> findAllByOrderByIdAsc();
}
