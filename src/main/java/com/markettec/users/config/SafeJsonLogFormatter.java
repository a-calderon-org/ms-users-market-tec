package com.markettec.users.config;

import ch.qos.logback.classic.spi.ILoggingEvent;
import org.springframework.boot.json.JsonWriter;
import org.springframework.boot.logging.structured.StructuredLogFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Output allowlist applies during bootstrap too. Never serialize free text or throwable messages. */
public final class SafeJsonLogFormatter implements StructuredLogFormatter<ILoggingEvent> {
    private static final String REQUEST_LOGGER = "com.markettec.users.adapters.in.web.CorrelationIdFilter";
    private static final String ERROR_LOGGER = "com.markettec.users.adapters.in.web.ApiExceptionHandler";
    private static final String REFRESH_LOGGER = "com.markettec.users.application.session.SessionAuthority";
    private static final String REFRESH_HTTP_LOGGER = "com.markettec.users.adapters.in.web.SessionRefreshController";
    private static final String MAIL_LOGGER = "com.markettec.users.adapters.out.mail.HttpEmailLinkSender";
    private static final String SQL_RETRY_LOGGER = "com.markettec.users.adapters.out.sql.SqlStartupRetry";
    private static final Set<String> SQL_RETRY_OUTCOMES = Set.of("retry_scheduled", "recovered", "exhausted");
    private static final Set<String> REFRESH_OUTCOMES = Set.of("rotated", "consumed_confirmed", "proof_invalid", "session_closed");
    private static final Set<String> MAIL_OUTCOMES = Set.of("accepted", "provider_rejected", "transport_failed", "timeout", "cancelled");
    private static final Set<String> MAIL_CHANNELS = Set.of("login", "proof");
    private static final Set<String> METHODS = Set.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "TRACE", "CONNECT");
    private static final JsonWriter<Map<String, Object>> JSON = JsonWriter.<Map<String, Object>>standard().withNewLineAtEnd();

    @Override
    public String format(ILoggingEvent event) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("@timestamp", event.getInstant().toString());
        fields.put("level", event.getLevel().toString());
        fields.put("logger_name", event.getLoggerName());
        boolean request = REQUEST_LOGGER.equals(event.getLoggerName()) && "request_completed".equals(event.getMessage());
        boolean failure = ERROR_LOGGER.equals(event.getLoggerName()) && "request_failed".equals(event.getMessage());
        boolean refresh = (REFRESH_LOGGER.equals(event.getLoggerName()) || REFRESH_HTTP_LOGGER.equals(event.getLoggerName()))
                && "refresh_audit".equals(event.getMessage());
        boolean mail = MAIL_LOGGER.equals(event.getLoggerName()) && "email_delivery_audit".equals(event.getMessage());
        boolean sqlRetry = SQL_RETRY_LOGGER.equals(event.getLoggerName()) && "sql_startup_retry".equals(event.getMessage());
        fields.put("message", request ? "request_completed" : failure ? "request_failed" : refresh ? "refresh_audit"
                : mail ? "email_delivery_audit" : sqlRetry ? "sql_startup_retry" : "runtime_event");
        String correlation = event.getMDCPropertyMap().get("correlationId");
        if (correlation != null && correlation.matches("[A-Za-z0-9._-]{1,128}")) fields.put("correlationId", correlation);
        if (event.getThrowableProxy() != null) fields.put("exception_type", event.getThrowableProxy().getClassName());
        if (request && event.getKeyValuePairs() != null) {
            for (var pair : event.getKeyValuePairs()) {
                if ("method".equals(pair.key) && pair.value instanceof String method && METHODS.contains(method)) fields.put("method", method);
                if ("route".equals(pair.key) && pair.value instanceof String route && route.matches("[A-Za-z0-9_/{*}.:-]{1,160}")) fields.put("route", route);
                if ("status".equals(pair.key) && pair.value instanceof Integer status && status >= 100 && status <= 599) fields.put("status", status);
                if ("durationMs".equals(pair.key) && pair.value instanceof Long duration && duration >= 0) fields.put("durationMs", duration);
            }
        }
        if (refresh && event.getKeyValuePairs() != null) {
            for (var pair : event.getKeyValuePairs()) {
                if ("eventType".equals(pair.key) && "session_refresh".equals(pair.value)) fields.put("eventType", "session_refresh");
                if ("outcome".equals(pair.key) && pair.value instanceof String outcome && REFRESH_OUTCOMES.contains(outcome)) fields.put("outcome", outcome);
                if ("policyVersion".equals(pair.key) && "C-1".equals(pair.value)) fields.put("policyVersion", "C-1");
                if ("sessionRef".equals(pair.key) && pair.value instanceof String ref && ref.matches("[0-9a-f]{16}")) fields.put("sessionRef", ref);
            }
        }
        if (mail && event.getKeyValuePairs() != null) {
            for (var pair : event.getKeyValuePairs()) {
                if ("eventType".equals(pair.key) && "email_delivery".equals(pair.value)) fields.put("eventType", "email_delivery");
                if ("channel".equals(pair.key) && pair.value instanceof String c && MAIL_CHANNELS.contains(c)) fields.put("channel", c);
                if ("outcome".equals(pair.key) && pair.value instanceof String o && MAIL_OUTCOMES.contains(o)) fields.put("outcome", o);
                if ("providerStatus".equals(pair.key) && pair.value instanceof Integer s && s >= 100 && s <= 599) fields.put("providerStatus", s);
            }
        }
        if (sqlRetry && event.getKeyValuePairs() != null) {
            for (var pair : event.getKeyValuePairs()) {
                if ("outcome".equals(pair.key) && pair.value instanceof String o && SQL_RETRY_OUTCOMES.contains(o)) fields.put("outcome", o);
                if ("attempt".equals(pair.key) && pair.value instanceof Integer a && a >= 1 && a <= 100) fields.put("attempt", a);
                if ("sqlState".equals(pair.key) && pair.value instanceof String s && s.matches("[0-9A-Z]{5}")) fields.put("sqlState", s);
                if ("vendorCode".equals(pair.key) && pair.value instanceof Integer c && c >= 0 && c <= 999999) fields.put("vendorCode", c);
                if ("delayMs".equals(pair.key) && pair.value instanceof Long d && d >= 0) fields.put("delayMs", d);
                if ("elapsedMs".equals(pair.key) && pair.value instanceof Long e && e >= 0) fields.put("elapsedMs", e);
            }
        }
        return JSON.writeToString(fields);
    }
}
