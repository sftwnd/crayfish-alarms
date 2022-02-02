/*
 * Copyright © 2017-2022 Andrey D. Shindarev. All rights reserved.
 * This program is made available under the terms of the BSD 3-Clause License.
 * Contacts: ashindarev@gmail.com
 */
package com.github.sftwnd.crayfish.alarms.timerange;

import com.github.sftwnd.crayfish.common.expectation.Expectation;
import com.github.sftwnd.crayfish.common.expectation.Expected;
import com.github.sftwnd.crayfish.common.expectation.ExpectedPackage;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.Duration;
import java.time.temporal.TemporalAccessor;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

import static java.util.Optional.ofNullable;

public final class TimeRangeConfig<M,R> {

    // The duration of the described interval
    @Getter final Duration duration;
    // The size of the internal chunk-a division of the interval
    @Getter final Duration interval;
    // Minimum polling delay in ACTIVE status. Allows you to unload the processor, but reduce the accuracy of the event
    // firing approximately (on average) to the delay value.
    // P.S.> For negative ones, Duration.ZERO is set, it cannot be larger than the size of the internal chunk: interval
    @Getter final Duration delay;
    // Timeout for delayed message delivery.
    // From the moment of the lastInstant, a timeout is maintained for the arrival of messages for processing
    @Getter final Duration completeTimeout;
    // Getting the date from the logged message
    @Getter final Expectation<M,? extends TemporalAccessor> expectation;
    // Comparison of two registered objects
    @Getter final Comparator<? super M> comparator;
    // Getting result element from registered
    @Getter final TimeRangeHolder.ResultTransformer<M,R> extractor;

    @SuppressWarnings("java:S107")
    TimeRangeConfig(
            @Nonnull  Duration duration,
            @Nonnull  Duration interval,
            @Nullable Duration delay,
            @Nonnull  Duration completeTimeout,
            @Nonnull  Expectation<M,? extends TemporalAccessor> expectation,
            @Nullable Comparator<? super M> comparator,
            @Nonnull  TimeRangeHolder.ResultTransformer<M,R> extractor
    ) {
        Objects.requireNonNull(duration, "TimeRangeConfig::new - duration is null");
        Objects.requireNonNull(interval, "TimeRangeConfig::new - interval is null");
        Objects.requireNonNull(completeTimeout, "TimeRangeConfig::new - completeTimeout is null");
        Objects.requireNonNull(expectation, "TimeRangeConfig::new - expectation is null");
        Objects.requireNonNull(extractor, "TimeRangeConfig::new - extractor is null");
        if (Duration.ZERO.equals(duration)) throw new IllegalArgumentException("TimeRangeConfig::new - Invalid duration: " + duration);
        this.duration = duration;
        this.interval = Optional.of(interval).filter(Predicate.not(Duration::isNegative)).filter(iv -> iv.compareTo(duration.abs()) <= 0).orElse(duration.abs());
        this.delay = ofNullable(delay)
                .filter(Predicate.not(Duration::isNegative))
                .map(d -> d.compareTo(this.interval) > 0 ? this.interval : d)
                .orElse(Duration.ZERO);
        this.completeTimeout = completeTimeout.abs();
        this.expectation = expectation;
        this.comparator = comparator;
        this.extractor = extractor;
    }

    /**
     * Create TimeRangeHolder based on current configuration
     * @param time actual border for plotting the final TimeRangeHolder
     * @return object TimeRangeHolder
     */
    public TimeRangeHolder<M,R> timeRangeHolder(@Nonnull TemporalAccessor time) {
        return new TimeRangeHolder<>(time, this);
    }

    /**
     * Creating a TimeRangeHolder TimeRangeConfig as the Type of Registered Items
     *
     * @param duration Duration of the region period (if negative, then to the left of instant, otherwise - to the right)
     * @param interval The intervals at which duration beats (if &gt; duration or &lt;= ZERO, then it is taken equal to duration.abs())
     * @param delay Intervals for checking for the operation of existing Expected objects
     * @param completeTimeout At a specified interval after the end of the described range, if there are no processed objects, the actor stops
     * @param expectation Getting timestamp from incoming element
     * @param comparator Redefining a comparator to order Expected objects not only in temporal ascending order, but also in internal content
     * @param extractor Method for converting an input element into a result element
     * @param <M> input element type
     * @param <R> the type of the returned element
     * @return TimeRangeHolder.TimeRangeConfig instance
     */
    @SuppressWarnings("java:S107")
    public static <M,R> TimeRangeConfig<M,R> create(
            @Nonnull  Duration duration,
            @Nonnull  Duration interval,
            @Nullable Duration delay,
            @Nonnull  Duration completeTimeout,
            @Nonnull  Expectation<M,? extends TemporalAccessor> expectation,
            @Nullable Comparator<? super M> comparator,
            @Nonnull  TimeRangeHolder.ResultTransformer<M,R> extractor
    ) {
        return new TimeRangeConfig<>(duration, interval, delay, completeTimeout, expectation, comparator, extractor);
    }
    /**
     * Creating a TimeRangeHolder.TimeRangeConfig with ExpectedPackage as the type of items being registered
     *
     * @param duration Duration of the region period (if negative, then to the left of instant, otherwise - to the right)
     * @param interval The intervals at which duration beats (if &gt; duration or &lt;= ZERO, then it is taken equal to duration.abs())
     * @param delay Intervals for checking for the operation of existing Expected objects
     * @param completeTimeout At a specified interval after the end of the described range, if there are no processed objects, the actor stops
     * @param comparator Redefining a comparator to order Expected objects not only in temporal ascending order, but also in internal content
     * @param <M> input element type
     * @param <R> the type of the returned element
     * @return TimeRangeHolder.TimeRangeConfig instance
     */
    public static <R, M extends ExpectedPackage<R,? extends TemporalAccessor>> TimeRangeConfig<M,R> packable(
            @Nonnull  Duration duration,
            @Nonnull  Duration interval,
            @Nullable Duration delay,
            @Nonnull  Duration completeTimeout,
            @Nullable Comparator<? super R> comparator
    ) {
        return create(duration, interval, delay, completeTimeout, ExpectedPackage::getTick,
                comparator == null ? null : (left, right) -> comparator.compare(left.getElement(), right.getElement()),
                ExpectedPackage::getElement);
    }

    /**
     * Creating a TimeRangeHolder.TimeRangeConfig with Expected as the type of items being registered
     *
     * @param duration Duration of the region period (if negative, then to the left of instant, otherwise - to the right)
     * @param interval The intervals at which duration beats (if &gt; duration or &lt;= ZERO, then it is taken equal to duration.abs())
     * @param delay Intervals for checking for the operation of existing Expected objects
     * @param completeTimeout At a specified interval after the end of the described range, if there are no processed objects, the actor stops
     * @param comparator Redefining a comparator to order Expected objects not only in temporal ascending order, but also in internal content
     * @param <M> input element type
     * @return TimeRangeHolder.TimeRangeConfig instance
     */
    public static <M extends Expected<? extends TemporalAccessor>> TimeRangeConfig<M,M> expected(
            @Nonnull  Duration duration,
            @Nonnull  Duration interval,
            @Nullable Duration delay,
            @Nonnull  Duration completeTimeout,
            @Nullable Comparator<? super M> comparator
    ) {
        return create(duration, interval, delay, completeTimeout, Expected::getTick, comparator, TimeRangeHolder.ResultTransformer.identity());
    }
}