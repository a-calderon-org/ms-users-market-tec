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
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Delivers a one-use link through a JSON-over-HTTPS transactional mail API (Bearer key; the
 * request shape {@code from,to[],subject,text} is accepted by common providers). The secret travels
 * in the URL fragment so it never reaches server logs, and is never logged here.
 *
 * <p>Every attempt emits one {@code email_delivery_audit} event whose only fields are the channel,
 * the outcome and the provider HTTP status. Recipient, link, secret, key and provider body are never
 * logged, so a silent provider failure is observable without exposing credentials or addresses.
 */
public final class HttpEmailLinkSender implements InstitutionalLinkSender {
    public static final Set<String> CHANNELS = Set.of("login", "proof", "recovery");
    private static final Logger LOG = LoggerFactory.getLogger(HttpEmailLinkSender.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client;
    private final URI endpoint;
    private final String apiKey;
    private final String from;
    private final String linkBase;
    private final String subject;
    private final String intro;
    private final String channel;

    /** Production deadline for the whole provider exchange: connect, headers and body. */
    public static final Duration DEFAULT_DEADLINE = Duration.ofSeconds(10);
    private final Duration deadline;

    public HttpEmailLinkSender(HttpClient client, String endpoint, String apiKey, String from,
                               String linkBase, String subject, String intro, String channel) {
        this(client, endpoint, apiKey, from, linkBase, subject, intro, channel, DEFAULT_DEADLINE);
    }

    HttpEmailLinkSender(HttpClient client, String endpoint, String apiKey, String from, String linkBase,
                        String subject, String intro, String channel, Duration deadline) {
        this.client = Objects.requireNonNull(client);
        this.endpoint = httpsUri(endpoint, "mail endpoint");
        if (apiKey == null || apiKey.isBlank() || from == null || from.isBlank())
            throw new IllegalArgumentException("Mail API key and sender are required");
        this.apiKey = apiKey;
        this.from = from;
        this.linkBase = httpsUri(linkBase, "link base URL").toString();
        if (this.linkBase.contains("#") || this.linkBase.contains("?"))
            throw new IllegalArgumentException("Link base URL must not carry a query or fragment");
        if (!CHANNELS.contains(channel)) throw new IllegalArgumentException("Unknown mail channel");
        if (deadline == null || deadline.isNegative() || deadline.isZero() || deadline.compareTo(DEFAULT_DEADLINE) > 0)
            throw new IllegalArgumentException("Mail deadline must be positive and at most " + DEFAULT_DEADLINE);
        this.subject = subject;
        this.intro = intro;
        this.channel = channel;
        this.deadline = deadline;
    }

    /**
     * Exactly one provider request per call, never retried here. {@link HttpRequest#timeout} only bounds
     * the wait for response headers, so the caller waits on the exchange future for at most
     * {@code deadline} in total (headers and body) and cancels it on timeout or interruption; since
     * Java 16 cancelling a {@code sendAsync} future aborts the underlying exchange. The exchange runs
     * on the client's own executor; no thread or task is created here.
     *
     * <p>A timeout after the provider already answered 2xx is reported as {@code timeout} with that
     * status: acceptance is uncertain, so the call fails closed and no second message is sent.
     */
    @Override public void send(Email recipient, String attemptId, String secret) {
        String link = linkBase + "#attempt=" + attemptId + "&token=" + secret;
        HttpRequest request;
        try {
            String body = JSON.writeValueAsString(Map.of("from", from, "to", List.of(recipient.address()),
                    "subject", subject, "text", intro + "\n\n" + link + "\n"));
            request = HttpRequest.newBuilder(endpoint).timeout(deadline)
                    .header("Authorization", "Bearer " + apiKey).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
        } catch (IOException failure) {
            audit("transport_failed", 0);
            throw new EmailDeliveryException(failure);
        }
        var observed = new AtomicInteger();
        HttpResponse.BodyHandler<Void> handler = info -> {
            observed.set(info.statusCode());
            return HttpResponse.BodySubscribers.discarding();
        };
        CompletableFuture<HttpResponse<Void>> exchange = client.sendAsync(request, handler);
        int status;
        try {
            status = exchange.get(deadline.toNanos(), TimeUnit.NANOSECONDS).statusCode();
        } catch (TimeoutException late) {
            exchange.cancel(true);
            audit("timeout", observed.get());
            throw new EmailDeliveryException(late);
        } catch (InterruptedException interrupted) {
            exchange.cancel(true);
            Thread.currentThread().interrupt();
            audit("cancelled", observed.get());
            throw new EmailDeliveryException(interrupted);
        } catch (CancellationException cancelled) {
            audit("cancelled", observed.get());
            throw new EmailDeliveryException(cancelled);
        } catch (ExecutionException failed) {
            audit(failed.getCause() instanceof HttpTimeoutException ? "timeout" : "transport_failed", observed.get());
            throw new EmailDeliveryException(failed.getCause());
        }
        if (status < 200 || status >= 300) {
            audit("provider_rejected", status);
            throw new EmailDeliveryException();
        }
        audit("accepted", status);
    }

    /** Provider acceptance only: it does not prove the message reached the mailbox. */
    private void audit(String outcome, int providerStatus) {
        var event = LOG.atInfo().addKeyValue("eventType", "email_delivery")
                .addKeyValue("channel", channel).addKeyValue("outcome", outcome);
        if (providerStatus >= 100 && providerStatus <= 599) event.addKeyValue("providerStatus", providerStatus);
        event.log("email_delivery_audit");
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
