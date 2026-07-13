package com.review.gateway.service;

import com.review.gateway.exception.BackendUnavailableException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** SR-10 SSRF guard unit tests. */
class BackendUrlValidatorTest {

    @Test
    void allowsAnOrdinaryPrivateLanAddress() {
        assertThatCode(() -> BackendUrlValidator.validate("http://192.168.1.50:8080", ".*"))
                .doesNotThrowAnyException();
    }

    @Test
    void allowsAnOrdinaryPrivateLanAddressOverHttps() {
        // A symbolic private-LAN hostname (e.g. "mac-mini-01.lan") would depend on real DNS/mDNS
        // resolution being available in the test environment; an IP literal keeps this deterministic.
        assertThatCode(() -> BackendUrlValidator.validate("https://192.168.1.60:8443", ".*"))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsCloudMetadataAddress() {
        assertThatThrownBy(() -> BackendUrlValidator.validate("http://169.254.169.254/latest/meta-data", ".*"))
                .isInstanceOf(BackendUnavailableException.class);
    }

    @Test
    void rejectsLoopbackAddress() {
        assertThatThrownBy(() -> BackendUrlValidator.validate("http://127.0.0.1:8080", ".*"))
                .isInstanceOf(BackendUnavailableException.class);
    }

    @Test
    void rejectsLoopbackHostname() {
        assertThatThrownBy(() -> BackendUrlValidator.validate("http://localhost:8080", ".*"))
                .isInstanceOf(BackendUnavailableException.class);
    }

    @Test
    void rejectsIpv6Loopback() {
        assertThatThrownBy(() -> BackendUrlValidator.validate("http://[::1]:8080", ".*"))
                .isInstanceOf(BackendUnavailableException.class);
    }

    @Test
    void rejectsAnyLocalAddress() {
        assertThatThrownBy(() -> BackendUrlValidator.validate("http://0.0.0.0:8080", ".*"))
                .isInstanceOf(BackendUnavailableException.class);
    }

    @Test
    void rejectsUnsupportedScheme() {
        assertThatThrownBy(() -> BackendUrlValidator.validate("ftp://192.168.1.50/health", ".*"))
                .isInstanceOf(BackendUnavailableException.class);
    }

    @Test
    void rejectsMalformedUrl() {
        assertThatThrownBy(() -> BackendUrlValidator.validate("not a url at all", ".*"))
                .isInstanceOf(BackendUnavailableException.class);
    }

    @Test
    void rejectsHostNotMatchingConfiguredAllowlist() {
        assertThatThrownBy(() -> BackendUrlValidator.validate("http://192.168.1.50:8080", "^10\\..*"))
                .isInstanceOf(BackendUnavailableException.class);
    }

    @Test
    void allowsHostMatchingConfiguredAllowlist() {
        assertThatCode(() -> BackendUrlValidator.validate("http://10.0.0.5:8080", "^10\\..*"))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsUnresolvableHost() {
        // ".invalid" is a reserved TLD (RFC 2606) guaranteed to never resolve.
        assertThatThrownBy(() -> BackendUrlValidator.validate("http://this-host-does-not-exist.invalid/health", ".*"))
                .isInstanceOf(BackendUnavailableException.class);
    }
}
