/*
 * Copyright © 2017-2023 Andrey D. Shindarev. All rights reserved.
 * This program is made available under the terms of the BSD 3-Clause License.
 * Contacts: ashindarev@gmail.com
 */
package com.github.sftwnd.crayfish.alarms.timerange;

import com.github.sftwnd.crayfish.alarms.timerange.ITimeRange.Transformer;
import com.github.sftwnd.crayfish.common.expectation.Expected;
import com.github.sftwnd.crayfish.common.expectation.ExpectedPackage;
import com.github.sftwnd.crayfish.common.expectation.TemporalExtractor;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

import java.time.Duration;
import java.time.temporal.TemporalAccessor;
import java.util.Comparator;

/**
 * Factory for ITimeRange instance creation
 * @param <M> incoming alarm description type
 * @param <R> resulting alarm event type
 */
@FunctionalInterface
public interface ITimeRangeFactory<M,R> {

    /**
     * Create TimeRange based on current configuration
     * @param time actual border for plotting the final TimeRange
     * @return object TimeRange
     */
    @NonNull ITimeRange<M,R> timeRange(@NonNull TemporalAccessor time);

    /**
     * Creating a TimeRange ITimeRangeFactory as the Type of Registered Items
     *
     * @param duration Duration of the region period (if negative, then to the left of instant, otherwise - to the right)
     * @param interval The intervals at which duration beats (if &gt; duration or &lt;= ZERO, then it is taken equal to duration.abs())
     * @param completeTimeout At a specified interval after the end of the described range, if there are no processed objects, the actor stops
     * @param preserver Constructor for the internal storage object from the incoming element
     * @param expectation Getting timestamp from internal element
     * @param reducer Method for converting an internal element into a result element
     * @param comparator Redefining a comparator to order Expected objects not only in temporal ascending order, but also in internal content
     * @param <M> input element type
     * @param <S> internal element type
     * @param <R> the type of the returned element
     * @return ITimeRangeFactory instance
     */
    @SuppressWarnings("java:S107")
    static <M,S,R>  @NonNull ITimeRangeFactory<M,R> create(
            @NonNull  Duration duration,
            @NonNull  Duration interval,
            @Nullable Duration completeTimeout,
            @NonNull  Transformer<M,S> preserver,
            @NonNull  TemporalExtractor<S,? extends TemporalAccessor> expectation,
            @NonNull  Transformer<S,R> reducer,
            @Nullable Comparator<? super S> comparator
    ) {
        return time -> new TimeRange<> (
                time,
                duration,
                interval,
                completeTimeout,
                preserver,
                expectation,
                reducer,
                comparator
        );
    }

    /**
     *
     * Creating a TimeRange ITimeRangeFactory as the Type of Registered Items
     *
     * @param duration Duration of the region period (if negative, then to the left of instant, otherwise - to the right)
     * @param interval The intervals at which duration beats (if &gt; duration or &lt;= ZERO, then it is taken equal to duration.abs())
     * @param completeTimeout At a specified interval after the end of the described range, if there are no processed objects, the actor stops
     * @param expectation Getting timestamp from internal element
     * @param comparator Redefining a comparator to order Expected objects not only in temporal ascending order, but also in internal content
     * @return TimeRange.ITimeRangeFactory instance
     * @param <M> input element type
     */
    @SuppressWarnings("java:S107")
    static <M> @NonNull ITimeRangeFactory<M,M> create(
            @NonNull  Duration duration,
            @NonNull  Duration interval,
            @Nullable Duration completeTimeout,
            @NonNull  TemporalExtractor<M,? extends TemporalAccessor> expectation,
            @Nullable Comparator<? super M> comparator
    ) {
        return create(duration, interval, completeTimeout, Transformer.identity(), expectation, Transformer.identity(), comparator);
    }

    /**
     * Creating a TimeRange.ITimeRangeFactory with ExpectedPackage as the type of items being registered
     *
     * @param duration Duration of the region period (if negative, then to the left of instant, otherwise - to the right)
     * @param interval The intervals at which duration beats (if &gt; duration or &lt;= ZERO, then it is taken equal to duration.abs())
     * @param completeTimeout At a specified interval after the end of the described range, if there are no processed objects, the actor stops
     * @param timeExtractor Extractor of temporal accessor from input element
     * @param comparator Redefining a comparator to order Expected objects not only in temporal ascending order, but also in internal content
     * @param <E> input element type
     * @param <T> the type of temporal accessor value
     * @return TimeRange.ITimeRangeFactory instance
     */
    static <E, T extends TemporalAccessor> @NonNull ITimeRangeFactory<E,E> packable(
            @NonNull  Duration duration,
            @NonNull  Duration interval,
            @Nullable Duration completeTimeout,
            @NonNull  TemporalExtractor<E,T> timeExtractor,
            @Nullable Comparator<? super E> comparator
    ) {
        return create(
                duration, interval, completeTimeout,
                source -> ExpectedPackage.extract(source, timeExtractor),
                ExpectedPackage::getTick,
                ExpectedPackage::getElement,
                comparator == null ? null : (o1, o2) -> comparator.compare(o1.getElement(), o2.getElement()));
    }

    /**
     * Creating a TimeRange.ITimeRangeFactory with Expected as the type of items being registered
     *
     * @param duration Duration of the region period (if negative, then to the left of instant, otherwise - to the right)
     * @param interval The intervals at which duration beats (if &gt; duration or &lt;= ZERO, then it is taken equal to duration.abs())
     * @param completeTimeout At a specified interval after the end of the described range, if there are no processed objects, the actor stops
     * @param comparator Redefining a comparator to order Expected objects not only in temporal ascending order, but also in internal content
     * @param <M> input element type
     * @return TimeRange.ITimeRangeFactory instance
     */
    static <M extends Expected<? extends TemporalAccessor>> @NonNull ITimeRangeFactory<M,M> expected(
            @NonNull  Duration duration,
            @NonNull  Duration interval,
            @Nullable Duration completeTimeout,
            @Nullable Comparator<? super M> comparator
    ) {
        return create(duration, interval, completeTimeout, M::getTick, comparator);
    }

    /**
     * Creating a TimeRange.ITimeRangeFactory with temporal as the type of items being registered
     *
     * @param duration Duration of the region period (if negative, then to the left of instant, otherwise - to the right)
     * @param interval The intervals at which duration beats (if &gt; duration or &lt;= ZERO, then it is taken equal to duration.abs())
     * @param completeTimeout At a specified interval after the end of the described range, if there are no processed objects, the actor stops
     * @param comparator Redefining a comparator to order Expected objects not only in temporal ascending order, but also in internal content
     * @param <M> input element type
     * @return TimeRange.ITimeRangeFactory instance
     */
    static <M extends  TemporalAccessor> @NonNull ITimeRangeFactory<M,M> temporal(
            @NonNull  Duration duration,
            @NonNull  Duration interval,
            @Nullable Duration completeTimeout,
            @Nullable Comparator<? super M> comparator
    ) {
        return create(duration, interval, completeTimeout, temporal -> temporal, comparator);
    }

}
