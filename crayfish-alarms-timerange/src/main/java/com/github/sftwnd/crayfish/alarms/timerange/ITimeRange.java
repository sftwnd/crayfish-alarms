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
import java.util.Collections;
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
        return addElements(Collections.singletonList(Objects.requireNonNull(element, "ITimeRange::addElement - element is null")));
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
     * The time from the current moment to the next event of the range, as {@link #duration(Instant)} counts it
     * @return the time to the next event, zero if it has come
     */
    default @NonNull Duration duration() {
        return duration( Duration.ZERO );
    }

    /**
     * The time from the current moment plus the delay to the next event of the range, as {@link #duration(Instant)}
     * counts it: the time that remains after the delay. It is no lower bound: an element due within the delay gives zero
     * @param delay the delay after the current moment
     * @return the time from the current moment plus the delay to the next event, zero if it has come by then
     */
    default @NonNull Duration duration(Duration delay) {
        return duration( Instant.now().plus(delay) );
    }

    /**
     * The time from the moment to the next event of the range: with elements, the moment of the nearest of them;
     * without elements, the start of the range before it, and the end of the range plus its completeTimeout after it
     * @param now point in time for which we calculate the value
     * @return the time to the next event, zero if it has come
     */
    @NonNull Duration duration(@NonNull Instant now);

    /**
     * Remove all alarms from the TimeRange
     */
    void clean();

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
