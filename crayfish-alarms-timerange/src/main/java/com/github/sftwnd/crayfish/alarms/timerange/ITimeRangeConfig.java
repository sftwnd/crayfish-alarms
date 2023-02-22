/*
 * Copyright © 2017-2022 Andrey D. Shindarev. All rights reserved.
 * This program is made available under the terms of the BSD 3-Clause License.
 * Contacts: ashindarev@gmail.com
 */
package com.github.sftwnd.crayfish.alarms.timerange;

import com.github.sftwnd.crayfish.alarms.timerange.ITimeRange.Transformer;
import com.github.sftwnd.crayfish.common.expectation.Expectation;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.Duration;
import java.time.temporal.TemporalAccessor;
import java.util.Comparator;

/**
 *
 * Time Range Configuration
 *
 * @param <M> Incoming alarm description class
 * @param <S> Storage class
 * @param <R> Result alarm class
 */
public interface ITimeRangeConfig<M,S,R> {

    /**
     * The duration of the described interval
     * @return time range duration
     */
    @Nonnull Duration getDuration();

    /**
     * The size of the internal chunk-a division of the interval
     * @return internal chunk size
     */
    @Nonnull Duration getInterval();

    /**
     * Minimum polling delay in ACTIVE status. Allows you to unload the processor, but reduce the accuracy of the event
     * firing approximately (on average) to the delay value.
     * P.S. For negative ones, 'Duration.ZERO' is set, it cannot be larger than the size of the internal chunk: interval
     * @return delay between alarm checks
     */
    @Nonnull Duration getDelay();

    /**
     * Timeout for delayed message delivery.
     * From the moment of the lastInstant, a timeout is maintained for the arrival of new alarms for processing
     * @return delay for completion of empty time region
     */
    @Nonnull Duration getCompleteTimeout();

    /**
     * Constructor for the internal storage object from the incoming element
     * @return storage preserver transformation for incoming alarm description
     */
    @Nonnull Transformer<M,S> getPreserver();

    /**
     * Getting the date from the preserved element
     * @return expectation getter for the stored alarm
     */
    @Nonnull Expectation<S,? extends TemporalAccessor> getExpectation();

    /**
     * Getting result element from internal storage
     * @return usefully data from alarm envelope
     */
    @Nonnull Transformer<S,R> getReducer();
    /**
     * Comparison of two internal elements objects
     * @return compare function for two internal objects
     */
    @Nullable Comparator<? super S> getComparator();

    default @Nonnull ITimeRangeConfig<M,S,R> immutable() {
        return ImmutableTimeRangeConfig.fromConfig(this);
    }

}