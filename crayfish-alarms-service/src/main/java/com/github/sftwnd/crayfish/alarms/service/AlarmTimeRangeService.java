package com.github.sftwnd.crayfish.alarms.service;

import com.github.sftwnd.crayfish.alarms.timerange.ITimeRange;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import lombok.extern.java.Log;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * The service allows you to register alarm clocks for operation according to a schedule and sets them
 * on fire at the right time for the predefined TimeRange
 * @param <M> type of incoming alarm to register
 * @param <R> type of alarm clock
 *
 * P.S. SonarCube: <a href="https://sonarcloud.io/organizations/sftwnd-github/rules?open=java%3AS2274&amp;rule_key=java%3AS2274">java:S2274</a>
 */
@Log
public class AlarmTimeRangeService<M,R> extends AlarmService<M, R> {

    private final ITimeRange<M,R> timeRange;

    /**
     * Construct IAlarmService / IAlarmServiceWithOffset for just one ITimeRange
     * @param timeRange predefined ITimeRange
     * @param minimalWait on the timeout less than minimalWait spinCount will be used instead of wait
     */
    public AlarmTimeRangeService(
            @NonNull ITimeRange<M, R> timeRange,
            @Nullable Duration minimalWait
    ) {
        super(minimalWait);
        this.timeRange = Objects.requireNonNull(timeRange, "AlarmTimeRangeService::new - timeRange is null");
    }

    @Override
    protected boolean isComplete() {
        return this.timeRange.isComplete(Instant.now().plusNanos(this.getTimeOffsetNanos()));
    }

    @Override
    protected void processFiredElements(Consumer<Collection<R>> consumer) {
        Optional.of(timeRange.extractFiredElements(Instant.now().plusNanos(this.getTimeOffsetNanos())))
                .filter(Predicate.not(Collection::isEmpty))
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