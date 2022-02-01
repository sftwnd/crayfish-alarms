/*
 * Copyright © 2017-20xx Andrey D. Shindarev. All rights reserved.
 * This program is made available under the terms of the BSD 3-Clause License.
 * Contacts: ashindarev@gmail.com
 */
package com.github.sftwnd.crayfish.alarms.timerange;

import com.github.sftwnd.crayfish.common.expectation.Expectation;
import com.github.sftwnd.crayfish.common.expectation.Expected;
import com.github.sftwnd.crayfish.common.expectation.ExpectedPackage;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Optional.ofNullable;

/**
 * A time range with a set of elements marked with a time stamp that falls within the bounds of the range. On request,
 * from the range, you can pull out a set of elements with a label up to the moment specified by the query parameter.
 * The elements returned by the query are not stored in the range.
 * @param <M> Element type when added
 * @param <R> Element type when retrieving
 */
public class TimeRangeItems<M,R> {

    /**
     * Transformation of nonnull element to nonnull value
     * @param <M> source element type
     * @param <R> target element type
     */
    public interface ResultTransformer<M,R> extends Function<M,R> {
        /**
         * Transform element from one type to other
         *
         * @param element the source element
         * @return target element
         */
        R apply(M element);
        static <T> ResultTransformer<T, T> identity() {
            return t -> t;
        }
    }

    /*
        Used sonar warnings:
            java:S107 Methods should not have too many params
            java:S3358 Ternary operators should not be nested
            java:S3864 "Stream.peek" should be used with caution
     */
    // Basic settings
    private final Config<M,R> config;
    // Beginning of the region validity period
    private final Instant startInstant;
    // Upper limit of the interval (exclude...)
    private final Instant lastInstant;
    // Comparison of two registered objects
    private final Comparator<? super M> comparator;
    // The moment of the nearest element. In case of absence - null
    @Setter(value = AccessLevel.PRIVATE)
    private Instant nearestInstant = null;
    // A set of elements distributed over ranges of size interval
    // TreeMap storage structure that guarantees ascending traversal order
    // The internal elements are contained in a TreeSet, which also guarantees order.
    // Here we specify not the interface, but the implementation deliberately!!!
    private final TreeMap<Instant, TreeSet<M>> expectedMap = new TreeMap<>();
    private final Instant lastDelayedInstant;

    /**
     * An object containing objects marked with a time-marker for the range to search for triggered
     *
     * @param instant The moment limiting the region processing period (if duration is positive, then on the left, otherwise - on the right)
     * @param duration Duration of the period of the region (if negative, then to the left of instant, otherwise - to the right).
     * @param interval The intervals at which duration beats (if &gt; duration or &lt;= ZERO, then it is taken equal to duration.abs())
     * @param delay Intervals for checking for the operation of existing Expected objects
     * @param completeTimeout At a specified interval after the end of the described range, if there are no processed objects, the actor stops
     * @param expectation Getting timestamp from incoming element
     * @param comparator Redefining a comparator to order Expected objects not only in temporal ascending order, but also in internal content
     * @param extractor Method for converting an input element into a result element
     */
    @SuppressWarnings("java:S107")
    public TimeRangeItems(
            @NonNull  Instant  instant,
            @NonNull  Duration duration,
            @NonNull  Duration interval,
            @Nullable Duration delay,
            @NonNull  Duration completeTimeout,
            @NonNull  Expectation<M,? extends TemporalAccessor> expectation,
            @Nullable Comparator<? super M> comparator,
            @NonNull  ResultTransformer<M,R> extractor
    ) {
        this(instant, new Config<>(duration, interval, delay, completeTimeout, expectation, comparator, extractor));
    }

