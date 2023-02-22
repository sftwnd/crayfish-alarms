package com.github.sftwnd.crayfish.alarms.timerange;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Objects;
import java.util.function.Function;

public interface ITimeRange<M,R> {

    /**
     * Point in time that is the start of the time range
     * @return start of time range
     */
    @Nonnull Instant getStartInstant();

    /**
     * Point in time that is the end of a time range
     * @return after of time range
     */
    @Nonnull Instant getLastInstant();

    /**
     * How long does it take to determine when an alarm goes off?
     * @return alarm accuracy
     */
    @Nonnull Duration getInterval();

    /**
     * Allows you to calculate the alarm trigger time with a specified offset relative to the current time
     * @return offset relative to now
     */
    @Nonnull Duration getDelay();

    /**
     * The time interval that is maintained after the end of the current range of alarms for processing the remaining ones
     * (in this interval, alarms can be added to the range - they will immediately be set on fire)
     * @return range interval extension time
     */
    @Nonnull Duration getCompleteTimeout();

    /**
     * The time interval taking into account completeTimeout has been exhausted by the current moment
     * @return true if exhausted or false otherwise
     */
    default boolean isExpired() {
        return isExpired(Instant.now());
    }

    /**
     * The time interval, taking into account completeTimeout, has been exhausted by the transmitted moment
     * @param instant point in time at which the check is made
     * @return true if exhausted or false otherwise
     */
    boolean isExpired(@Nullable Instant instant);

    /**
     * It is checked that the structure does not contain elements and the interval, taking into account completeTimeout,
     * has been exhausted at the current time
     * @return true if completed or false otherwise
     */
    default boolean isComplete() {
        return isComplete(Instant.now());
    }

    /**
     * It is checked that the structure does not contain elements and the interval, taking into account completeTimeout,
     * has been exhausted for the passed time point
     * @param instant point in time at which the check is made
     * @return true if completed or false otherwise
     */
    boolean isComplete(@Nullable Instant instant);

    /**
     * Add the specified set of elements to the range map
     * The attribute will also be set: nearestInstant
     * Out-of-range data is ignored with a message
     * @param elements collection of added elements
     * @return list of ignored elements
     */
    @Nonnull Collection<M> addElements(@Nonnull Collection<M> elements);

    /**
     * Extracting from the saved elements those that, according to the temporary marker, are considered to have
     * worked at the current moment
     * @return List of triggered elements
     */
    default @Nonnull Collection<R> extractFiredElements() {
        return extractFiredElements( Instant.now() );
    }

    /**
     * Extracting from the saved elements those that, according to the time marker, are considered to have worked
     * at the time passed by the parameter
     * @param instant point in time at which the check is made
     * @return List of triggered elements
     */
    @Nonnull Collection<R> extractFiredElements(@Nullable Instant instant);

    /**
     * Timeout until the nearest available Expected, but not less than delay, and if not, until the next time limit -
     * either startInstant or lastInstant + completeDuration
     * @return timeout to the nearest event, taking into account the delay from the current moment
     */
    default @Nonnull Duration duration() {
        return duration( Instant.now() );
    }

    /**
     * Timeout until the nearest available Expected, but not less than delay, and if not, until the next time limit -
     * either startInstant or lastInstant + completeDuration
     * @param now point in time for which we calculate the value
     * @return timeout to the nearest event, taking into account delay
     */
    @Nonnull Duration duration(@Nonnull Instant now);

    /**
     * Transformation of nonnull element to nonnull value
     * @param <S> source element type
     * @param <T> target element type
     */
    @FunctionalInterface
    interface Transformer<S,T> extends Function<S,T> {
        /**
         * Transform element from one type to other
         *
         * @param source the source element
         * @return target element
         */
        @Nonnull T apply(@Nonnull S source);
        static <T> Transformer<T, T> identity() {
            return element -> Objects.requireNonNull(element, "Transformer::apply - element is null");
        }
    }


}
