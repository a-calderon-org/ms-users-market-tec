package com.markettec.users.adapters.out.mail;

import com.markettec.users.application.session.InstitutionalLinkSender;
import com.markettec.users.domain.model.Email;
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
 * <p>One worker and a bounded queue: a burst beyond {@code capacity} is dropped (and logged without recipient or
 * secret) instead of growing memory. Delivery outcomes are audited by the delegate; nothing propagates back.
 */
public final class DetachedLinkSender implements InstitutionalLinkSender, AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(DetachedLinkSender.class);

    private final InstitutionalLinkSender delegate;
    private final String channel;
    private final ThreadPoolExecutor worker;

    public DetachedLinkSender(InstitutionalLinkSender delegate, String channel, int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("Queue capacity must be positive");
        this.delegate = Objects.requireNonNull(delegate);
        this.channel = Objects.requireNonNull(channel);
        this.worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(capacity),
                runnable -> {
                    var thread = new Thread(runnable, "mail-" + channel);
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    @Override public void send(Email recipient, String attemptId, String secret) {
        Objects.requireNonNull(recipient);
        try {
            worker.execute(() -> {
                try {
                    delegate.send(recipient, attemptId, secret);
                } catch (RuntimeException undelivered) {
                    // The delegate already audited the outcome; the requester was answered long ago.
                }
            });
        } catch (RejectedExecutionException saturated) {
            LOG.warn("event=email_delivery_dropped channel={} reason=queue_full", channel);
        }
    }

    @Override public void close() {
        worker.shutdown();
        try {
            if (!worker.awaitTermination(5, TimeUnit.SECONDS)) worker.shutdownNow();
        } catch (InterruptedException interrupted) {
            worker.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
