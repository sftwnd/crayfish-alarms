/*
 * Copyright © 2017-2022 Andrey D. Shindarev. All rights reserved.
 * This program is made available under the terms of the BSD 3-Clause License.
 * Contacts: ashindarev@gmail.com
 */
package com.github.sftwnd.crayfish.alarms.timerange;

import com.github.sftwnd.crayfish.common.expectation.Expectation;
import lombok.AccessLevel;
import lombok.Setter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
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
public class TimeRange<M,S,R> implements ITimeRange<M,R> {

    /*
        Used sonar warnings:
            java:S107 Methods should not have too many params
            java:S3358 Ternary operators should not be nested
            java:S3864 "Stream.peek" should be used with caution
     */
    // Basic settings
    private final ITimeRangeConfig<M,S,R> timeRangeConfig;
    // Message to internal store class preserver
    private final Transformer<M,S> preserver;
    // Message from internal store class to result reducer
    private final Transformer<S,R> reducer;


    // Beginning of the region validity period
    private final Instant startInstant;
    // Upper limit of the interval (exclude...)
    private final Instant lastInstant;
    // Comparison of two registered objects
    private final Comparator<? super S> comparator;
    // The moment of the nearest element. In case of absence - null
    @Setter(value = AccessLevel.PRIVATE)
    private Instant nearestInstant = null;
    // A set of elements distributed over ranges of size interval
    // TreeMap storage structure that guarantees ascending traversal order
    // The internal elements are contained in a TreeSet, which also guarantees order.
    // Here we specify not the interface, but the implementation deliberately!!!
    private final TreeMap<Instant, TreeSet<S>> expectedMap = new TreeMap<>();
    private final Instant lastDelayedInstant;

    /**
     * An object containing objects marked with a time-marker for the range to search for triggered
     *
     * @param instant The moment limiting the region processing period (if duration is positive, then on the left, otherwise - on the right)
     * @param duration Duration of the period of the region (if negative, then to the left of instant, otherwise - to the right).
     * @param interval The intervals at which duration beats (if &gt; duration or &lt;= ZERO, then it is taken equal to duration.abs())
     * @param delay Intervals for checking for the operation of existing Expected objects
     * @param completeTimeout At a specified interval after the end of the described range, if there are no processed objects, the actor stops
     * @param preserver Method for converting an input element into an internal element
     * @param expectation Getting timestamp from incoming element
     * @param reducer Method for converting an internal element into a result element
     * @param comparator Redefining a comparator to order Expected objects not only in temporal ascending order, but also in internal content
     */
    @SuppressWarnings("java:S107")
    public TimeRange(
            @Nonnull  Instant  instant,
            @Nonnull  Duration duration,
            @Nonnull  Duration interval,
            @Nullable Duration delay,
            @Nonnull  Duration completeTimeout,
            @Nonnull  Transformer<M,S> preserver,
            @Nonnull  Expectation<S,? extends TemporalAccessor> expectation,
            @Nonnull  Transformer<S,R> reducer,
            @Nullable Comparator<? super S> comparator
    ) {
        this(instant, new ImmutableTimeRangeConfig<>(duration, interval, delay, completeTimeout, preserver,expectation, reducer, comparator));
    }

    /**
     * An object containing objects marked with a time-marker for the range to search for triggered
     *
     * @param time The moment limiting the region processing period (if duration is positive, then on the left, otherwise - on the right)
     * @param timeRangeConfig Configuration for constructor parameters
     */
    public TimeRange(
            @Nonnull TemporalAccessor time,
            @Nonnull ITimeRangeConfig<M,S,R> timeRangeConfig
    ) {
        Objects.requireNonNull(time, "TimeRange::new - time is null");
        this.timeRangeConfig = Objects.requireNonNull(timeRangeConfig, "TimeRange::new - timeRangeConfig is null").immutable();
        this.preserver = timeRangeConfig.getPreserver();
        this.reducer = timeRangeConfig.getReducer();
        this.startInstant = Optional.of(this.timeRangeConfig.getDuration()).filter(Duration::isNegative).map(Instant.from(time)::plus).orElseGet(() -> Instant.from(time));
        this.lastInstant = Optional.of(this.timeRangeConfig.getDuration()).filter(Predicate.not(Duration::isNegative)).map(Instant.from(time)::plus).orElseGet(() -> Instant.from(time));
        this.comparator = ofNullable(this.timeRangeConfig.getComparator()).orElse(this::compareObjects);
        this.lastDelayedInstant = this.lastInstant.minus(this.timeRangeConfig.getDelay());
    }

    @Nonnull public Instant getStartInstant() { return this.startInstant; }
    @Nonnull public Instant getLastInstant() { return this.lastInstant; }
    @Nonnull public Duration getInterval() { return timeRangeConfig.getInterval(); }
    @Nonnull public Duration getDelay() { return timeRangeConfig.getDelay(); }
    @Nonnull public Duration getCompleteTimeout() { return timeRangeConfig.getCompleteTimeout(); }

    /**
     * The time interval, taking into account completeTimeout, has been exhausted by the transmitted moment
     * @param instant point in time at which the check is made
     * @return true if exhausted or false otherwise
     */
    public boolean isExpired(@Nullable Instant instant) {
        return !ofNullable(instant).orElseGet(Instant::now)
                .isBefore(this.lastInstant.plus(timeRangeConfig.getCompleteTimeout()));
    }

