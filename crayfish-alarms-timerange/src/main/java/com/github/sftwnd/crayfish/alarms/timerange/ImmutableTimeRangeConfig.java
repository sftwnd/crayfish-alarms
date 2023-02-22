/*
 * Copyright © 2017-2022 Andrey D. Shindarev. All rights reserved.
 * This program is made available under the terms of the BSD 3-Clause License.
 * Contacts: ashindarev@gmail.com
 */
package com.github.sftwnd.crayfish.alarms.timerange;

import com.github.sftwnd.crayfish.common.expectation.Expectation;
import lombok.AllArgsConstructor;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.time.Duration;
import java.time.temporal.TemporalAccessor;
import java.util.Comparator;
import java.util.Objects;

@AllArgsConstructor
final class ImmutableTimeRangeConfig<M,R> implements ITimeRangeConfig<M,R> {

    @Getter private final Duration duration;
    @Getter private final Duration interval;
    @Getter private final Duration delay;
    @Getter private final Duration completeTimeout;
    @Getter private final Expectation<M,? extends TemporalAccessor> expectation;
    @Getter private final TimeRange.ResultTransformer<M,R> extractor;
    @Getter private final Comparator<? super M> comparator;

    static <M,R> ITimeRangeConfig<M,R> fromConfig(@Nonnull ITimeRangeConfig<M,R> config) {
        return Objects.requireNonNull(config, "ImmutableTimeRangeConfig::new - config is null") instanceof ImmutableTimeRangeConfig ? config
             : new ImmutableTimeRangeConfig<>(
                config.getDuration(), config.getInterval(), config.getDelay(), config.getCompleteTimeout(),
                config.getExpectation(), config.getExtractor(), config.getComparator());
    }

}