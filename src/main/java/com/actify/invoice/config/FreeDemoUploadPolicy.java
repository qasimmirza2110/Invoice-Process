package com.actify.invoice.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Free demo only: an upload is accepted only when its exact bytes are one of the published synthetic fixture PDFs
 * (docs/react-handoff/demo-fixtures/, SHA-256 list in application-render-free-demo.properties). Synthetic e-mail
 * addresses prove nothing about a file's content, and the demo is public, ephemeral and has no backups - so a real
 * invoice must never get in, not even by accident.
 *
 * BatchIntakeService asks this policy FIRST, before any other check, so a refused file is never stored and its name
 * is never recorded (a real file name can itself be customer data). The refusal is a normal 422 rejection
 * (NOT_A_DEMO_FIXTURE) - nothing is faked. Outside the render-free-demo profile the policy is inert and every upload
 * follows the production rules unchanged. RenderFreeDemoGuard refuses a demo start with an empty or malformed list.
 */
@Component
public class FreeDemoUploadPolicy {

    public static final String ALLOWLIST_PROPERTY = "invoice.free-demo.allowed-upload-sha256";
    public static final String REJECTION_CODE = "NOT_A_DEMO_FIXTURE";
    public static final String REJECTION_MESSAGE = "This temporary demo accepts only the provided synthetic fixture PDFs "
            + "(synthetic-demo-invoice-01.pdf to -06.pdf). Never upload a real invoice here.";
    /** Recorded instead of the uploaded file's own name: that name may be real customer data. */
    public static final String NEUTRAL_FILE_NAME = "file-not-accepted-by-demo.pdf";

    static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    private final boolean enforced;
    private final Set<String> allowed;

    @Autowired // two constructors: tell Spring which one to use (FreeDemoUploadPolicyWiringTest)
    public FreeDemoUploadPolicy(Environment environment, @Value("${" + ALLOWLIST_PROPERTY + ":}") String allowlist) {
        this(RenderFreeDemoGuard.isActive(environment), allowlist);
    }

    FreeDemoUploadPolicy(boolean enforced, String allowlist) {
        this.enforced = enforced;
        this.allowed = Collections.unmodifiableSet(parse(allowlist));
    }

    /** Comma-separated, whitespace tolerated, compared lower-case. Malformed entries are dropped (and refused by the guard). */
    static Set<String> parse(String allowlist) {
        if (allowlist == null || allowlist.trim().isEmpty()) {
            return Collections.emptySet();
        }
        return Arrays.stream(allowlist.split(","))
                .map(s -> s.trim().toLowerCase(Locale.ROOT))
                .filter(s -> SHA256.matcher(s).matches())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    public boolean isEnforced() {
        return enforced;
    }

    /** Only meaningful when enforced; an empty list allows nothing. */
    public boolean allows(String sha256Hex) {
        return sha256Hex != null && allowed.contains(sha256Hex.toLowerCase(Locale.ROOT));
    }
}
