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
    private static final Set<String> REFRESH_OUTCOMES = Set.of("rotated", "consumed_confirmed", "proof_invalid", "session_closed");
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
        fields.put("message", request ? "request_completed" : failure ? "request_failed" : refresh ? "refresh_audit" : "runtime_event");
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
        return JSON.writeToString(fields);
    }
}