    /**
     * An object containing objects marked with a time-marker for the range to search for triggered
     *
     * @param instant The moment limiting the region processing period (if duration is positive, then on the left, otherwise - on the right)
     * @param config Configuration for constructor parameters
     */
    public TimeRangeItems(
            @NonNull Instant instant,
            @NonNull TimeRangeItems.Config<M,R> config
    ) {
        this.config = config;
        this.startInstant = Optional.of(config.duration).filter(Duration::isNegative).map(instant::plus).orElse(instant);
        this.lastInstant = Optional.of(config.duration).filter(Predicate.not(Duration::isNegative)).map(instant::plus).orElse(instant);
        this.comparator = ofNullable(config.comparator).orElse(this::compareObjects);
        this.lastDelayedInstant = this.lastInstant.minus(config.delay);
    }

    @Nonnull public Instant getStartInstant() { return this.startInstant; }
    @Nonnull public Instant getLastInstant() { return this.lastInstant; }
    @Nonnull public Duration getInterval() { return config.interval; }
    @Nonnull public Duration getDelay() { return config.delay; }
    @Nonnull public Duration getCompleteTimeout() { return config.completeTimeout; }

    /**
     * The time interval taking into account completeTimeout has been exhausted by the current moment
     * @return true if exhausted or false otherwise
     */
    public boolean isExpired() {
        return isExpired(Instant.now());
    }

    /**
     * The time interval, taking into account completeTimeout, has been exhausted by the transmitted moment
     * @param instant point in time at which the check is made
     * @return true if exhausted or false otherwise
     */
    public boolean isExpired(@Nullable Instant instant) {
        return !ofNullable(instant).orElseGet(Instant::now)
                .isBefore(this.lastInstant.plus(config.completeTimeout));
    }

    /**
     * It is checked that the structure does not contain elements and the interval, taking into account completeTimeout, has been exhausted at the current time
     * @return true if completed or false otherwise
     */
    public boolean isComplete() {
        return isComplete(Instant.now());
    }

    /**
     * It is checked that the structure does not contain elements and the interval, taking into account completeTimeout, has been exhausted for the passed time point
     * @param instant point in time at which the check is made
     * @return true if completed or false otherwise
     */
    public boolean isComplete(@Nullable Instant instant) {
        return this.expectedMap.isEmpty() && isExpired(instant);
    }

    /**
     * Add the specified set of elements to the range map
     * The attribute will also be set: nearestInstant
     * Out-of-range data is ignored with a message
     * @param elements collection of added elements
     * @return list of ignored elements
     */
    public Collection<M> addElements(@Nonnull Collection<M> elements) {
        List<M> excludes = new ArrayList<>();
        //noinspection ConstantConditions
        elements.stream().filter(Objects::nonNull)
                // Checking for range
                .filter(element -> checkRange(element) || !excludes.add(element))
                // If the element is the earliest, then mark it with Instant
                // P.S.> Due to the presence of the terminal operator, peek will work for every element that has passed through it.
                .peek(elm -> Optional.of(instant(elm)) //NOSONAR java:S3864 "Stream.peek" should be used with caution
                        .filter(inst -> inst.isBefore(ofNullable(this.nearestInstant).orElse(Instant.MAX)))
                        .ifPresent(this::setNearestInstant))
                // Grouping by instantKey ranges
                .collect(
                        Collectors.groupingBy(
                                elm -> getTemporalKey(instant(elm)),
                                Collectors.toCollection(() -> new TreeSet<>(this::compare))
                        )
                ).forEach((key,value) -> ofNullable(this.expectedMap.get(key))
                        .ifPresentOrElse(
                                // If present, expand
                                set -> set.addAll(value)
                                // If missing, add
                                , () -> this.expectedMap.put(key, value))
                );
        return excludes;
    }

    /**
     * Extracting from the saved elements those that, according to the temporary marker, are considered to have worked at the current moment
     * @return List of triggered elements
     */
    public @Nonnull Set<R> getFiredElements() {
        // Looking for current moment
        return getFiredElements(Instant.now());
    }

