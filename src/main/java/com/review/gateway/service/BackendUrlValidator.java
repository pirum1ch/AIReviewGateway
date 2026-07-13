package com.review.gateway.service;

import com.review.gateway.exception.BackendUnavailableException;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * SR-10 SSRF guard for {@code backends.url}, applied by {@link BackendProberImpl} before every probe
 * (the URL is data — written to the registry, ultimately DB-sourced — not a trusted, admin-fixed
 * constant like the GitLab base URL, so it must be re-validated on every use, not just at write time).
 *
 * <ol>
 *   <li>scheme must be {@code http} or {@code https};</li>
 *   <li>host must resolve, and must not be loopback/link-local/any-local/multicast — this blocks the
 *       classic SSRF targets ({@code 127.0.0.1}, {@code 169.254.169.254} cloud metadata, {@code
 *       0.0.0.0}, etc.) while still allowing ordinary private-LAN addresses (10.x/172.16-31.x/192.168.x)
 *       where the Mac-mini backends actually live;</li>
 *   <li>host must additionally match the configured {@code gateway.backend.allowed-host-pattern}
 *       (permissive by default; operators can tighten it per deployment).</li>
 * </ol>
 *
 * <p>Redirect-following is disabled at the transport layer ({@code RestClientConfig}), and connect/read
 * timeouts are short (§9 {@code gateway.backend.*}) — both also part of the SR-10 control set.
 */
final class BackendUrlValidator {

    private BackendUrlValidator() {
    }

    static void validate(String url, String allowedHostPattern) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException | NullPointerException malformed) {
            throw new BackendUnavailableException("Backend URL is malformed");
        }

        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new BackendUnavailableException("Backend URL scheme must be http or https");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new BackendUnavailableException("Backend URL has no host");
        }

        if (isBlockedHost(host)) {
            throw new BackendUnavailableException("Backend URL host is in a blocked range (loopback/link-local/metadata)");
        }

        String pattern = (allowedHostPattern == null || allowedHostPattern.isBlank()) ? ".*" : allowedHostPattern;
        if (!host.toLowerCase(Locale.ROOT).matches(pattern)) {
            throw new BackendUnavailableException("Backend URL host does not match the configured allowlist");
        }
    }

    private static boolean isBlockedHost(String host) {
        if (host.equalsIgnoreCase("localhost")) {
            return true;
        }
        try {
            InetAddress address = InetAddress.getByName(host);
            return address.isLoopbackAddress()
                    || address.isLinkLocalAddress()
                    || address.isAnyLocalAddress()
                    || address.isMulticastAddress();
        } catch (UnknownHostException unresolvable) {
            // Can't resolve -> treat as unsafe rather than silently letting an unresolvable host through.
            return true;
        }
    }
}
