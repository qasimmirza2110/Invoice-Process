package com.actify.invoice.user;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The name the UI shows for a user (label): the stored display name when it is filled in, otherwise the part of the
 * e-mail address before the "@" (alex@example.test -> "alex"). The admin keeps display names meaningful on the Users
 * page (two accounts called "Administrator" would look the same). The UI shows the full e-mail as a tooltip where that
 * matters; a deleted account is shown as UNKNOWN_USER, never as a raw id.
 */
@Component
public class UserNames {

    private static final String ACTOR_PREFIX = "user:";
    /** Shown when a referenced account no longer exists - never a raw id such as "user 5". */
    public static final String UNKNOWN_USER = "Unknown user";

    private final AppUserRepository userRepository;

    public UserNames(AppUserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /** "alex@example.test" -> "alex"; null/blank -> null; no "@" -> the trimmed text itself. */
    public static String fromEmail(String email) {
        if (email == null || email.trim().isEmpty()) {
            return null;
        }
        String trimmed = email.trim();
        int at = trimmed.indexOf('@');
        return at > 0 ? trimmed.substring(0, at) : trimmed;
    }

    /**
     * The ONE rule for the name shown anywhere in the UI/API: the stored display name when it is filled in, otherwise
     * the e-mail before the "@". The admin sets display names on the Users page.
     */
    public static String label(String displayName, String email) {
        if (displayName != null && !displayName.trim().isEmpty()) {
            return displayName.trim();
        }
        return fromEmail(email);
    }

    /** "user:<id>" (invoice_versions.changed_by) -> the user id, or null for anything else (e.g. background work). */
    public static Long idFromActorLabel(String actorLabel) {
        if (actorLabel == null || !actorLabel.startsWith(ACTOR_PREFIX)) {
            return null;
        }
        try {
            return Long.valueOf(actorLabel.substring(ACTOR_PREFIX.length()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** null when there is no user (background work, not assigned); UNKNOWN_USER when the account no longer exists. */
    public static String nameOrUnknown(Map<Long, String> names, Long id) {
        if (id == null) {
            return null;
        }
        String name = names.get(id);
        return name == null ? UNKNOWN_USER : name;
    }

    /** id -> name for every id that exists; one query, unknown ids are left out. */
    @Transactional(readOnly = true)
    public Map<Long, String> namesById(Collection<Long> ids) {
        Set<Long> wanted = new HashSet<>();
        for (Long id : ids) {
            if (id != null) {
                wanted.add(id);
            }
        }
        Map<Long, String> result = new HashMap<>();
        if (wanted.isEmpty()) {
            return result;
        }
        for (AppUser user : userRepository.findAllById(wanted)) {
            result.put(user.getId(), label(user.getDisplayName(), user.getEmail()));
        }
        return result;
    }
}
