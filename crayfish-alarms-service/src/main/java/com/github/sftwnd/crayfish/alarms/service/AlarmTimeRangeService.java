package com.github.sftwnd.crayfish.alarms.service;

import com.github.sftwnd.crayfish.alarms.timerange.ITimeRange;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.java.Log;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.logging.Level;

/**
 * The service allows you to register alarm clocks for operation according to a schedule and sets them
 * on fire at the right time for the predefined TimeRange
 * @param <M> type of incoming alarm to register
 * @param <R> type of alarm clock
 *
 * P.S. SonarCube: <a href="https://sonarcloud.io/organizations/sftwnd-github/rules?open=java%3AS2274&amp;rule_key=java%3AS2274">java:S2274</a>
 */
@Log
public class AlarmTimeRangeService<M,R> implements IAlarmService<M,R> {

    private static final long DEFAULT_MINIMAL_WAIT_NANOS = Duration.ofMillis(75).toNanos();
    private final ITimeRange<M,R> timeRange;
    private final ConcurrentLinkedQueue<RegistrationRequest> registrationQueue = new ConcurrentLinkedQueue<>();
    private final long minimalWaitNanos;

    /**
     * Construct IAlarmService for just one ITimeRange
     * @param timeRange predefined ITimeRange
     * @param minimalWait on the timeout less than minimalWait spinCount will be used instead of wait
     */
    public AlarmTimeRangeService(
            @NonNull ITimeRange<M, R> timeRange,
            @Nullable Duration minimalWait
    ) {
        this.timeRange = Objects.requireNonNull(timeRange, "AlarmTimeRangeService::new - timeRange is null");
        this.minimalWaitNanos = Optional.ofNullable(minimalWait)
                .map(Duration::toNanos)
                .map(nanos -> Math.max(0L, nanos))
                .orElse(DEFAULT_MINIMAL_WAIT_NANOS);
    }

    /**
     * Add new elements to Service with rejects in CompletionStage result
     * @param elements not null collection of elements to add
     * @return CompletionStage with rejected elements on completion
     */
    @Override
    public CompletionStage<Collection<M>> addElements(@NonNull Collection<M> elements) {
        if (!elements.isEmpty()) {
            RegistrationRequest registrationRequest = registrationRequest(elements);
            if (syncFlag.get()) {
                synchronized (registrationQueue) {
                    if (syncFlag.compareAndSet(true, false)) {
                        registrationQueue.add(registrationRequest);
                        registrationQueue.notifyAll();
                        return registrationRequest.getCompletableFuture().minimalCompletionStage();
                    }
                }
            }
            registrationQueue.add(registrationRequest);
            return registrationRequest.getCompletableFuture().minimalCompletionStage();
        } else {
            CompletableFuture<Collection<M>> result = new CompletableFuture<>();
            result.complete(Collections.emptyList());
            return result.minimalCompletionStage();
        }
    }

    /**
     * The process of igniting alarm clocks at a given moment in time
     * @param consumer Handler for a set of triggered alarms
     */
    @Override
    @SneakyThrows
    public final void process(@NonNull Consumer<Collection<R>> consumer) {
        Objects.requireNonNull(consumer, "AlarmTimeRangeService::process - consumer is null");
        if (!processFlag.compareAndSet(false, true)) {
            throw new IllegalStateException("AlarmTimeRangeService already in process");
        }
        try {
            loopProcess(consumer);
        } catch (InterruptedException itrex) {
            logger.log(Level.WARNING, "AlarmTimeRangeService::process is terminated by cause: {}", itrex.getLocalizedMessage());
            Thread.currentThread().interrupt();
        } finally {
            this.registrationQueue.forEach(RegistrationRequest::reject);
            processFlag.set(false);
        }
    }

    private void loopProcess(Consumer<Collection<R>> consumer) throws InterruptedException {
        while (!timeRange.isComplete()) {
            Optional.of(timeRange.extractFiredElements())
                    .filter(Predicate.not(Collection::isEmpty))
                    .ifPresent(consumer);
            Instant now = Instant.now();
            register(now.plus(timeRange.duration(now)));
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

    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    private class RegistrationRequest {
        private final @Getter CompletableFuture<Collection<M>> completableFuture;
        private final @Getter Collection<M> elements;
        private boolean apply() {
            Collection<M> rejected = timeRange.addElements(elements);
            completableFuture.complete(rejected);
            return elements.size() - rejected.size() > 0;
        }
        private void reject() {
            completableFuture.complete(elements);
        }
    }
    private RegistrationRequest registrationRequest(@NonNull Collection<M> elements) {
        return new RegistrationRequest(
                new CompletableFuture<>(),
                Objects.requireNonNull(elements, "RegistrationRequest::new - elements is null")
        );
    }

}
