package com.github.sftwnd.crayfish.alarms.service;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The service allows you to register alarm clocks for operation according to a schedule and sets them
 * on fire at the right time for the predefined TimeRange
 * @param <M> type of incoming alarm to register
 * @param <R> type of alarm clock
 *
 * P.S. SonarCube: <a href="https://sonarcloud.io/organizations/sftwnd-github/rules?open=java%3AS2274&amp;rule_key=java%3AS2274">java:S2274</a>
 */
public abstract class AlarmService<M,R> extends AbstractAlarmProcessor<R> implements IAlarmService<M,R> {

    private static final Logger logger = Logger.getLogger(AlarmService.class.getName());
    // no spin by default: the process waits for every alarm, unless a minimal wait is given
    private static final long DEFAULT_MINIMAL_WAIT_NANOS = 0L;
    private final ConcurrentLinkedQueue<RegistrationRequest> registrationQueue = new ConcurrentLinkedQueue<>();
    private final long minimalWaitNanos;

    /**
     * Construct IAlarmService
     * @param minimalWait on the timeout less than minimalWait spinCount will be used instead of wait; null for none
     */
    protected AlarmService(@Nullable Duration minimalWait) {
        this.minimalWaitNanos = Optional.ofNullable(minimalWait)
                .map(Duration::toNanos)
                .map(nanos -> Math.max(0L, nanos))
                .orElse(DEFAULT_MINIMAL_WAIT_NANOS);
    }

