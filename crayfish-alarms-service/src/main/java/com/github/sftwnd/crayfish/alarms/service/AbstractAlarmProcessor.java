package com.github.sftwnd.crayfish.alarms.service;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import lombok.extern.java.Log;

import java.time.Duration;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

@Log
public abstract class AbstractAlarmProcessor<R> implements IAlarmProcessor<R> {

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
        return Optional.ofNullable(timeOffset).map(Supplier::get).orElse(Duration.ZERO);
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