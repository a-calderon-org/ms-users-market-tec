package com.markettec.users.application.session;

/** The configured mail provider did not accept the message. Carries no recipient or secret. */
public final class EmailDeliveryException extends RuntimeException {
    public EmailDeliveryException() { super("Email delivery failed"); }
    public EmailDeliveryException(Throwable cause) { super("Email delivery failed", cause); }
}