    /**
     * It is checked that the structure does not contain elements and the interval, taking into account completeTimeout,
     * has been exhausted for the passed time point
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
    public @Nonnull Collection<M> addElements(@Nonnull Collection<M> elements) {
        Objects.requireNonNull(elements, "TimeRange::addElement - elements is null");
        List<M> excludes = new LinkedList<>();
        //noinspection ConstantConditions
        addElements(
                elements.stream()
                // Checking for range
                .map(element -> {
                    if (element != null) { // If element is not null
                        S storeElement = this.preserver.apply(element); // Transform element to internal store format
                        if (checkRange(storeElement)) {
                            return storeElement;
                        } else {
                            excludes.add(element);
                        }
                    }
                    return null;
                })
                .filter(Objects::nonNull)
        );
        return excludes;
    }

    // Add or return back elements in the internal format to the time range storage
    private void addElements(@Nonnull Stream<S> stream) {
        // If the element is the earliest, then mark it with Instant
        // P.S. Due to the presence of the terminal operator, peek will work for every element that has passed through it.
        stream.peek(elm -> Optional.of(instant(elm)) //NOSONAR java:S3864 "Stream.peek" should be used with caution
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
    }

    /**
     * Extracting from the saved elements those that, according to the time marker, are considered
     * to have worked at the time passed by the parameter
     * @param instant point in time at which the check is made
     * @return List of triggered elements
     */
    public @Nonnull Collection<R> extractFiredElements(@Nullable Instant instant) {
        Instant now = ofNullable(instant).orElseGet(Instant::now);
        // The key corresponding to the current moment
        Instant nowKey = getInstantKey(now);
        List<R> result = new ArrayList<>();
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

    private void addCollectionOnProcess(@Nonnull Collection<S> elements) {
        // At the time of the call, rows were deleted, and it is required to recalculate the time of the nearest element (if any)
        setNearestInstant(findNearestInstant());
        addElements(elements.stream());
    }

    private Instant findNearestInstant() {
        return ofNullable(this.expectedMap.firstEntry())
                .map(Map.Entry::getValue)
                .map(TreeSet::first)
                .map(this::instant)
                .orElse(null);
    }

    private Stream<S> processKey(Set<S> elements, Instant now, boolean complete, List<R> result) {
        return complete ? processComplete(elements, result) : processIncomplete(elements, now, result);
    }

    private Stream<S> processComplete(Set<S> elements, List<R> result) {
        elements.stream().map(timeRangeConfig.getReducer()).forEach(result::add);
        return Stream.empty();
    }

    private Stream<S> processIncomplete(Set<S> elements, Instant now, List<R> result) {
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
    public @Nonnull Duration duration(@Nonnull Instant now) {
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

    // Time until the moment after lastInstant by completeTimeout duration. If after this point we are in COMPLETE,
    // then the actor ends.
    // This time is given by AKKA System to deliver the message with the processing order to us. The fact is that it is not
    // supposed to receive tasks for processing after the moment of their occurrence. This actor only accepts messages for the future
    private @Nonnull Duration durationToStop(Instant now) {
        return durationTo(this.lastInstant.plus(timeRangeConfig.getCompleteTimeout()), now);
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
                .filter(d -> d.compareTo(timeRangeConfig.getDelay()) >= 0)
                // If it does not exceed, then when hitting lastDelayedInstant we return delay, otherwise - the remaining time to lastInstant
                .orElseGet(() -> now.isAfter(this.lastInstant) ? Duration.ZERO
                        : now.isBefore(this.lastDelayedInstant) ? timeRangeConfig.getDelay() // NOSONAR java:S3358 Ternary operators should not be nested
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

    private boolean checkRange(@Nonnull S element) {
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
        return Instant.ofEpochMilli(instant.toEpochMilli() - instant.toEpochMilli() % timeRangeConfig.getInterval().toMillis());
    }

    private Instant getTemporalKey(@Nullable TemporalAccessor temporalAccessor) {
        return getInstantKey(Instant.from(ofNullable(temporalAccessor).orElse(Instant.MIN)));
    }

    /**
     * This function compares two existing internal objects. If their trigger times do not match, then the result of the comparison
     * is the same as the result of comparing the trigger times of the objects.
     * Otherwise, the order is taken according to the result of comparing both objects by the registered comparator
     */
    private int compare(@Nonnull S first, @Nonnull S second) {
        return first == second ? 0
             : Optional.of(instant(first).compareTo(instant(second)))
                .filter(result -> result != 0)
                .orElseGet(() -> comparator.compare(first, second));

    }

    private int compareObjects(@Nonnull Object first, @Nonnull Object second) {
        return Integer.compare(first.hashCode(), second.hashCode());
    }

    // Check that element event occurs for now
    private boolean happened(@Nonnull S element, @Nonnull Instant now) {
        return !instant(element).isAfter(now);
    }

    // Extract instant from internal element
    private @Nonnull Instant instant(@Nonnull S element) {
        return Instant.from(timeRangeConfig.getExpectation().apply(element));
    }
    
}
