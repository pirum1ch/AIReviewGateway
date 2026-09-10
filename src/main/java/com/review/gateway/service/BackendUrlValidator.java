package com.review.gateway.service;

import com.review.gateway.exception.BackendUnavailableException;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * SR-10 SSRF guard for {@code backends.url}, applied by {@link BackendProberImpl} before every probe
 * (the URL is data — written to the registry, ultimately DB-sourced — not a trusted, admin-fixed
 * constant like the GitLab base URL, so it must be re-validated on every use, not just at write time).
 *
 * <ol>
 *   <li>scheme must be {@code http} or {@code https};</li>
 *   <li>no userinfo, and — <b>BSQ-04, Backend Self-Registration</b> — no path/query/fragment: the value
 *       must be a <b>bare origin</b>, {@code scheme://host[:port]}, full stop. Before this, {@link
 *       BackendProberImpl} concatenated {@code url + "/health"}, so a stored {@code url} carrying a path
 *       or query turned the "bare health GET" claim into an arbitrary-path/arbitrary-query GET
 *       (threat-model BST-02). Rejecting rather than silently stripping is deliberate — the caller finds
 *       out immediately, and a legacy (pre-V6, raw-SQL-inserted) row carrying a path now simply fails its
 *       next probe instead of ever being trusted;</li>
 *   <li>host must be no longer than 255 characters (BSQ-06) — checked <em>before</em> the allowlist regex
 *       match, since an operator-authored pattern has no timeout of its own;</li>
 *   <li>host must match the configured {@code gateway.backend.allowed-host-pattern}, supplied here as an
 *       already-{@link Pattern#compile(String) compiled} {@link Pattern} — <b>BSQ-06, Backend
 *       Self-Registration:</b> callers must compile the configured regex exactly once (see {@code
 *       GatewayProperties#validateAllowedHostPatternOnStartup}) and pass the same instance on every call,
 *       never recompile per call. Recompiling was tolerable while this pattern was only ever DBA-authored
 *       against DBA-chosen data; self-registration makes it the first hand-authored, per-deployment regex
 *       evaluated against <em>caller-supplied</em> input in this project, so an uncompilable or
 *       catastrophically-backtracking pattern must be caught once, at startup — not recompiled (and
 *       re-risked) on every single probe/announce. <b>F-BSR-06:</b> this check runs <em>before</em> DNS
 *       resolution (the next bullet), deliberately — see that bullet;</li>
 *   <li>only then, host must resolve, and must not be loopback/link-local/any-local/multicast — this
 *       blocks the classic SSRF targets ({@code 127.0.0.1}, {@code 169.254.169.254} cloud metadata,
 *       {@code 0.0.0.0}, etc.) while still allowing ordinary private-LAN addresses
 *       (10.x/172.16-31.x/192.168.x) where the Mac-mini backends actually live. Checks <em>every</em>
 *       address a name resolves to (BSQ-INH-3), not just the first — a host with one public and one
 *       loopback/link-local record must not slip past on the lucky one. <b>F-BSR-06:</b> resolution
 *       (unbounded, no timeout, {@link InetAddress#getAllByName}) is deliberately ordered <em>after</em>
 *       the allowlist match so a fleet-token holder can only provoke a resolver query for a hostname the
 *       operator's own pattern already admits, not for an arbitrary submitted hostname — do not reorder
 *       these two checks; both orderings reject the same set of URLs, but only this one bounds the DNS
 *       side channel;</li>
 * </ol>
 *
 * <p>Redirect-following is disabled at the transport layer ({@code RestClientConfig}), and connect/read
 * timeouts are short (§9 {@code gateway.backend.*}) — both also part of the SR-10 control set.
 */
final class BackendUrlValidator {

    /** BSQ-06: rejected before ever reaching the (operator-authored, potentially slow) allowlist regex. */
    private static final int MAX_HOST_LENGTH = 255;

    private BackendUrlValidator() {
    }

    /**
     * @param allowedHostPattern a pre-compiled pattern (BSQ-06); {@code null} is treated as {@code .*}
     *                           (matches the pre-existing "blank means permissive" behavior)
     * @return the normalized bare origin — {@code scheme://host[:port]}, lower-cased scheme, no trailing
     *         slash, no path/query/fragment/userinfo (BSQ-04). Write-path callers persist <em>this</em>
     *         return value, never the caller-supplied raw string.
     */
    static String validate(String url, Pattern allowedHostPattern) {
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

        if (uri.getRawUserInfo() != null) {
            throw new BackendUnavailableException("Backend URL must be a bare origin (no userinfo)");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new BackendUnavailableException("Backend URL has no host");
        }
        if (host.length() > MAX_HOST_LENGTH) {
            throw new BackendUnavailableException("Backend URL host exceeds the maximum allowed length");
        }

        // BSQ-04/BST-02: bare origin only -- see class javadoc.
        String rawPath = uri.getRawPath();
        if (rawPath != null && !rawPath.isEmpty()) {
            throw new BackendUnavailableException("Backend URL must be a bare origin (no path)");
        }
        if (uri.getRawQuery() != null) {
            throw new BackendUnavailableException("Backend URL must be a bare origin (no query string)");
        }
        if (uri.getRawFragment() != null) {
            throw new BackendUnavailableException("Backend URL must be a bare origin (no fragment)");
        }

        // F-BSR-06: the allowlist regex (pure in-memory, no I/O) MUST run before isBlockedHost (a DNS
        // lookup with no timeout) -- do not reorder these two checks. Both orderings reject the same set
        // of URLs (outcome-neutral), but resolving first meant a fleet-token holder could provoke a
        // resolver query for ANY submitted hostname, allowlisted or not (an unbounded DNS/QNAME
        // exfiltration primitive plus a measurable timing oracle: ~0ms for an allowlist-rejected IP
        // literal vs 46-57ms wall-clock for an allowlist-rejected hostname that goes through DNS). Running
        // the allowlist first narrows DNS resolution to only hostnames the operator's own pattern already
        // admits -- for an IP-literal-only pattern (the deployment-recommended shape) this is zero DNS
        // queries, ever.
        Pattern pattern = allowedHostPattern != null ? allowedHostPattern : Pattern.compile(".*");
        if (!pattern.matcher(host.toLowerCase(Locale.ROOT)).matches()) {
            throw new BackendUnavailableException("Backend URL host does not match the configured allowlist");
        }

        if (isBlockedHost(host)) {
            throw new BackendUnavailableException("Backend URL host is in a blocked range (loopback/link-local/metadata)");
        }

        int port = uri.getPort();
        String normalizedScheme = scheme.toLowerCase(Locale.ROOT);
        return port == -1 ? normalizedScheme + "://" + host : normalizedScheme + "://" + host + ":" + port;
    }

    private static boolean isBlockedHost(String host) {
        if (host.equalsIgnoreCase("localhost")) {
            return true;
        }
        try {
            // BSQ-INH-3: every resolved address is checked, not just the first -- a host with one public
            // and one loopback/link-local A/AAAA record must not slip past on the lucky one.
            InetAddress[] addresses = InetAddress.getAllByName(host);
            for (InetAddress address : addresses) {
                if (address.isLoopbackAddress() || address.isLinkLocalAddress()
                        || address.isAnyLocalAddress() || address.isMulticastAddress()) {
                    return true;
                }
            }
            return false;
        } catch (UnknownHostException unresolvable) {
            // Can't resolve -> treat as unsafe rather than silently letting an unresolvable host through.
            return true;
        }
    }
}
