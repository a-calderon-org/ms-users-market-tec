package com.markettec.users.adapters.out.mail;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.markettec.users.application.session.EmailDeliveryException;
import com.markettec.users.application.session.InstitutionalLinkSender;
import com.markettec.users.domain.model.Email;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Delivers a one-use link through a JSON-over-HTTPS transactional mail API (Bearer key; the
 * request shape {@code from,to[],subject,text} is accepted by common providers). The secret travels
 * in the URL fragment so it never reaches server logs, and is never logged here.
 */
public final class HttpEmailLinkSender implements InstitutionalLinkSender {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client;
    private final URI endpoint;
    private final String apiKey;
    private final String from;
    private final String linkBase;
    private final String subject;
    private final String intro;

    public HttpEmailLinkSender(HttpClient client, String endpoint, String apiKey, String from,
                               String linkBase, String subject, String intro) {
        this.client = Objects.requireNonNull(client);
        this.endpoint = httpsUri(endpoint, "mail endpoint");
        if (apiKey == null || apiKey.isBlank() || from == null || from.isBlank())
            throw new IllegalArgumentException("Mail API key and sender are required");
        this.apiKey = apiKey;
        this.from = from;
        this.linkBase = httpsUri(linkBase, "link base URL").toString();
        if (this.linkBase.contains("#") || this.linkBase.contains("?"))
            throw new IllegalArgumentException("Link base URL must not carry a query or fragment");
        this.subject = subject;
        this.intro = intro;
    }

    @Override public void send(Email recipient, String attemptId, String secret) {
        String link = linkBase + "#attempt=" + attemptId + "&token=" + secret;
        try {
            String body = JSON.writeValueAsString(Map.of("from", from, "to", List.of(recipient.address()),
                    "subject", subject, "text", intro + "\n\n" + link + "\n"));
            var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + apiKey).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
            int status = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            if (status < 200 || status >= 300) throw new EmailDeliveryException();
        } catch (IOException failure) {
            throw new EmailDeliveryException(failure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new EmailDeliveryException(interrupted);
        }
    }

    private static URI httpsUri(String value, String name) {
        try {
            URI uri = URI.create(value == null ? "" : value);
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null)
                throw new IllegalArgumentException(name + " must be an absolute HTTPS URL");
            return uri;
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(name + " must be an absolute HTTPS URL");
        }
    }
}
