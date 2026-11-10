package com.markettec.users.adapters.out.mail;

import com.markettec.users.application.session.EmailDeliveryException;
import com.markettec.users.domain.model.Email;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** The requester's response never waits for, nor learns about, a recovery delivery. */
class DetachedLinkSenderTest {
    private static final Email ANA = new Email("ana@estudiantec.cr");

    @Test void returnsBeforeTheProviderAnswers() throws Exception {
        var release = new CountDownLatch(1);
        var delivered = new CountDownLatch(1);
        try (var sender = new DetachedLinkSender((to, id, secret) -> {
            await(release);
            delivered.countDown();
        }, "recovery", 4)) {
            long started = System.nanoTime();
            sender.send(ANA, "a".repeat(32), "b".repeat(64));
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1_000, "did not wait for the provider");
            assertEquals(1, delivered.getCount(), "the provider is still blocked");
            release.countDown();
            assertTrue(delivered.await(5, TimeUnit.SECONDS), "the link is still delivered");
        }
    }

    @Test void aProviderFailureNeitherPropagatesNorStopsLaterDeliveries() throws Exception {
        List<String> sent = new CopyOnWriteArrayList<>();
        var second = new CountDownLatch(1);
        try (var sender = new DetachedLinkSender((to, id, secret) -> {
            if (id.startsWith("1")) throw new EmailDeliveryException();
            sent.add(id);
            second.countDown();
        }, "recovery", 4)) {
            assertDoesNotThrow(() -> sender.send(ANA, "1".repeat(32), "b".repeat(64)));
            sender.send(ANA, "2".repeat(32), "b".repeat(64));
            assertTrue(second.await(5, TimeUnit.SECONDS));
            assertEquals(List.of("2".repeat(32)), sent);
        }
    }

    @Test void aFullQueueDropsInsteadOfBlockingOrFailing() throws Exception {
        var release = new CountDownLatch(1);
        var started = new CountDownLatch(1);
        List<String> sent = new CopyOnWriteArrayList<>();
        try (var sender = new DetachedLinkSender((to, id, secret) -> {
            started.countDown();
            await(release);
            sent.add(id);
        }, "recovery", 1)) {
            sender.send(ANA, "1".repeat(32), "b".repeat(64)); // running
            assertTrue(started.await(5, TimeUnit.SECONDS));
            sender.send(ANA, "2".repeat(32), "b".repeat(64)); // queued
            assertDoesNotThrow(() -> sender.send(ANA, "3".repeat(32), "b".repeat(64))); // dropped
            release.countDown();
        }
        assertEquals(List.of("1".repeat(32), "2".repeat(32)), sent, "close drains what was queued");
    }

    @Test void rejectsAnEmptyQueue() {
        assertThrows(IllegalArgumentException.class, () -> new DetachedLinkSender((to, id, secret) -> { }, "recovery", 0));
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