    /**
     * Retrieving from the saved elements those that, according to the time marker, are considered to have worked at the time passed by the parameter
     * @param instant point in time at which the check is made
     * @return List of triggered elements
     */
    public @Nonnull Set<R> getFiredElements(@Nullable Instant instant) {
        Instant now = ofNullable(instant).orElseGet(Instant::now);
        // The key corresponding to the current moment
        Instant nowKey = getInstantKey(now);
        HashSet<R> result = new HashSet<>();
        addCollectionOnProcess(
                this.expectedMap
                        .entrySet()
                        .stream()
                        // Since TreeMap, the order goes in ascending order of the instantKey key, and we process all records that have
                        // instantKey < nextKey
                        .takeWhile(entry -> !entry.getKey().isAfter(nowKey))
                        // We collect all the keys in a set (so that you can modify the primary TreeMap
                        .map(Map.Entry::getKey).collect(Collectors.toSet())
                        // For all keys that may contain triggered elements
                        .stream()
                        // We call the processing of a set removed from the primary Map
                        .flatMap(key -> processKey(this.expectedMap.remove(key), now, key.isBefore(nowKey), result))
                        // We collect everything that the handlers returned in set and send it back to Map
                        .collect(Collectors.toCollection(() -> new TreeSet<>(this.comparator))));
        return result;
    }

    private void addCollectionOnProcess(@Nonnull Collection<M> elements) {
        // At the time of the call, rows were deleted, and it is required to recalculate the time of the nearest element (if any)
        setNearestInstant(findNearestInstant());
        addElements(elements);
    }

    private Instant findNearestInstant() {
        return ofNullable(this.expectedMap.firstEntry())
                .map(Map.Entry::getValue)
                .map(TreeSet::first)
                .map(this::instant)
                .orElse(null);
    }

    private Stream<M> processKey(Set<M> elements, Instant now, boolean complete, Set<R> result) {
        return complete ? processComplete(elements, result) : processIncomplete(elements, now, result);
    }

    private Stream<M> processComplete(Set<M> elements, Set<R> result) {
        elements.stream().map(config.extractor).forEach(result::add);
        return Stream.empty();
    }

    private Stream<M> processIncomplete(Set<M> elements, Instant now, Set<R> result) {
        return elements
                .stream()
                .collect(Collectors.partitioningBy(element -> happened(element,now), Collectors.toSet()))
                .entrySet()
                .stream()
                .flatMap(entry -> Optional.of(entry)
                        .filter(Map.Entry::getKey)
                        .map(b -> processComplete(entry.getValue(), result))
                        .orElseGet(entry.getValue()::stream)
                );
    }

    /**
     * Timeout until the nearest available Expected, but not less than delay, and if not, until the next time limit -
     * either startInstant or lastInstant + completeDuration
     * @param now point in time for which we calculate the value
     * @return timeout to the nearest event, taking into account delay
     */
    public Duration duration(@NonNull Instant now) {
        // If the time is before the start of the range
        if (now.isBefore(this.startInstant)) {
            return durationToStart(now);
        // If the start time of the range has passed and there are no elements
        } else if (this.expectedMap.isEmpty()) {
            return durationToStop(now);
        // If there are elements and the time falls within the range
        } else if (now.isBefore(this.lastInstant)) {
            return durationToExpect(now);
        } else {
            return Duration.ZERO;
        }
    }

    /**
     * Timeout until the nearest available Expected, but not less than delay, and if not, until the next time limit -
     * either startInstant or lastInstant + completeDuration
     * @return timeout to the nearest event, taking into account the delay from the current moment
     */
    public Duration duration() {
        return duration(Instant.now());
    }

    // Time until the moment after lastInstant by completeTimeout duration. If after this point we are in COMPLETE,
    // then the actor ends.
    // This time is given by AKKA System to deliver the message with the processing order to us. The fact is that it is not
    // supposed to receive tasks for processing after the moment of their occurrence. This actor only accepts messages for the future
    private @Nonnull Duration durationToStop(Instant now) {
        return durationTo(this.lastInstant.plus(config.completeTimeout), now);
    }

