/*
 * Copyright © 2017-2022 Andrey D. Shindarev. All rights reserved.
 * This program is made available under the terms of the BSD 3-Clause License.
 * Contacts: ashindarev@gmail.com
 */
package com.github.sftwnd.crayfish.alarms.timerange;

import com.github.sftwnd.crayfish.common.expectation.Expectation;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.Setter;

import javax.annotation.Nonnull;
import java.time.Duration;
import java.time.temporal.TemporalAccessor;
import java.util.Comparator;

@NoArgsConstructor
@AllArgsConstructor
public final class TimeRangeConfig<M,R> implements ITimeRangeConfig<M,R> {

    @Getter @Setter @NonNull @Nonnull Duration duration;
    // The size of the internal chunk-a division of the interval
    @Getter @Setter @NonNull @Nonnull Duration interval;
    // Minimum polling delay in ACTIVE status. Allows you to unload the processor, but reduce the accuracy of the event
    // firing approximately (on average) to the delay value.
    // P.S. For negative ones, Duration.ZERO is set, it cannot be larger than the size of the internal chunk: interval
    @Getter @Setter @NonNull @Nonnull Duration delay;
    // Timeout for delayed message delivery.
    // From the moment of the lastInstant, a timeout is maintained for the arrival of messages for processing
    @Getter @Setter @NonNull @Nonnull Duration completeTimeout;
    // Getting the date from the logged message
    @Getter @Setter @NonNull @Nonnull Expectation<M,? extends TemporalAccessor> expectation;
    // Getting result element from registered
    @Getter @Setter @NonNull @Nonnull TimeRange.ResultTransformer<M,R> extractor;
    // Comparison of two registered objects
    @Getter @Setter Comparator<? super M> comparator;

}