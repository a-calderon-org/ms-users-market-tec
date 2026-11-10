package com.markettec.users.adapters.out.mail;

import com.markettec.users.application.session.EmailDeliveryException;
import com.markettec.users.application.session.InstitutionalLinkSender;
import com.markettec.users.domain.model.Email;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Hands a link to its delegate off the request thread, so the caller's response time and status never depend
 * on the mail provider. Used for password recovery, where a link is sent only for existing accounts: waiting for
 * the provider (or failing when it fails) would tell an anonymous caller that the account exists.
 *
 * <p>Best effort, in memory, by design (no new messaging infrastructure):
 * <ul>
 *   <li>one worker and a bounded queue: a link that does not fit is dropped ({@code dropped_queue_full});</li>
 *   <li>a link that waited longer than {@code maxAge} is dropped ({@code dropped_stale}) instead of arriving with
 *       little or no validity left;</li>
 *   <li>on shutdown queued links get a short drain; the rest are lost ({@code dropped_shutdown}).</li>
 * </ul>
 * A dropped link is never retried: the requester was already answered 202 and may simply ask again. Every drop
 * emits an {@code email_delivery_audit} event with only the channel, outcome and (where relevant) the wait or
 * count; recipient, attempt id and secret are never logged. Delivery outcomes are audited by the delegate.
 */
public final class DetachedLinkSender implements InstitutionalLinkSender, AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(DetachedLinkSender.class);
    static final Duration DRAIN = Duration.ofSeconds(5);

    private final InstitutionalLinkSender delegate;
    private final String channel;
    private final Duration maxAge;
    private final Clock clock;
    private final ThreadPoolExecutor worker;

    public DetachedLinkSender(InstitutionalLinkSender delegate, String channel, int capacity, Duration maxAge,
                              Clock clock) {
        if (capacity < 1) throw new IllegalArgumentException("Queue capacity must be positive");
        if (maxAge == null || maxAge.isNegative() || maxAge.isZero())
            throw new IllegalArgumentException("Maximum queue age must be positive");
        this.delegate = Objects.requireNonNull(delegate);
        this.channel = Objects.requireNonNull(channel);
        this.maxAge = maxAge;
        this.clock = Objects.requireNonNull(clock);
        this.worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(capacity),
                runnable -> {
                    var thread = new Thread(runnable, "mail-" + channel);
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    @Override public void send(Email recipient, String attemptId, String secret) {
        Objects.requireNonNull(recipient);
        Instant queued = clock.instant();
        try {
            worker.execute(() -> deliver(recipient, attemptId, secret, queued));
        } catch (RejectedExecutionException saturatedOrClosed) {
            audit(worker.isShutdown() ? "dropped_shutdown" : "dropped_queue_full", null, 1, null);
        }
    }

    private void deliver(Email recipient, String attemptId, String secret, Instant queued) {
        long waitedMs = Math.max(0, Duration.between(queued, clock.instant()).toMillis());
        if (waitedMs > maxAge.toMillis()) {
            audit("dropped_stale", waitedMs, 1, null);
            return;
        }
        try {
            delegate.send(recipient, attemptId, secret);
        } catch (RuntimeException failure) {
            // Delivery failures are audited by the delegate; anything else is a defect, logged by type only.
            if (!(failure instanceof EmailDeliveryException))
                audit("sender_error", waitedMs, null, failure.getClass().getSimpleName());
        }
    }

    @Override public void close() {
        worker.shutdown();
        try {
            if (!worker.awaitTermination(DRAIN.toMillis(), TimeUnit.MILLISECONDS)) {
                int lost = worker.shutdownNow().size();
                if (lost > 0) audit("dropped_shutdown", null, lost, null);
            }
        } catch (InterruptedException interrupted) {
            int lost = worker.shutdownNow().size();
            if (lost > 0) audit("dropped_shutdown", null, lost, null);
            Thread.currentThread().interrupt();
        }
    }

    /** Only fixed, non-identifying fields: never the recipient, attempt id, secret or exception message. */
    private void audit(String outcome, Long queueWaitMs, Integer count, String errorType) {
        var event = LOG.atWarn().addKeyValue("eventType", "email_delivery")
                .addKeyValue("channel", channel).addKeyValue("outcome", outcome);
        if (queueWaitMs != null) event = event.addKeyValue("queueWaitMs", queueWaitMs);
        if (count != null) event = event.addKeyValue("count", count);
        if (errorType != null) event = event.addKeyValue("errorType", errorType);
        event.log("email_delivery_audit");
    }
}
