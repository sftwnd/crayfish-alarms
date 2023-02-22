package com.github.sftwnd.crayfish.alarms.timerange;

import com.github.sftwnd.crayfish.alarms.timerange.ITimeRange.Transformer;
import com.github.sftwnd.crayfish.common.expectation.Expectation;
import com.github.sftwnd.crayfish.common.expectation.Expected;
import com.github.sftwnd.crayfish.common.expectation.ExpectedPackage;
import com.github.sftwnd.crayfish.common.expectation.TimeExtractor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.Duration;
import java.time.temporal.TemporalAccessor;
import java.util.Comparator;

@FunctionalInterface
public interface ITimeRangeFactory<M,R> {

    /**
     * Create TimeRange based on current configuration
     * @param time actual border for plotting the final TimeRange
     * @return object TimeRange
     */
    @Nonnull ITimeRange<M,R> timeRange(@Nonnull TemporalAccessor time);

    /**
     * Creating a TimeRange ITimeRangeFactory as the Type of Registered Items
     *
     * @param duration Duration of the region period (if negative, then to the left of instant, otherwise - to the right)
     * @param interval The intervals at which duration beats (if &gt; duration or &lt;= ZERO, then it is taken equal to duration.abs())
     * @param delay Intervals for checking for the operation of existing Expected objects
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
    static <M,S,R>  @Nonnull ITimeRangeFactory<M,R> create(
            @Nonnull  Duration duration,
            @Nonnull  Duration interval,
            @Nullable Duration delay,
            @Nonnull  Duration completeTimeout,
            @Nonnull  Transformer<M,S> preserver,
            @Nonnull  Expectation<S,? extends TemporalAccessor> expectation,
            @Nonnull  Transformer<S,R> reducer,
            @Nullable Comparator<? super S> comparator
    ) {
        return time -> new TimeRange<> (
                time,
                duration,
                interval,
                delay,
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
     * @param delay Intervals for checking for the operation of existing Expected objects
     * @param completeTimeout At a specified interval after the end of the described range, if there are no processed objects, the actor stops
     * @param expectation Getting timestamp from internal element
     * @param comparator Redefining a comparator to order Expected objects not only in temporal ascending order, but also in internal content
     * @return TimeRange.ITimeRangeFactory instance
     * @param <M> input element type
     */
    @SuppressWarnings("java:S107")
    static <M> @Nonnull ITimeRangeFactory<M,M> create(
            @Nonnull  Duration duration,
            @Nonnull  Duration interval,
            @Nullable Duration delay,
            @Nonnull  Duration completeTimeout,
            @Nonnull  Expectation<M,? extends TemporalAccessor> expectation,
            @Nullable Comparator<? super M> comparator
    ) {
        return create(duration, interval, delay, completeTimeout, Transformer.identity(), expectation, Transformer.identity(), comparator);
    }

    /**
     * Creating a TimeRange.ITimeRangeFactory with ExpectedPackage as the type of items being registered
     *
     * @param duration Duration of the region period (if negative, then to the left of instant, otherwise - to the right)
     * @param interval The intervals at which duration beats (if &gt; duration or &lt;= ZERO, then it is taken equal to duration.abs())
     * @param delay Intervals for checking for the operation of existing Expected objects
     * @param completeTimeout At a specified interval after the end of the described range, if there are no processed objects, the actor stops
     * @param timeExtractor Extractor of temporal accessor from input element
     * @param comparator Redefining a comparator to order Expected objects not only in temporal ascending order, but also in internal content
     * @param <E> input element type
     * @param <T> the type of temporal accessor value
     * @param <S> the type of internal object (extend ExpectedPackage)
     * @return TimeRange.ITimeRangeFactory instance
     */
    static <E, T extends TemporalAccessor> @Nonnull ITimeRangeFactory<E,E> packable(
            @Nonnull  Duration duration,
            @Nonnull  Duration interval,
            @Nullable Duration delay,
            @Nonnull  Duration completeTimeout,
            @Nonnull  TimeExtractor<E,T> timeExtractor,
            @Nullable Comparator<? super E> comparator
    ) {
        return create(
                duration, interval, delay, completeTimeout,
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
     * @param delay Intervals for checking for the operation of existing Expected objects
     * @param completeTimeout At a specified interval after the end of the described range, if there are no processed objects, the actor stops
     * @param comparator Redefining a comparator to order Expected objects not only in temporal ascending order, but also in internal content
     * @param <M> input element type
     * @return TimeRange.ITimeRangeFactory instance
     */
    static <M extends Expected<? extends TemporalAccessor>> @Nonnull ITimeRangeFactory<M,M> expected(
            @Nonnull  Duration duration,
            @Nonnull  Duration interval,
            @Nullable Duration delay,
            @Nonnull  Duration completeTimeout,
            @Nullable Comparator<? super M> comparator
    ) {
        return create(duration, interval, delay, completeTimeout, M::getTick, comparator);
    }

}
