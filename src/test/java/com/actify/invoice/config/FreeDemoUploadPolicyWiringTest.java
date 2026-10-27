package com.actify.invoice.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spring must be able to build FreeDemoUploadPolicy (it has two constructors), and it must be inert outside the free
 * demo. Every profile's application context needs this bean, because BatchIntakeService depends on it.
 */
class FreeDemoUploadPolicyWiringTest {

    private static final String HASH = "85a802e3f35521ac1ee674e5cc807f9f43cbd62716ba8586c15135d0ea05a323";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(FreeDemoUploadPolicy.class)
            .withPropertyValues(FreeDemoUploadPolicy.ALLOWLIST_PROPERTY + "=" + HASH);

    @Test
    void outsideTheDemoSpringBuildsAnInertPolicy() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(FreeDemoUploadPolicy.class).isEnforced()).isFalse();
        });
    }

    @Test
    void inTheDemoSpringBuildsAnEnforcingPolicyWithTheConfiguredList() {
        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles(RenderFreeDemoGuard.PROFILE))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    FreeDemoUploadPolicy policy = context.getBean(FreeDemoUploadPolicy.class);
                    assertThat(policy.isEnforced()).isTrue();
                    assertThat(policy.allows(HASH)).isTrue();
                    assertThat(policy.allows("0000000000000000000000000000000000000000000000000000000000000000"))
                            .isFalse();
                });
    }
}
