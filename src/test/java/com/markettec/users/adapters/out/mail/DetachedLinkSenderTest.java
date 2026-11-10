package com.markettec.users.adapters.out.mail;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.markettec.users.application.session.EmailDeliveryException;
import com.markettec.users.domain.model.Email;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import static org.junit.jupiter.api.Assertions.*;

/** The requester's response never waits for, nor learns about, a recovery delivery; drops are observable. */
class DetachedLinkSenderTest {
    private static final Email ANA = new Email("ana@estudiantec.cr");
    private static final String SECRET = "b".repeat(64);
    private static final Duration MAX_AGE = Duration.ofMinutes(2);
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-10T12:00:00Z"));
    private final Clock clock = new Clock() {
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now.get(); }
    };
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(DetachedLinkSender.class);

    @BeforeEach void capture() { logs.start(); logger.addAppender(logs); }
    @AfterEach void release() { logger.detachAppender(logs); }

    private DetachedLinkSender sender(com.markettec.users.application.session.InstitutionalLinkSender delegate, int capacity) {
        return new DetachedLinkSender(delegate, "recovery", capacity, MAX_AGE, clock);
    }

    @Test void returnsBeforeTheProviderAnswers() throws Exception {
        var release = new CountDownLatch(1);
        var delivered = new CountDownLatch(1);
        try (var sender = sender((to, id, secret) -> { await(release); delivered.countDown(); }, 4)) {
            long started = System.nanoTime();
            sender.send(ANA, "a".repeat(32), SECRET);
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1_000, "did not wait for the provider");
            assertEquals(1, delivered.getCount(), "the provider is still blocked");
            release.countDown();
            assertTrue(delivered.await(5, TimeUnit.SECONDS), "the link is still delivered");
        }
        assertTrue(logs.list.isEmpty(), "a normal delivery adds no event here (the delegate audits it)");
    }

    @Test void aProviderFailureNeitherPropagatesNorStopsLaterDeliveries() throws Exception {
        List<String> sent = new CopyOnWriteArrayList<>();
        var second = new CountDownLatch(1);
        try (var sender = sender((to, id, secret) -> {
            if (id.startsWith("1")) throw new EmailDeliveryException();
            sent.add(id);
            second.countDown();
        }, 4)) {
            assertDoesNotThrow(() -> sender.send(ANA, "1".repeat(32), SECRET));
            sender.send(ANA, "2".repeat(32), SECRET);
            assertTrue(second.await(5, TimeUnit.SECONDS));
            assertEquals(List.of("2".repeat(32)), sent);
        }
        assertTrue(logs.list.isEmpty(), "provider failures are audited by the delegate, not twice");
    }

    @Test void aFullQueueDropsInsteadOfBlockingOrFailingAndIsAudited() throws Exception {
        var release = new CountDownLatch(1);
        var started = new CountDownLatch(1);
        List<String> sent = new CopyOnWriteArrayList<>();
        try (var sender = sender((to, id, secret) -> { started.countDown(); await(release); sent.add(id); }, 1)) {
            sender.send(ANA, "1".repeat(32), SECRET); // running
            assertTrue(started.await(5, TimeUnit.SECONDS));
            sender.send(ANA, "2".repeat(32), SECRET); // queued
            assertDoesNotThrow(() -> sender.send(ANA, "3".repeat(32), SECRET)); // dropped
            release.countDown();
        }
        assertEquals(List.of("1".repeat(32), "2".repeat(32)), sent, "close drains what was queued");
        assertEquals(List.of(Map.of("eventType", "email_delivery", "channel", "recovery", "outcome", "dropped_queue_full",
                "count", "1")), events());
    }

    @Test void aLinkThatWaitedTooLongIsDroppedInsteadOfArrivingNearlyExpired() throws Exception {
        var release = new CountDownLatch(1);
        var started = new CountDownLatch(1);
        List<String> sent = new CopyOnWriteArrayList<>();
        try (var sender = sender((to, id, secret) -> { started.countDown(); await(release); sent.add(id); }, 4)) {
            sender.send(ANA, "1".repeat(32), SECRET);
            assertTrue(started.await(5, TimeUnit.SECONDS));
            sender.send(ANA, "2".repeat(32), SECRET); // waits behind a slow provider
            now.set(now.get().plus(MAX_AGE).plusSeconds(1));
            release.countDown();
        }
        assertEquals(List.of("1".repeat(32)), sent);
        assertEquals(List.of(Map.of("eventType", "email_delivery", "channel", "recovery", "outcome", "dropped_stale",
                "queueWaitMs", "121000", "count", "1")), events());
    }

    @Test void sendingAfterShutdownIsDroppedAndAudited() {
        var sender = sender((to, id, secret) -> fail("closed"), 4);
        sender.close();
        assertDoesNotThrow(() -> sender.send(ANA, "1".repeat(32), SECRET));
        assertEquals("dropped_shutdown", events().get(0).get("outcome"));
    }

    @Test void anUnexpectedSenderErrorIsLoggedByTypeOnly() throws Exception {
        var done = new CountDownLatch(1);
        try (var sender = sender((to, id, secret) -> {
            done.countDown();
            throw new IllegalStateException(to.address() + " " + secret);
        }, 4)) {
            sender.send(ANA, "1".repeat(32), SECRET);
            assertTrue(done.await(5, TimeUnit.SECONDS));
        }
        var error = events().stream().filter(e -> "sender_error".equals(e.get("outcome"))).findFirst().orElseThrow();
        assertEquals("IllegalStateException", error.get("errorType"));
    }

    @Test void noEventEverCarriesTheRecipientAttemptOrSecret() throws Exception {
        aFullQueueDropsInsteadOfBlockingOrFailingAndIsAudited();
        anUnexpectedSenderErrorIsLoggedByTypeOnly();
        for (var event : logs.list) {
            String rendered = event.getFormattedMessage() + event.getKeyValuePairs() + event.getThrowableProxy();
            for (String leak : List.of(ANA.address(), "estudiantec", SECRET, "1".repeat(32), "2".repeat(32), "3".repeat(32)))
                assertFalse(rendered.contains(leak), "event leaks " + leak + ": " + rendered);
        }
    }

    @Test void rejectsInvalidBounds() {
        assertThrows(IllegalArgumentException.class, () -> sender((to, id, secret) -> { }, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new DetachedLinkSender((to, id, secret) -> { }, "recovery", 4, Duration.ZERO, clock));
    }

    private List<Map<String, String>> events() {
        return logs.list.stream().map(e -> e.getKeyValuePairs().stream()
                .collect(Collectors.toMap(kv -> kv.key, kv -> String.valueOf(kv.value)))).toList();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