    // The time from the specified moment until the first element fires, and in case of absence - until the end of the range of the current key
    private @Nonnull Duration durationToExpect(@Nonnull Instant now) {
        // If the nearest element is defined, then we wait until its moment, otherwise - until the end of the range time
        // P.S. - maybe you have to wait until the next key... It seems that there are no grounds yet, but there was a single hang-up precedent -
        //        previously associated with the use of BalancingPool, which is not supported by this Actor !!!.
        //        If it repeats, you need to look in the direction of optimizing durationToExpect
        return Optional.of(durationTo( // We take Delay to the nearest element, and if it is not there, then to the end of the chunk
                        ofNullable(this.nearestInstant)
                                .orElse(this.lastInstant),
                        now))
                // Check if it exceeds delay
                .filter(d -> d.compareTo(config.delay) >= 0)
                // If it does not exceed, then when hitting lastDelayedInstant we return delay, otherwise - the remaining time to lastInstant
                .orElseGet(() -> now.isAfter(this.lastInstant) ? Duration.ZERO
                        : now.isBefore(this.lastDelayedInstant) ? config.delay // NOSONAR java:S3358 Ternary operators should not be nested
                        : Duration.between(now, this.lastInstant));
    }

    // The time from the specified moment until the first element is triggered, and in case of absence - until the start of the range activation
    private @Nonnull Duration durationToStart(@Nonnull Instant now) {
        return durationTo(
                ofNullable(this.nearestInstant).orElse(this.startInstant),
                now);
    }

    // Time until the specified moment from the moment of the now parameter
    private static @Nonnull Duration durationTo(@Nonnull Instant instant, @Nonnull Instant now) {
        return instant.isAfter(now)
                ? Duration.between(now, instant)
                : Duration.ZERO;
    }

    private boolean checkRange(@Nonnull M element) {
        return Optional.of(element)
                .map(this::instant)
                .filter(Predicate.not(this.startInstant::isAfter))
                .filter(this.lastInstant::isAfter)
                .isPresent();
    }

    /**
     * Rounds the given instant to the beginning of the interval
     * @param instant The moment at which it is necessary to determine the key of the polling period
     * @return moment describing the range of the polling period
     */
    private Instant getInstantKey(@Nonnull Instant instant) {
        return Instant.ofEpochMilli(instant.toEpochMilli() - instant.toEpochMilli() % config.interval.toMillis());
    }

    private Instant getTemporalKey(@Nullable TemporalAccessor temporalAccessor) {
        return getInstantKey(Instant.from(ofNullable(temporalAccessor).orElse(Instant.MIN)));
    }

    /**
     * This function compares two existing objects. If their trigger times do not match, then the result of the comparison
     * is the same as the result of comparing the trigger times of the objects.
     * Otherwise, the order is taken according to the result of comparing both objects by the registered comparator
     */
    private int compare(@Nonnull M first, @Nonnull M second) {
        return first == second ? 0
             : Optional.of(instant(first).compareTo(instant(second)))
                .filter(result -> result != 0)
                .orElseGet(() -> comparator.compare(first, second));

    }

    private int compareObjects(@Nonnull Object first, @Nonnull Object second) {
        return Integer.compare(first.hashCode(), second.hashCode());
    }

    private boolean happened(@Nonnull M element, @NonNull Instant now) {
        return !instant(element).isAfter(now);
    }

    private @Nonnull Instant instant(@Nonnull M element) {
        return Instant.from(config.expectation.apply(element));
    }

    public static class Config<M,R> {

        // The duration of the described interval
        @Getter private final Duration duration;
        // The size of the internal chunk-a division of the interval
        @Getter private final Duration interval;
        // Minimum polling delay in ACTIVE status. Allows you to unload the processor, but reduce the accuracy of the event
        // firing approximately (on average) to the delay value.
        // P.S.> For negative ones, Duration.ZERO is set, it cannot be larger than the size of the internal chunk: interval
        @Getter private final Duration delay;
        // Timeout for delayed message delivery.
        // From the moment of the lastInstant, a timeout is maintained for the arrival of messages for processing
        @Getter private final Duration completeTimeout;
        // Getting the date from the logged message
        @Getter private final Expectation<M,? extends TemporalAccessor> expectation;
        // Comparison of two registered objects
        @Getter private final Comparator<? super M> comparator;
        // Getting result element from registered
        @Getter private final ResultTransformer<M,R> extractor;

