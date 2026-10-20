package com.github.sftwnd.crayfish.alarms.service;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

import java.time.Duration;
import java.util.Collection;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The base of the alarm processors: the time offset of the process
 * @param <R> type of fired alarm
 */
public abstract class AbstractAlarmProcessor<R> implements IAlarmProcessor<R> {

    /**
     * Construct the processor without a time offset: the process sets it
     */
    protected AbstractAlarmProcessor() {
        // the time offset is set by process
    }

    @Override
    public void process(@NonNull Consumer<Collection<R>> consumer, @Nullable Supplier<Duration> timeOffset) {
        Objects.requireNonNull(consumer, "AbstractAlarmProcessor::process - consumer is null");
        setTimeOffset(timeOffset);
    }

    /**
     * Change timeOffset in process (used for time correction in process)
     * @param timeOffset value of new timeOffset
     */
    protected final void setTimeOffset(Supplier<Duration> timeOffset) {
        this.timeOffset = timeOffset;
    }

    /**
     * Get current timeOffset value
     * @return timeOffset duration
     */
    protected final Duration getTimeOffset() {
        // no supplier, or a supplier of null: no offset
        Supplier<Duration> supplier = this.timeOffset;
        Duration offset = supplier == null ? null : supplier.get();
        return offset == null ? Duration.ZERO : offset;
    }

    /**
     * Get current timeOffset in nano of seconds
     * @return timeOffset in nano of seconds
     */
    protected final long getTimeOffsetNanos() {
        return getTimeOffset().toNanos();
    }

    private Supplier<Duration> timeOffset;

}