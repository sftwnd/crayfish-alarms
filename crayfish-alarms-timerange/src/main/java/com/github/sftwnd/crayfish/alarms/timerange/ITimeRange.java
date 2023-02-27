/*
 * Copyright © 2017-2023 Andrey D. Shindarev. All rights reserved.
 * This program is made available under the terms of the BSD 3-Clause License.
 * Contacts: ashindarev@gmail.com
 */
package com.github.sftwnd.crayfish.alarms.timerange;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * TimeRange alarms holder
 * @param <M> incoming alarm description type
 * @param <R> resulting alarm event type
 */
public interface ITimeRange<M,R> extends ITimeRangeConsumer<M,Collection<M>> {

    /**
     * Point in time that is the start of the time range
     * @return start of time range
     */
    @NonNull Instant getStartInstant();

    /**
     * Point in time that is the end of a time range
     * @return after of time range
     */
    @NonNull Instant getLastInstant();

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
    @Override
    @NonNull Collection<M> addElements(@NonNull Collection<M> elements);

    /**
     * Add just one element to the range map
     * @param element element to add
     * @return list of ignored elements (just zero or one element)
     */
    default @NonNull Collection<M> addElement(@NonNull M element) {
        return addElements(List.of(element));
    }

    /**
     * Extracting from the saved elements those that, according to the temporary marker, are considered to have
     * worked at the current moment
     * @return List of triggered elements
     */
    default @NonNull Collection<R> extractFiredElements() {
        return extractFiredElements( Instant.now() );
    }

    /**
     * Extracting from the saved elements those that, according to the time marker, are considered to have worked
     * at the time passed by the parameter
     * @param instant point in time at which the check is made
     * @return List of triggered elements
     */
    @NonNull Collection<R> extractFiredElements(@Nullable Instant instant);

    /**
     * Timeout until the nearest available Expected, but not less than delay, and if not, until the next time limit -
     * either startInstant or lastInstant + completeDuration
     * @return timeout to the nearest event, taking into account the delay from the current moment
     */
    default @NonNull Duration duration() {
        return duration( Duration.ZERO );
    }

    /**
     * Timeout until the nearest available Expected, but not less than delay from now, and if not, until the next
     * time limit - either startInstant or lastInstant + completeDuration
     * @param delay point in time with delay from now for which we calculate the value
     * @return timeout to the nearest event, taking into account the delay from the current moment
     */
    default @NonNull Duration duration(Duration delay) {
        return duration( Instant.now().plus(delay) );
    }

    /**
     * Timeout until the nearest available Expected, but not less than delay, and if not, until the next time limit -
     * either startInstant or lastInstant + completeDuration
     * @param now point in time for which we calculate the value
     * @return timeout to the nearest event, taking into account delay
     */
    @NonNull Duration duration(@NonNull Instant now);

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
        @NonNull T apply(@NonNull S source);

        /**
         * Returns a transformer that always returns its input argument.
         * @return a transformer that always returns its input argument
         * @param <T> type of element
         */
        static <T> Transformer<T, T> identity() {
            return element -> Objects.requireNonNull(element, "Transformer::apply - element is null");
        }
    }


}
