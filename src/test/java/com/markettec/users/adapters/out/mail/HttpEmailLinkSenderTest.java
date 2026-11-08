package com.markettec.users.adapters.out.mail;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.markettec.users.application.session.EmailDeliveryException;
import com.markettec.users.config.SafeJsonLogFormatter;
import com.markettec.users.domain.model.Email;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Synthetic provider: a loopback-only TLS server written for this test (no real mail, no external
 * network). The test owns every thread and socket it creates and closes them after each case.
 */
class HttpEmailLinkSenderTest {
    private static final String KEY = "provider-key-marker";
    private static final String ATTEMPT = "a".repeat(32);
    private static final String SECRET = "5".repeat(64);
    private static final Email TO = new Email("private-recipient@estudiantec.cr");
    private static final Duration DEADLINE = Duration.ofMillis(1500);
    private static final char[] STORE_PASSWORD = "test-only-keystore".toCharArray();
    private static SSLContext tls;

    private final ObjectMapper json = new ObjectMapper();
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(HttpEmailLinkSender.class);
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final AtomicInteger connections = new AtomicInteger();
    private final List<Socket> open = new CopyOnWriteArrayList<>();
    private SSLServerSocket server;
    private Thread acceptor;
    private HttpClient client;

    interface Behaviour { void respond(OutputStream out, Socket socket) throws Exception; }

    @BeforeAll static void keystore(@TempDir Path dir) throws Exception {
        Path store = dir.resolve("provider.p12");
        String keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
        var process = new ProcessBuilder(keytool, "-genkeypair", "-alias", "provider", "-keyalg", "RSA",
                "-keysize", "2048", "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1",
                "-validity", "2", "-storetype", "PKCS12", "-keystore", store.toString(),
                "-storepass", new String(STORE_PASSWORD), "-keypass", new String(STORE_PASSWORD))
                .redirectErrorStream(true).start();
        process.getInputStream().readAllBytes();
        assertTrue(process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0, "keytool failed");
        var keys = KeyStore.getInstance("PKCS12");
        try (var in = Files.newInputStream(store)) { keys.load(in, STORE_PASSWORD); }
        var km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        km.init(keys, STORE_PASSWORD);
        var tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tm.init(keys);
        tls = SSLContext.getInstance("TLS");
        tls.init(km.getKeyManagers(), tm.getTrustManagers(), null);
    }