        @SuppressWarnings("java:S107")
        private Config(
                @NonNull  Duration duration,
                @NonNull  Duration interval,
                @Nullable Duration delay,
                @NonNull  Duration completeTimeout,
                @NonNull  Expectation<M,? extends TemporalAccessor> expectation,
                @Nullable Comparator<? super M> comparator,
                @NonNull  ResultTransformer<M,R> extractor
        ) {
            if (Duration.ZERO.equals(duration)) throw new IllegalArgumentException("Config::new - Invalid duration: " + duration);
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
         * Create TimeRangeItems based on current configuration
         * @param instant actual border for plotting the final TimeRangeItems
         * @return object TimeRangeItems
         */
        public TimeRangeItems<M,R> timeRange(Instant instant) {
            return new TimeRangeItems<>(instant, this);
        }

        /**
         * Creating a TimeRangeItems Config as the Type of Registered Items
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
         * @return TimeRangeItems.Config instance
         */
        @SuppressWarnings("java:S107")
        public static <M,R> Config<M,R> create(
                @NonNull  Duration duration,
                @NonNull  Duration interval,
                @Nullable Duration delay,
                @NonNull  Duration completeTimeout,
                @NonNull  Expectation<M,? extends TemporalAccessor> expectation,
                @Nullable Comparator<? super M> comparator,
                @NonNull  ResultTransformer<M,R> extractor
        ) {
            return new Config<>(duration, interval, delay, completeTimeout, expectation, comparator, extractor);
        }
        /**
         * Creating a TimeRangeItems.Config with ExpectedPackage as the type of items being registered
         *
         * @param duration Duration of the region period (if negative, then to the left of instant, otherwise - to the right)
         * @param interval The intervals at which duration beats (if &gt; duration or &lt;= ZERO, then it is taken equal to duration.abs())
         * @param delay Intervals for checking for the operation of existing Expected objects
         * @param completeTimeout At a specified interval after the end of the described range, if there are no processed objects, the actor stops
         * @param comparator Redefining a comparator to order Expected objects not only in temporal ascending order, but also in internal content
         * @param <M> input element type
         * @param <R> the type of the returned element
         * @return TimeRangeItems.Config instance
         */
        public static <R, M extends ExpectedPackage<R,? extends TemporalAccessor>> Config<M,R> packable(
                @NonNull  Duration duration,
                @NonNull  Duration interval,
                @Nullable Duration delay,
                @NonNull  Duration completeTimeout,
                @Nullable Comparator<? super R> comparator
        ) {
            return create(duration, interval, delay, completeTimeout, ExpectedPackage::getTick,
                    comparator == null ? null : (left, right) -> comparator.compare(left.getElement(), right.getElement()),
                    ExpectedPackage::getElement);
        }

        /**
         * Creating a TimeRangeItems.Config with Expected as the type of items being registered
         *
         * @param duration Duration of the region period (if negative, then to the left of instant, otherwise - to the right)
         * @param interval The intervals at which duration beats (if &gt; duration or &lt;= ZERO, then it is taken equal to duration.abs())
         * @param delay Intervals for checking for the operation of existing Expected objects
         * @param completeTimeout At a specified interval after the end of the described range, if there are no processed objects, the actor stops
         * @param comparator Redefining a comparator to order Expected objects not only in temporal ascending order, but also in internal content
         * @param <M> input element type
         * @return TimeRangeItems.Config instance
         */
        public static <M extends Expected<? extends TemporalAccessor>> Config<M,M> expected(
                @NonNull  Duration duration,
                @NonNull  Duration interval,
                @Nullable Duration delay,
                @NonNull  Duration completeTimeout,
                @Nullable Comparator<? super M> comparator
        ) {
            return create(duration, interval, delay, completeTimeout, Expected::getTick, comparator, ResultTransformer.identity());
        }
    }

}
