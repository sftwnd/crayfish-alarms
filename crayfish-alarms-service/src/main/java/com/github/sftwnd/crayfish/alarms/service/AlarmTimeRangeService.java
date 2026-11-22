package com.github.sftwnd.crayfish.alarms.service;

import com.github.sftwnd.crayfish.alarms.timerange.ITimeRange;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The service allows you to register alarm clocks for operation according to a schedule and sets them
 * on fire at the right time for the predefined TimeRange
 * @param <M> type of incoming alarm to register
 * @param <R> type of alarm clock
 *
 * P.S. SonarCube: <a href="https://sonarcloud.io/organizations/sftwnd-github/rules?open=java%3AS2274&amp;rule_key=java%3AS2274">java:S2274</a>
 */
public class AlarmTimeRangeService<M,R> extends AlarmService<M, R> {

    private final ITimeRange<M,R> timeRange;

    /**
     * Construct IAlarmService for just one ITimeRange, with the default step of a wake-up, a millisecond
     * @param timeRange predefined ITimeRange
     * @param minimalWait a wait shorter than minimalWait is a busy loop instead of a wait; null or zero, the default,
     *                    for none
     */
    public AlarmTimeRangeService(
            @NonNull ITimeRange<M, R> timeRange,
            @Nullable Duration minimalWait
    ) {
        this(timeRange, minimalWait, null);
    }

    /**
     * Construct IAlarmService for just one ITimeRange
     * @param timeRange predefined ITimeRange
     * @param minimalWait a wait shorter than minimalWait is a busy loop instead of a wait; null or zero, the default,
     *                    for none
     * @param wakeUpStep the step of a wake-up: the timeout of a wait for the next alarm is rounded up to a multiple of
     *                   it. Null, the default, for a millisecond, the rounding of 1.0.5; zero for precise waits, which
     *                   fire the alarms closer to their moments at the cost of more wake-ups of the process, up to twice
     *                   its CPU; a coarser step leaves more alarms to an extraction. A negative step is zero
     * @since 1.1.0
     */
    public AlarmTimeRangeService(
            @NonNull ITimeRange<M, R> timeRange,
            @Nullable Duration minimalWait,
            @Nullable Duration wakeUpStep
    ) {
        super(minimalWait, wakeUpStep);
        this.timeRange = Objects.requireNonNull(timeRange, "AlarmTimeRangeService::new - timeRange is null");
    }

    @Override
    protected boolean isComplete() {
        return this.timeRange.isComplete(Instant.now().plusNanos(this.getTimeOffsetNanos()));
    }

    @Override
    protected void processFiredElements(Consumer<Collection<R>> consumer) {
        Optional.of(timeRange.extractFiredElements(Instant.now().plusNanos(this.getTimeOffsetNanos())))
                .filter(fired -> !fired.isEmpty())
                .ifPresent(consumer);
    }

    @Override
    protected Duration durationToFirstAlarm(Instant fromInstant) {
        return this.timeRange.duration(fromInstant);
    }

    @Override
    protected Collection<M> registerElements(@NonNull Collection<M> elements) {
        return this.timeRange.addElements(elements);
    }

}