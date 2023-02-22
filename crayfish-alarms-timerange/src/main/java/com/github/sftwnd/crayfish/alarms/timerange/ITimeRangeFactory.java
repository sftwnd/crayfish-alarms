package com.github.sftwnd.crayfish.alarms.timerange;

import com.github.sftwnd.crayfish.common.expectation.Expectation;
import com.github.sftwnd.crayfish.common.expectation.Expected;
import com.github.sftwnd.crayfish.common.expectation.ExpectedPackage;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.Duration;
import java.time.temporal.TemporalAccessor;
import java.util.Comparator;
import java.util.Objects;

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
     * @param expectation Getting timestamp from incoming element
     * @param comparator Redefining a comparator to order Expected objects not only in temporal ascending order, but also in internal content
     * @param extractor Method for converting an input element into a result element
     * @param <M> input element type
     * @param <R> the type of the returned element
     * @return TimeRange.ITimeRangeFactory instance
     */
    @SuppressWarnings("java:S107")
    static @Nonnull <M,R> ITimeRangeFactory<M,R> create(
            @Nonnull  Duration duration,
            @Nonnull  Duration interval,
            @Nullable Duration delay,
            @Nonnull  Duration completeTimeout,
            @Nonnull  Expectation<M,? extends TemporalAccessor> expectation,
            @Nullable Comparator<? super M> comparator,
            @Nonnull  TimeRange.ResultTransformer<M,R> extractor
    ) {
        return time -> new TimeRange<>(
                time,
                new ImmutableTimeRangeFactoryConfig<>(
                        Objects.requireNonNull(duration, "ITimeRangeFactory::create - duration is null"),
                        Objects.requireNonNull(interval, "ITimeRangeFactory::create - interval is null"),
                        delay,
                        Objects.requireNonNull(completeTimeout, "ITimeRangeFactory::create - completeTimeout is null"),
                        Objects.requireNonNull(expectation, "ITimeRangeFactory::create - expectation is null"),
                        comparator,
                        Objects.requireNonNull(extractor, "ITimeRangeFactory::create - extractor is null")
                ));
    }

    /**
     * Creating a TimeRange ITimeRangeFactory as the Type of Registered Items
     *
     * @param duration Duration of the region period (if negative, then to the left of instant, otherwise - to the right)
     * @param interval The intervals at which duration beats (if &gt; duration or &lt;= ZERO, then it is taken equal to duration.abs())
     * @param delay Intervals for checking for the operation of existing Expected objects
     * @param completeTimeout At a specified interval after the end of the described range, if there are no processed objects, the actor stops
     * @param expectation Getting timestamp from incoming element
     * @param comparator Redefining a comparator to order Expected objects not only in temporal ascending order, but also in internal content
     * @param <M> input and returned elements type
     * @return TimeRange.ITimeRangeFactory instance
     */
    @SuppressWarnings("java:S107")
    static @Nonnull <M> ITimeRangeFactory<M,M> create(
            @Nonnull  Duration duration,
            @Nonnull  Duration interval,
            @Nullable Duration delay,
            @Nonnull  Duration completeTimeout,
            @Nonnull  Expectation<M,? extends TemporalAccessor> expectation,
            @Nullable Comparator<? super M> comparator
    ) {
        return create(duration, interval, delay, completeTimeout, expectation, comparator, TimeRange.ResultTransformer.identity());
    }

    /**
     * Creating a TimeRange.ITimeRangeFactory with ExpectedPackage as the type of items being registered
     *
     * @param duration Duration of the region period (if negative, then to the left of instant, otherwise - to the right)
     * @param interval The intervals at which duration beats (if &gt; duration or &lt;= ZERO, then it is taken equal to duration.abs())
     * @param delay Intervals for checking for the operation of existing Expected objects
     * @param completeTimeout At a specified interval after the end of the described range, if there are no processed objects, the actor stops
     * @param comparator Redefining a comparator to order Expected objects not only in temporal ascending order, but also in internal content
     * @param <M> input element type
     * @param <R> the type of the returned element
     * @return TimeRange.ITimeRangeFactory instance
     */
    static @Nonnull <R, M extends ExpectedPackage<R,? extends TemporalAccessor>> ITimeRangeFactory<M,R> packable(
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
    static @Nonnull <M extends Expected<? extends TemporalAccessor>> ITimeRangeFactory<M,M> expected(
            @Nonnull  Duration duration,
            @Nonnull  Duration interval,
            @Nullable Duration delay,
            @Nonnull  Duration completeTimeout,
            @Nullable Comparator<? super M> comparator
    ) {
        return create(duration, interval, delay, completeTimeout, Expected::getTick, comparator, TimeRange.ResultTransformer.identity());
    }

}
