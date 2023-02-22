/*
 * Copyright © 2017-2022 Andrey D. Shindarev. All rights reserved.
 * This program is made available under the terms of the BSD 3-Clause License.
 * Contacts: ashindarev@gmail.com
 */
package com.github.sftwnd.crayfish.alarms.timerange;

import com.github.sftwnd.crayfish.common.expectation.Expectation;

import java.time.Duration;
import java.time.temporal.TemporalAccessor;
import java.util.Comparator;

public interface ITimeRangeConfig<M,R> {

    /**
     * The duration of the described interval
     * @return time range duration
     */
    Duration getDuration();

    /**
     * The size of the internal chunk-a division of the interval
     * @return internal chunk size
     */
    Duration getInterval();

    /**
     * Minimum polling delay in ACTIVE status. Allows you to unload the processor, but reduce the accuracy of the event
     * firing approximately (on average) to the delay value.
     * P.S. For negative ones, 'Duration.ZERO' is set, it cannot be larger than the size of the internal chunk: interval
     * @return delay between alarm checks
     */
    Duration getDelay();

    /**
     * Timeout for delayed message delivery.
     * From the moment of the lastInstant, a timeout is maintained for the arrival of new alarms for processing
     * @return delay for completion of empty time region
     */
    Duration getCompleteTimeout();

    /**
     * Getting the date from the logged message
     * @return expectation getter for alarm
     */
    Expectation<M,? extends TemporalAccessor> getExpectation();

    /**
     * Comparison of two registered objects
     * @return compare function for two alarm objects
     */
    Comparator<? super M> getComparator();
    /**
     * Getting result element from registered
     * @return usefully data from alarm envelope
     */
    TimeRange.ResultTransformer<M,R> getExtractor();

}