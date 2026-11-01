package com.markettec.users.domain.model;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** ASCII addr-spec subset. No provider-specific aliasing or local-part case folding. */
public final class Email {
    private static final Pattern LOCAL = Pattern.compile("[A-Za-z0-9!#$%&'*+/=?^_`{|}~.-]+");
    private static final Pattern LABEL = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?");
    private final String address;
    private final String normalized;

    public Email(String address) {
        if (address == null || address.length() > 254 || !address.equals(address.strip())) {
            throw new IllegalArgumentException("Invalid email address");
        }
        int at = address.indexOf('@');
        if (at < 1 || at != address.lastIndexOf('@') || at > 64) {
            throw new IllegalArgumentException("Invalid email address");
        }
        String local = address.substring(0, at);
        String domain = address.substring(at + 1);
        String[] labels = domain.split("\\.", -1);
        if (!LOCAL.matcher(local).matches() || local.startsWith(".") || local.endsWith(".")
                || local.contains("..") || domain.length() > 253 || labels.length < 2) {
            throw new IllegalArgumentException("Invalid email address");
        }
        for (String label : labels) {
            if (!LABEL.matcher(label).matches()) {
                throw new IllegalArgumentException("Invalid email domain");
            }
        }
        this.address = address;
        this.normalized = local + "@" + domain.toLowerCase(Locale.ROOT);
    }

    /** Original spelling, including the case of a verified address. */
    public String address() { return address; }

    /** Domain case only; dots, plus aliases and local-part case are preserved. */
    public String normalized() { return normalized; }

    @Override
    public boolean equals(Object other) {
        return other instanceof Email email && normalized.equals(email.normalized);
    }

    @Override
    public int hashCode() { return Objects.hash(normalized); }
}
