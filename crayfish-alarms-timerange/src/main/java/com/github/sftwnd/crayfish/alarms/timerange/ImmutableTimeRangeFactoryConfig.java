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
final class ImmutableTimeRangeFactoryConfig<M,R> implements ITimeRangeFactoryConfig<M,R> {

    @Getter private final Duration duration;
    @Getter private final Duration interval;
    @Getter private final Duration delay;
    @Getter private final Duration completeTimeout;
    @Getter private final Expectation<M,? extends TemporalAccessor> expectation;
    @Getter private final Comparator<? super M> comparator;
    @Getter private final TimeRange.ResultTransformer<M,R> extractor;

    static <M,R> ITimeRangeFactoryConfig<M,R> fromConfig(@Nonnull ITimeRangeFactoryConfig<M,R> config) {
        return Objects.requireNonNull(config, "ImmutableTimeRangeFactoryConfig::new - config is null") instanceof ImmutableTimeRangeFactoryConfig ? config
             : new ImmutableTimeRangeFactoryConfig<>(
                config.getDuration(), config.getInterval(), config.getDelay(), config.getCompleteTimeout(),
                config.getExpectation(), config.getComparator(), config.getExtractor());
    }

}