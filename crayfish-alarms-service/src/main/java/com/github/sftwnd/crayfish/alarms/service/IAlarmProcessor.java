package com.github.sftwnd.crayfish.alarms.service;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

import java.time.Duration;
import java.util.Collection;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

public interface IAlarmProcessor<R> {

    /**
     * The process of igniting alarm clocks at a given moment in time with defined offset
     * @param consumer Handler for a set of triggered alarms
     * @param timeOffset time offset supplier
     */
    void process(@NonNull Consumer<Collection<R>> consumer, @Nullable Supplier<Duration> timeOffset);

    /**
     * The process of firing alarms at a given moment in time with defined offset
     * with an individual call to the handler for each triggered alarm
     * @param consumer Handler for a triggered alarm
     * @param timeOffset time offset supplier
     */
    default void singleProcess(@NonNull Consumer<R> consumer, @Nullable Supplier<Duration> timeOffset) {
        Objects.requireNonNull(consumer, "IAlarmProcessor::singleProcess - consumer is null");
        process(collection -> collection.forEach(consumer), timeOffset);
    }

    /**
     * The process of igniting alarm clocks at a given moment in time with defined offset
     * @param consumer Handler for a set of triggered alarms
     * @param timeOffset time offset duration
     */
    default void process(@NonNull Consumer<Collection<R>> consumer, @Nullable Duration timeOffset) {
        process(consumer, () -> timeOffset);
    }

    /**
     * The process of firing alarms at a given moment in time with defined offset
     * with an individual call to the handler for each triggered alarm
     * @param consumer Handler for a triggered alarm
     * @param timeOffset time offset duration
     */
    default void singleProcess(@NonNull Consumer<R> consumer, @Nullable Duration timeOffset) {
        singleProcess(consumer, () -> timeOffset);
    }

    /**
     * The process of igniting alarm clocks at a given moment in time
     * @param consumer Handler for a set of triggered alarms
     */
    default void process(@NonNull Consumer<Collection<R>> consumer) {
        process(consumer, Duration.ZERO);
    }

    /**
     * The process of firing alarms at a given moment in time
     * with an individual call to the handler for each triggered alarm
     * @param consumer Handler for a triggered alarm
     */
    default void singleProcess(@NonNull Consumer<R> consumer) {
        singleProcess(consumer, Duration.ZERO);
    }

}