    /**
     * Add new elements to Service with rejects in CompletionStage result.
     * After the end of the process, until it is started again, all the elements are rejected at once.
     * @param elements not null collection of elements to add
     * @return CompletionStage with rejected elements on completion, or completed exceptionally with the exception of the
     * registration of the elements
     */
    @Override
    public CompletionStage<Collection<M>> addElements(@NonNull Collection<M> elements) {
        if (!elements.isEmpty()) {
            RegistrationRequest registrationRequest = registrationRequest(elements);
            // queued before the flag of the waiting process is read: a process that sets the flag later finds the
            // request when it polls the queue under the lock, and a process that waits already is notified under it
            registrationQueue.add(registrationRequest);
            if (stopped) {
                // the process is over: the request is rejected, unless the process has been started again and has
                // taken it; a process that ends after the read of the flag rejects the request itself
                if (registrationQueue.remove(registrationRequest)) {
                    registrationRequest.reject();
                }
            } else if (syncFlag.get()) {
                synchronized (registrationQueue) {
                    registrationQueue.notifyAll();
                }
            }
            return registrationRequest.getCompletionStage();
        } else {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
    }

    /**
     * The process of igniting alarm clocks at a given moment in time.
     * At its end, the requests that the process has not applied are rejected.
     * @param consumer Handler for a set of triggered alarms
     */
    @Override
    public final void process(@NonNull Consumer<Collection<R>> consumer, @Nullable Supplier<Duration> timeOffset) {
        // checked before the flag is set: a null consumer leaves the service usable
        Objects.requireNonNull(consumer, "AlarmService::process - consumer is null");
        if (!processFlag.compareAndSet(false, true)) {
            throw new IllegalStateException("AlarmTimeRangeService already in process");
        }
        this.stopped = false;
        super.process(consumer, timeOffset);
        try {
            processLoop(consumer);
        } catch (InterruptedException itrex) {
            // an interrupt is the normal stop of the process before the end of its range: no warning
            logger.log(Level.FINE, "AlarmService::process is terminated by cause: {0}", Optional.ofNullable(itrex.getLocalizedMessage()).orElseGet(() -> String.valueOf(itrex)));
            Thread.currentThread().interrupt();
        } finally {
            // set before the queue is polled: a request queued before it is rejected here, a later one at once
            this.stopped = true;
            for (RegistrationRequest request = this.registrationQueue.poll(); request != null; request = this.registrationQueue.poll()) {
                request.reject();
            }
            processFlag.set(false);
        }
    }

    /**
     * Check for completion of processing of all alarms and receptions of new ones
     * @return true if service has to be completed
     */
    protected abstract boolean isComplete();

    /**
     * Ignition of alarms that have worked at the current moment
     * @param consumer Handler for a set of triggered alarms
     */
    protected abstract void processFiredElements(Consumer<Collection<R>> consumer);

    /**
     * The interval from the specified time until the next alarm goes off
     * @param fromInstant Point in time from which to count
     * @return interval to the next alarm goes off
     */
    protected abstract Duration durationToFirstAlarm(Instant fromInstant);

    /**
     * Register new elements with rejects in CompletionStage result
     * @param elements not null collection of elements to add
     * @return CompletionStage with rejected elements on completion
     */
    protected abstract Collection<M> registerElements(@NonNull Collection<M> elements);

    private void processLoop(Consumer<Collection<R>> consumer) throws InterruptedException {
        while (!isComplete()) {
            processFiredElements(consumer);
            Instant now = Instant.now();
            register(now.plus(durationToFirstAlarm(now.plusNanos(this.getTimeOffsetNanos()))));
        }
    }

    private final AtomicBoolean syncFlag = new AtomicBoolean(false);

    @SuppressWarnings("java:S2274")
    private @Nullable RegistrationRequest syncNext(Instant until) throws InterruptedException {
        RegistrationRequest next = this.registrationQueue.poll();
        if (next == null) {
            boolean needNext = false;
            synchronized (this.registrationQueue) {
                syncFlag.compareAndSet(false, true);
                try {
                    next = this.registrationQueue.poll();
                    if (next == null) {
                        long waitNanos = Duration.between(Instant.now(), until).toNanos();
                        if (waitNanos > this.minimalWaitNanos) {
                            // java:S2274
                            this.registrationQueue.wait(waitNanos / 1000000, (int) (waitNanos % 1000000));
                        }
                        needNext = waitNanos > 0;
                    }
                } finally {
                    syncFlag.set(false);
                }
            }
            if (needNext) {
                next = this.registrationQueue.poll();
            }
        }
        return next;
    }

    private void register(Instant until) throws InterruptedException {
        Instant instant = Instant.MIN;
        while (
            instant.isBefore(until) &&
            !Optional.ofNullable(syncNext(until)).map(RegistrationRequest::apply).orElse(false)
        ) {
             instant = Instant.now();
        }
    }

    private final AtomicBoolean processFlag = new AtomicBoolean(false);
    // The process is over, and has not been started again
    private volatile boolean stopped;

    private class RegistrationRequest {
        private final CompletableFuture<Collection<M>> completableFuture;
        private final Collection<M> elements;
        private RegistrationRequest(CompletableFuture<Collection<M>> completableFuture, Collection<M> elements) {
            this.completableFuture = completableFuture;
            this.elements = elements;
        }
        // A stage that depends on the future of the request: the caller cannot complete the request with it
        private CompletionStage<Collection<M>> getCompletionStage() {
            return completableFuture.thenApply(Function.identity());
        }
        private boolean apply() {
            try {
                Collection<M> rejected = registerElements(elements);
                completableFuture.complete(rejected);
                return elements.size() - rejected.size() > 0;
            } catch (RuntimeException exception) {
                // the request gets the exception, and the process goes on; some of the elements may have been
                // registered, so the process looks for its next alarm anew
                completableFuture.completeExceptionally(exception);
                return true;
            }
        }
        private void reject() {
            completableFuture.complete(elements);
        }
    }
    private RegistrationRequest registrationRequest(@NonNull Collection<M> elements) {
        return new RegistrationRequest(
                new CompletableFuture<>(),
                // a copy: the caller may change its collection before the process takes the request
                new ArrayList<>(Objects.requireNonNull(elements, "RegistrationRequest::new - elements is null"))
        );
    }

}
