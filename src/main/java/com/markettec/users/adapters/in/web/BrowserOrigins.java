package com.markettec.users.adapters.in.web;

import java.net.URI;
import java.util.regex.Pattern;

/**
 * Configured browser origins must be written in the exact form browsers serialize in the
 * {@code Origin} header: {@code https://} plus a lowercase host and nothing else. Request-time
 * matching is literal, so only the canonical form is accepted; equivalent spellings are rejected
 * instead of normalized, which would let them slip past the exclusions below.
 *
 * <ul>
 *   <li>Lowercase {@code https} scheme only.</li>
 *   <li>The authority is the bare host: no userinfo, no port and no empty-port separator.</li>
 *   <li>No path (not even {@code /}), query or fragment, including empty ones.</li>
 *   <li>Lowercase LDH host labels, without a trailing dot.</li>
 *   <li>Reserved names, applied to the canonical host: {@code localhost}, {@code *.localhost}
 *       (RFC 6761 loopback names), {@code invalid} and {@code *.invalid}.</li>
 *   <li>IP literals are rejected. They cannot form the approved same-site HTTPS pair (A-12); allowing
 *       one would need a separate decision (D-04).</li>
 *   <li>Hosts a browser would parse as IPv4 are rejected even when {@code java.net.URI} sees a name.
 *       The WHATWG URL host parser treats a domain as IPv4 when its last label "ends in a number":
 *       it is all decimal digits (this also covers octal {@code 0177} and shortened forms such as
 *       {@code 127.1}) or a {@code 0x}/{@code 0X} hexadecimal number, {@code 0x} alone included. Such a host
 *       is never accepted, whatever its value or range.</li>
 * </ul>
 * No DNS resolution takes part in the decision; {@code URI} only splits the string.
 */
final class BrowserOrigins {
    private static final Pattern HOST = Pattern.compile(
            "(?=.{1,253}$)[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)*");
    /** WHATWG "ends in a number": decimal digits, or 0x/0X followed by zero or more hex digits. */
    private static final Pattern NUMERIC_LABEL = Pattern.compile("[0-9]+|0[xX][0-9a-fA-F]*");

    private BrowserOrigins() { }

    static boolean isCanonicalHttpsOrigin(String value) {
        if (value == null || value.length() > 8 + 253) return false;
        try {
            URI uri = new URI(value);
            String host = uri.getHost();
            if (!"https".equals(uri.getScheme()) || host == null || !host.equals(uri.getRawAuthority())
                    || !value.equals("https://" + host)) return false;
            if (!HOST.matcher(host).matches() || endsInANumber(host)) return false;
            return !host.equals("localhost") && !host.endsWith(".localhost")
                    && !host.equals("invalid") && !host.endsWith(".invalid");
        } catch (Exception invalid) {
            return false;
        }
    }

    private static boolean endsInANumber(String host) {
        return NUMERIC_LABEL.matcher(host.substring(host.lastIndexOf('.') + 1)).matches();
    }
}
