package com.review.gateway.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Backend Self-Registration startup validation (BSQ-05/BSQ-06): the {@code
 * gateway.backend.allowed-host-pattern} compile-once + backtracking-budget check (unconditional), and the
 * sentinel-based "behaviourally non-universal" gate (only when {@code
 * gateway.backend.self-registration.enabled=true}).
 */
class GatewayPropertiesBackendSelfRegistrationValidationTest {

    private GatewayProperties validProperties() {
        GatewayProperties properties = new GatewayProperties();
        properties.getSecurity().setCiToken("a".repeat(32));
        properties.getSecurity().setWorkerToken("b".repeat(32));
        properties.getSecurity().setAdminToken("c".repeat(32));
        properties.getGitlab().setToken("d".repeat(32));
        properties.getGitlab().setBaseUrl("https://gitlab.example.com/api/v4");
        properties.getPrompt().setEnabled(false);
        return properties;
    }

    // ------------------------------------------------------- BSQ-06: compile-once + ReDoS budget ---

    @Test
    void uncompilablePatternRefusesStartupEvenWhenSelfRegistrationIsOff() {
        GatewayProperties properties = validProperties();
        properties.getBackend().setAllowedHostPattern("[unterminated");

        assertThatThrownBy(properties::validateOnStartup)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("allowed-host-pattern");
    }

    // Note: classic textbook ReDoS shapes (^(a+)+$, ^(a|aa)+$, ^(x+x+)+y$, ...) were empirically verified
    // (throwaway benchmark, not checked in) to complete in low single-digit milliseconds even at n=50 on
    // this JDK (Corretto 21) against a 255-char mismatching input -- java.util.regex's Loop/GroupCurly
    // implementation has absorbed enough of the classic optimizations that these no longer reliably
    // reproduce catastrophic backtracking here, so there is no organically-slow pattern left to build a
    // non-flaky "it actually times out" test around on this engine/version. The 100ms budget-enforcement
    // mechanism itself (bounded Future#get, abandoning the probe thread rather than waiting it out) is
    // still implemented as BSQ-06 requires -- see GatewayProperties#runBacktrackingBudgetProbe -- as
    // defense in depth for whatever pattern/JDK a deployment actually runs.

    @Test
    void benignPatternCompilesAndStartsWithoutSelfRegistration() {
        GatewayProperties properties = validProperties();
        properties.getBackend().setAllowedHostPattern("^192\\.168\\.1\\.\\d+$");

        assertThatCode(properties::validateOnStartup).doesNotThrowAnyException();
        assertThatCode(() -> properties.getBackend().getCompiledAllowedHostPattern().matcher("192.168.1.5").matches())
                .doesNotThrowAnyException();
    }

    @Test
    void theCompiledPatternInstanceIsReusedNotRecompiled() {
        GatewayProperties properties = validProperties();
        properties.getBackend().setAllowedHostPattern("^192\\.168\\.1\\.\\d+$");
        properties.validateOnStartup();

        var first = properties.getBackend().resolveAllowedHostPattern();
        var second = properties.getBackend().resolveAllowedHostPattern();

        assertThatCode(() -> {
            if (first != second) {
                throw new AssertionError("expected the same compiled Pattern instance to be reused");
            }
        }).doesNotThrowAnyException();
    }

    // -------------------------------------------------- BSQ-05: sentinel-based universality gate ---

    @Test
    void literalWildcardRefusesStartupWhenSelfRegistrationEnabled() {
        GatewayProperties properties = validProperties();
        properties.getBackend().getSelfRegistration().setEnabled(true);
        properties.getBackend().setAllowedHostPattern(".*");

        assertThatThrownBy(properties::validateOnStartup)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("allowed-host-pattern");
    }

    @Test
    void blankPatternRefusesStartupWhenSelfRegistrationEnabled() {
        GatewayProperties properties = validProperties();
        properties.getBackend().getSelfRegistration().setEnabled(true);
        properties.getBackend().setAllowedHostPattern("");

        assertThatThrownBy(properties::validateOnStartup).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void dotPlusRefusesStartup() {
        assertRefusesWhenEnabled(".+");
    }

    @Test
    void dotStarDotStarRefusesStartup() {
        assertRefusesWhenEnabled(".*.*");
    }

    @Test
    void anyCharacterClassRefusesStartup() {
        assertRefusesWhenEnabled("[\\s\\S]*");
    }

    @Test
    void dotAllFlagWildcardRefusesStartup() {
        assertRefusesWhenEnabled("(?s).*");
    }

    @Test
    void ipLiteralOnlyPatternRefusesStartup() {
        // BST-03: passes every NAME-shaped sentinel while granting the entire IPv4 space -- the whole
        // point of BSQ-05's grouped check.
        assertRefusesWhenEnabled("^[0-9.]+$");
    }

    @Test
    void singleLabelOnlyPatternRefusesStartup() {
        // "every single-label internal hostname" -- none of the dotted sentinels match this, but every
        // unqualified LAN name does.
        assertRefusesWhenEnabled("^[^.]*$");
    }

    @Test
    void adversarialExclusionOfOneSentinelStillRefusesStartup() {
        // Crafted to exclude exactly the a3f9c1e2-probe.invalid sentinel -- still matches every IP
        // literal sentinel (none of which end in .invalid), so the grouped check still catches it.
        assertRefusesWhenEnabled("^(?!.*\\.invalid$).*$");
    }

    @Test
    void narrowLanPatternStartsSuccessfully() {
        GatewayProperties properties = validProperties();
        properties.getBackend().getSelfRegistration().setEnabled(true);
        properties.getBackend().setAllowedHostPattern("^192\\.168\\.1\\.\\d+$");

        assertThatCode(properties::validateOnStartup).doesNotThrowAnyException();
    }

    @Test
    void patternValidationIsSkippedEntirelyWhenSelfRegistrationIsDisabled() {
        // Same wide-open pattern that would refuse startup if the flag were on -- off by default, this
        // branch must remain a no-op for every deployment that never opts in.
        GatewayProperties properties = validProperties();
        properties.getBackend().setAllowedHostPattern(".*");

        assertThatCode(properties::validateOnStartup).doesNotThrowAnyException();
    }

    @Test
    void maxBackendsMustBePositiveWhenEnabled() {
        GatewayProperties properties = validProperties();
        properties.getBackend().getSelfRegistration().setEnabled(true);
        properties.getBackend().setAllowedHostPattern("^192\\.168\\.1\\.\\d+$");
        properties.getBackend().getSelfRegistration().setMaxBackends(0);

        assertThatThrownBy(properties::validateOnStartup)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max-backends");
    }

    private void assertRefusesWhenEnabled(String pattern) {
        GatewayProperties properties = validProperties();
        properties.getBackend().getSelfRegistration().setEnabled(true);
        properties.getBackend().setAllowedHostPattern(pattern);

        assertThatThrownBy(properties::validateOnStartup)
                .as("pattern=%s", pattern)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("allowed-host-pattern");
    }
}
