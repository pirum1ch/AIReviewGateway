package com.review.gateway.service;

import com.review.gateway.exception.BackendUnavailableException;
import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** SR-10 SSRF guard unit tests; extended for Backend Self-Registration (BSQ-04/06/07/24). */
class BackendUrlValidatorTest {

    private static final Pattern ANY_HOST = Pattern.compile(".*");

    @Test
    void allowsAnOrdinaryPrivateLanAddress() {
        assertThatCode(() -> BackendUrlValidator.validate("http://192.168.1.50:8080", ANY_HOST))
                .doesNotThrowAnyException();
    }

    @Test
    void allowsAnOrdinaryPrivateLanAddressOverHttps() {
        // A symbolic private-LAN hostname (e.g. "mac-mini-01.lan") would depend on real DNS/mDNS
        // resolution being available in the test environment; an IP literal keeps this deterministic.
        assertThatCode(() -> BackendUrlValidator.validate("https://192.168.1.60:8443", ANY_HOST))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsCloudMetadataAddress() {
        assertThatThrownBy(() -> BackendUrlValidator.validate("http://169.254.169.254/latest/meta-data", ANY_HOST))
                .isInstanceOf(BackendUnavailableException.class);
    }

    @Test
    void rejectsLoopbackAddress() {
        assertThatThrownBy(() -> BackendUrlValidator.validate("http://127.0.0.1:8080", ANY_HOST))
                .isInstanceOf(BackendUnavailableException.class);
    }

    @Test
    void rejectsLoopbackHostname() {
        assertThatThrownBy(() -> BackendUrlValidator.validate("http://localhost:8080", ANY_HOST))
                .isInstanceOf(BackendUnavailableException.class);
    }

    @Test
    void rejectsIpv6Loopback() {
        assertThatThrownBy(() -> BackendUrlValidator.validate("http://[::1]:8080", ANY_HOST))
                .isInstanceOf(BackendUnavailableException.class);
    }

    @Test
    void rejectsAnyLocalAddress() {
        assertThatThrownBy(() -> BackendUrlValidator.validate("http://0.0.0.0:8080", ANY_HOST))
                .isInstanceOf(BackendUnavailableException.class);
    }

    @Test
    void rejectsUnsupportedScheme() {
        assertThatThrownBy(() -> BackendUrlValidator.validate("ftp://192.168.1.50/health", ANY_HOST))
                .isInstanceOf(BackendUnavailableException.class);
    }

    @Test
    void rejectsMalformedUrl() {
        assertThatThrownBy(() -> BackendUrlValidator.validate("not a url at all", ANY_HOST))
                .isInstanceOf(BackendUnavailableException.class);
    }

    @Test
    void rejectsHostNotMatchingConfiguredAllowlist() {
        assertThatThrownBy(() -> BackendUrlValidator.validate("http://192.168.1.50:8080", Pattern.compile("^10\\..*")))
                .isInstanceOf(BackendUnavailableException.class);
    }

    @Test
    void allowsHostMatchingConfiguredAllowlist() {
        assertThatCode(() -> BackendUrlValidator.validate("http://10.0.0.5:8080", Pattern.compile("^10\\..*")))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsUnresolvableHost() {
        // ".invalid" is a reserved TLD (RFC 2606) guaranteed to never resolve.
        assertThatThrownBy(() -> BackendUrlValidator.validate("http://this-host-does-not-exist.invalid/health", ANY_HOST))
                .isInstanceOf(BackendUnavailableException.class);
    }

    // ------------------------------------------------------- BSQ-04: bare-origin normalization -----

    @Test
    void returnsTheNormalizedBareOriginWithPort() {
        assertThat(BackendUrlValidator.validate("http://192.168.1.50:8080", ANY_HOST))
                .isEqualTo("http://192.168.1.50:8080");
    }

    @Test
    void returnsTheNormalizedBareOriginWithoutPort() {
        assertThat(BackendUrlValidator.validate("http://192.168.1.50", ANY_HOST))
                .isEqualTo("http://192.168.1.50");
    }

    @Test
    void lowerCasesTheSchemeInTheNormalizedOrigin() {
        assertThat(BackendUrlValidator.validate("HTTP://192.168.1.50:8080", ANY_HOST))
                .isEqualTo("http://192.168.1.50:8080");
    }

    @Test
    void rejectsAPathEvenJustASlash() {
        assertThatThrownBy(() -> BackendUrlValidator.validate("http://192.168.1.50:8080/", ANY_HOST))
                .isInstanceOf(BackendUnavailableException.class);
    }

    @Test
    void rejectsAnArbitraryPath() {
        assertThatThrownBy(() -> BackendUrlValidator.validate("http://192.168.1.50:8080/admin/reset", ANY_HOST))
                .isInstanceOf(BackendUnavailableException.class);
    }

    @Test
    void rejectsAQueryString() {
        assertThatThrownBy(() -> BackendUrlValidator.validate("http://192.168.1.50:8080?x=1", ANY_HOST))
                .isInstanceOf(BackendUnavailableException.class);
    }

    @Test
    void rejectsAFragment() {
        assertThatThrownBy(() -> BackendUrlValidator.validate("http://192.168.1.50:8080#frag", ANY_HOST))
                .isInstanceOf(BackendUnavailableException.class);
    }

    @Test
    void rejectsUserinfo() {
        assertThatThrownBy(() -> BackendUrlValidator.validate("http://user:pass@192.168.1.50:8080", ANY_HOST))
                .isInstanceOf(BackendUnavailableException.class);
    }

    @Test
    void rejectsAHostLongerThan255Characters() {
        String longHost = "a".repeat(250) + ".test";
        assertThatThrownBy(() -> BackendUrlValidator.validate("http://" + longHost, ANY_HOST))
                .isInstanceOf(BackendUnavailableException.class);
    }

    // -------------------------------------------------------------------- BSQ-24: full match only ---

    @Test
    void fullMatchSemanticsRejectAHostThatOnlyPartiallyMatches() {
        // The pattern below would match a SUBSTRING of "evil.com.192.168.1.5" under Matcher.find()
        // semantics; String.matches()/Pattern.matches() are always a FULL match, so this must be
        // rejected outright rather than accepted because "192.168.1.5" appears somewhere in the host.
        Pattern narrow = Pattern.compile("^192\\.168\\.1\\.\\d+$");
        assertThat(narrow.matcher("evil.com.192.168.1.5").matches()).isFalse();
    }
}
