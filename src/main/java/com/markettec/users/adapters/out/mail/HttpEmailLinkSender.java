package com.markettec.users.adapters.out.mail;

import com.azure.core.credential.TokenCredential;
import com.azure.core.credential.TokenRequestContext;
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
 * Delivers a one-use link through Resend or Azure Communication Services Email over HTTPS.
 * The secret travels in the URL fragment so it never reaches server logs.
 *
 * <p>Every attempt emits one {@code email_delivery_audit} event whose only fields are the channel,
 * the outcome and the provider HTTP status. Recipient, link, secret, key and provider body are never
 * logged, so a silent provider failure is observable without exposing credentials or addresses.
 */
public final class HttpEmailLinkSender implements InstitutionalLinkSender {
    public static final Set<String> CHANNELS = Set.of("login", "proof", "recovery");
    private static final Logger LOG = LoggerFactory.getLogger(HttpEmailLinkSender.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ACS_SCOPE = "https://communication.azure.com//.default";
    private static final String ACS_SEND_PATH = "/emails:send?api-version=2023-03-31";
    private final HttpClient client;
    private final URI endpoint;
    private final String apiKey;
    private final TokenCredential managedIdentity;
    private final String from;
    private final String linkBase;
    private final String subject;
    private final String intro;
    private final String channel;

    /** Production deadline for authentication, connection, headers and body. */
    public static final Duration DEFAULT_DEADLINE = Duration.ofSeconds(10);
    private final Duration deadline;

    public HttpEmailLinkSender(HttpClient client, String endpoint, String apiKey, String from,
                               String linkBase, String subject, String intro, String channel) {
        this(client, endpoint, apiKey, from, linkBase, subject, intro, channel, DEFAULT_DEADLINE);
    }

    HttpEmailLinkSender(HttpClient client, String endpoint, String apiKey, String from, String linkBase,
                        String subject, String intro, String channel, Duration deadline) {
        this(client, httpsUri(endpoint, "mail endpoint"), apiKey, null, from, linkBase, subject, intro, channel,
                deadline);
    }

    public static HttpEmailLinkSender forAcs(HttpClient client, String endpoint, TokenCredential managedIdentity,
                                             String from, String linkBase, String subject, String intro, String channel) {
        URI resource = httpsUri(endpoint, "ACS resource endpoint");
        String host = resource.getHost();
        String suffix = ".communication.azure.com";
        if (!host.endsWith(suffix) || host.length() == suffix.length()
                || (resource.getPort() != -1 && resource.getPort() != 443)
                || (!resource.getRawPath().isEmpty() && !resource.getRawPath().equals("/"))
                || resource.getRawQuery() != null || resource.getRawFragment() != null
                || client.followRedirects() != HttpClient.Redirect.NEVER) {
            throw new IllegalArgumentException("ACS requires its HTTPS resource endpoint and redirects disabled");
        }
        return new HttpEmailLinkSender(client, resource.resolve(ACS_SEND_PATH), null,
                Objects.requireNonNull(managedIdentity), from, linkBase, subject, intro, channel, DEFAULT_DEADLINE);
    }

    private HttpEmailLinkSender(HttpClient client, URI endpoint, String apiKey, TokenCredential managedIdentity,
                                String from, String linkBase, String subject, String intro, String channel,
                                Duration deadline) {
        this.client = Objects.requireNonNull(client);
        this.endpoint = endpoint;
        if (managedIdentity == null && (apiKey == null || apiKey.isBlank()))
            throw new IllegalArgumentException("Mail API key is required");
        if (from == null || from.isBlank())
            throw new IllegalArgumentException("Mail sender is required");
        this.apiKey = apiKey;
        this.managedIdentity = managedIdentity;
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
     * {@code deadline} in total (authentication, headers and body) and cancels it on timeout or interruption; since
     * Java 16 cancelling a {@code sendAsync} future aborts the underlying exchange. The exchange runs
     * on the client's own executor; no thread or task is created here.
     *
     * <p>A timeout after the provider already answered 2xx is reported as {@code timeout} with that
     * status: acceptance is uncertain, so the call fails closed and no second message is sent.
     */
    @Override public void send(Email recipient, String attemptId, String secret) {
        long deadlineNanos = System.nanoTime() + deadline.toNanos();
        String link = linkBase + "#attempt=" + attemptId + "&token=" + secret;
        String text = intro + "\n\n" + link + "\n";
        var observed = new AtomicInteger();
        HttpResponse.BodyHandler<Void> handler = info -> {
            observed.set(info.statusCode());
            return HttpResponse.BodySubscribers.discarding();
        };
        int status;
        try {
            String bearer = bearerToken(deadlineNanos);
            String body = JSON.writeValueAsString(message(recipient.address(), text));
            HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofNanos(remaining(deadlineNanos)))
                    .header("Authorization", "Bearer " + bearer).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
            status = await(client.sendAsync(request, handler), deadlineNanos).statusCode();
        } catch (TimeoutException late) {
            audit("timeout", observed.get());
            throw new EmailDeliveryException(late);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            audit("cancelled", observed.get());
            throw new EmailDeliveryException(interrupted);
        } catch (CancellationException cancelled) {
            audit("cancelled", observed.get());
            throw new EmailDeliveryException(cancelled);
        } catch (ExecutionException failed) {
            audit(failed.getCause() instanceof HttpTimeoutException ? "timeout" : "transport_failed", observed.get());
            throw new EmailDeliveryException();
        } catch (IOException | RuntimeException failure) {
            audit("transport_failed", observed.get());
            throw new EmailDeliveryException();
        }
        if (managedIdentity != null ? status != 202 : status < 200 || status >= 300) {
            audit("provider_rejected", status);
            throw new EmailDeliveryException();
        }
        audit("accepted", status);
    }

    private String bearerToken(long deadlineNanos)
            throws ExecutionException, InterruptedException, TimeoutException, IOException {
        if (managedIdentity == null) return apiKey;
        var token = await(managedIdentity.getToken(new TokenRequestContext().addScopes(ACS_SCOPE)).toFuture(),
                deadlineNanos);
        if (token == null || token.getToken().isBlank()) throw new IOException("Missing managed identity token");
        return token.getToken();
    }

    private Map<String, Object> message(String recipient, String text) {
        if (managedIdentity == null)
            return Map.of("from", from, "to", List.of(recipient), "subject", subject, "text", text);
        return Map.of("senderAddress", from, "recipients", Map.of("to", List.of(Map.of("address", recipient))),
                "content", Map.of("subject", subject, "plainText", text));
    }

    private static <T> T await(CompletableFuture<T> operation, long deadlineNanos)
            throws ExecutionException, InterruptedException, TimeoutException {
        try {
            return operation.get(remaining(deadlineNanos), TimeUnit.NANOSECONDS);
        } catch (TimeoutException | InterruptedException stopped) {
            operation.cancel(true);
            throw stopped;
        }
    }

    private static long remaining(long deadlineNanos) throws TimeoutException {
        long remaining = deadlineNanos - System.nanoTime();
        if (remaining <= 0) throw new TimeoutException();
        return remaining;
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