    @BeforeEach void capture() {
        logs.start();
        logger.addAppender(logs);
        client = HttpClient.newBuilder().sslContext(tls).version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @AfterEach void release() throws Exception {
        logger.detachAppender(logs);
        client.shutdownNow();
        if (server != null) server.close();
        for (Socket s : open) s.close();
        if (acceptor != null) { acceptor.interrupt(); acceptor.join(5000); }
        assertTrue(acceptor == null || !acceptor.isAlive(), "server thread leaked");
    }

    private void provider(Behaviour behaviour) throws IOException {
        server = (SSLServerSocket) tls.getServerSocketFactory().createServerSocket(0, 8, InetAddress.getLoopbackAddress());
        acceptor = new Thread(() -> {
            while (!server.isClosed()) {
                try (Socket socket = server.accept()) {
                    open.add(socket);
                    connections.incrementAndGet();
                    requests.add(readRequest(socket.getInputStream()));
                    behaviour.respond(socket.getOutputStream(), socket);
                } catch (Exception closedOrAborted) {
                    // Client cancellation or test teardown closes the socket; nothing else to do.
                }
            }
        }, "synthetic-mail-provider");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    private HttpEmailLinkSender sender(String channel) {
        return new HttpEmailLinkSender(client, "https://localhost:" + server.getLocalPort() + "/v1/send", KEY,
                "no-reply@markettec.example", "https://app.markettec.example/auth/email", "Asunto", "Intro",
                channel, DEADLINE);
    }

    private static void write(OutputStream out, String text) throws IOException {
        out.write(text.getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    private static String readRequest(InputStream in) throws IOException {
        var head = new ByteArrayOutputStream();
        int matched = 0;
        while (matched < 4) {
            int b = in.read();
            if (b < 0) throw new IOException("closed");
            head.write(b);
            matched = (b == "\r\n\r\n".charAt(matched)) ? matched + 1 : (b == '\r' ? 1 : 0);
        }
        String headers = head.toString(StandardCharsets.US_ASCII);
        int length = 0;
        for (String line : headers.split("\r\n"))
            if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) length = Integer.parseInt(line.substring(15).trim());
        return headers + new String(in.readNBytes(length), StandardCharsets.UTF_8);
    }

    private List<JsonNode> formatted() throws Exception {
        var formatter = new SafeJsonLogFormatter();
        var out = new ArrayList<JsonNode>();
        for (var event : logs.list) out.add(json.readTree(formatter.format(event)));
        return out;
    }

    /** Elapsed time of a failing send, asserting it is bounded by the deadline plus scheduling slack. */
    private long failsWithin(HttpEmailLinkSender sender) {
        long started = System.nanoTime();
        assertThrows(EmailDeliveryException.class, () -> sender.send(TO, ATTEMPT, SECRET));
        long elapsed = Duration.ofNanos(System.nanoTime() - started).toMillis();
        assertTrue(elapsed < DEADLINE.toMillis() + 1500, "send took " + elapsed + " ms");
        return elapsed;
    }

    private void assertSingleRequestAndNoRetry() throws Exception {
        Thread.sleep(500); // a retry would open a second connection within this window
        assertEquals(1, connections.get(), "adapter retried");
        assertEquals(1, requests.size());
    }

    @Test void complete202IsAcceptedWithSecretOnlyInTheFragment() throws Exception {
        provider((out, s) -> write(out, "HTTP/1.1 202 Accepted\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}"));
        sender("login").send(TO, ATTEMPT, SECRET);
        String request = requests.get(0);
        assertTrue(request.startsWith("POST /v1/send HTTP/1.1\r\n"));
        assertTrue(request.toLowerCase(Locale.ROOT).contains("authorization: bearer " + KEY.toLowerCase(Locale.ROOT)));
        var body = json.readTree(request.substring(request.indexOf("\r\n\r\n") + 4));
        assertEquals(TO.address(), body.path("to").get(0).asText());
        assertTrue(body.path("text").asText().contains(
                "https://app.markettec.example/auth/email#attempt=" + ATTEMPT + "&token=" + SECRET));
        assertFalse(request.substring(0, request.indexOf("\r\n")).contains(SECRET));
        var event = formatted().get(0);
        assertEquals("email_delivery_audit", event.path("message").asText());
        assertEquals("email_delivery", event.path("eventType").asText());
        assertEquals("login", event.path("channel").asText());
        assertEquals("accepted", event.path("outcome").asText());
        assertEquals(202, event.path("providerStatus").asInt());
        assertNoSecrets(event.toString());
        assertSingleRequestAndNoRetry();
    }

    /** Sol 6.1 reproduction: fast 202 headers, body completes well after the deadline. */
    @Test void fast202HeadersWithDelayedBodyAreCutAtTheDeadlineAsUncertainAcceptance() throws Exception {
        provider((out, s) -> {
            write(out, "HTTP/1.1 202 Accepted\r\nContent-Length: 2\r\n\r\n");
            Thread.sleep(DEADLINE.toMillis() + 4000);
            write(out, "{}");
        });
        long elapsed = failsWithin(sender("login"));
        assertTrue(elapsed >= DEADLINE.toMillis() - 100, "returned before the deadline: " + elapsed);
        var event = formatted().get(0);
        assertEquals("timeout", event.path("outcome").asText());
        assertEquals(202, event.path("providerStatus").asInt(), "observed but uncertain acceptance");
        assertNoSecrets(event.toString());
        assertSingleRequestAndNoRetry();
    }

    @Test void bodyThatNeverEndsIsCutAtTheDeadline() throws Exception {
        provider((out, s) -> {
            write(out, "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n");
            while (true) { write(out, "1\r\nx\r\n"); Thread.sleep(100); }
        });
        failsWithin(sender("proof"));
        var event = formatted().get(0);
        assertEquals("timeout", event.path("outcome").asText());
        assertEquals("proof", event.path("channel").asText());
        assertEquals(200, event.path("providerStatus").asInt());
        assertSingleRequestAndNoRetry();
    }

    @Test void silentProviderTimesOutWithoutAStatus() throws Exception {
        provider((out, s) -> Thread.sleep(DEADLINE.toMillis() + 4000));
        failsWithin(sender("login"));
        var event = formatted().get(0);
        assertEquals("timeout", event.path("outcome").asText());
        assertTrue(event.path("providerStatus").isMissingNode());
        assertSingleRequestAndNoRetry();
    }

    @Test void provider5xxIsRejectedWithoutRetry() throws Exception {
        provider((out, s) -> write(out, "HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"));
        failsWithin(sender("proof"));
        var event = formatted().get(0);
        assertEquals("provider_rejected", event.path("outcome").asText());
        assertEquals(503, event.path("providerStatus").asInt());
        assertNoSecrets(event.toString());
        assertSingleRequestAndNoRetry();
    }

    @Test void disconnectWithoutResponseIsATransportFailure() throws Exception {
        provider((out, s) -> s.close());
        var failure = assertThrows(EmailDeliveryException.class, () -> sender("login").send(TO, ATTEMPT, SECRET));
        assertEquals("Email delivery failed", failure.getMessage());
        var event = formatted().get(0);
        assertEquals("transport_failed", event.path("outcome").asText());
        assertNoSecrets(event.toString());
        // The JDK client may itself retry an idempotent request; POST is not idempotent and must not be.
        assertSingleRequestAndNoRetry();
    }

    @Test void interruptionCancelsTheExchangeAndRestoresTheFlag() throws Exception {
        provider((out, s) -> {
            write(out, "HTTP/1.1 202 Accepted\r\nTransfer-Encoding: chunked\r\n\r\n");
            while (true) { write(out, "1\r\nx\r\n"); Thread.sleep(100); }
        });
        Thread caller = Thread.currentThread();
        Thread interrupter = new Thread(() -> {
            try { Thread.sleep(300); } catch (InterruptedException ignored) { return; }
            caller.interrupt();
        });
        interrupter.start();
        try {
            long started = System.nanoTime();
            assertThrows(EmailDeliveryException.class, () -> sender("login").send(TO, ATTEMPT, SECRET));
            assertTrue(Thread.currentThread().isInterrupted());
            assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() < DEADLINE.toMillis());
        } finally {
            Thread.interrupted();
            interrupter.join();
        }
        var event = formatted().get(0);
        assertEquals("cancelled", event.path("outcome").asText());
        assertEquals(202, event.path("providerStatus").asInt());
        assertSingleRequestAndNoRetry();
    }

    @Test void configurationFailsClosed() {
        var mock = mock(HttpClient.class);
        String ok = "https://mail.example.invalid";
        assertThrows(IllegalArgumentException.class, () -> new HttpEmailLinkSender(mock,
                "http://mail.example.invalid", KEY, "f@x", "https://app.example", "s", "i", "login"));
        assertThrows(IllegalArgumentException.class, () -> new HttpEmailLinkSender(mock,
                "https://user:pw@mail.example.invalid", KEY, "f@x", "https://app.example", "s", "i", "login"));
        assertThrows(IllegalArgumentException.class, () -> new HttpEmailLinkSender(mock,
                ok, "", "f@x", "https://app.example", "s", "i", "login"));
        assertThrows(IllegalArgumentException.class, () -> new HttpEmailLinkSender(mock,
                ok, KEY, "f@x", "https://app.example/?next=x", "s", "i", "login"));
        assertThrows(IllegalArgumentException.class, () -> new HttpEmailLinkSender(mock,
                ok, KEY, "f@x", "https://app.example/#x", "s", "i", "login"));
        assertThrows(IllegalArgumentException.class, () -> new HttpEmailLinkSender(mock,
                ok, KEY, "f@x", "https://app.example", "s", "i", "other"));
        for (Duration bad : new Duration[]{null, Duration.ZERO, Duration.ofMillis(-1), Duration.ofSeconds(11)})
            assertThrows(IllegalArgumentException.class, () -> new HttpEmailLinkSender(mock,
                    ok, KEY, "f@x", "https://app.example", "s", "i", "login", bad));
        verifyNoInteractions(mock);
    }

    @Test void formatterDropsUnknownMailFieldsAndValues() throws Exception {
        LoggerFactory.getLogger(HttpEmailLinkSender.class).atInfo()
                .addKeyValue("eventType", "email_delivery").addKeyValue("channel", TO.address())
                .addKeyValue("outcome", SECRET).addKeyValue("providerStatus", 9999)
                .addKeyValue("recipient", TO.address()).log("email_delivery_audit");
        var event = formatted().get(0);
        assertTrue(event.path("channel").isMissingNode());
        assertTrue(event.path("outcome").isMissingNode());
        assertTrue(event.path("providerStatus").isMissingNode());
        assertTrue(event.path("recipient").isMissingNode());
        assertNoSecrets(event.toString());
    }

    private static void assertNoSecrets(String line) {
        for (String marker : List.of(KEY, SECRET, ATTEMPT, TO.address(), "private-recipient", "app.markettec.example"))
            assertFalse(line.contains(marker), "log leaked " + marker);
    }
}
