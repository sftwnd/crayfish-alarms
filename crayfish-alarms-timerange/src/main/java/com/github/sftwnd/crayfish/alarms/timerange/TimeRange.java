/*
 * Copyright © 2017-2023 Andrey D. Shindarev. All rights reserved.
 * This program is made available under the terms of the BSD 3-Clause License.
 * Contacts: ashindarev@gmail.com
 */
package com.github.sftwnd.crayfish.alarms.timerange;

import com.github.sftwnd.crayfish.common.expectation.TemporalExtractor;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
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
class TimeRange<M,S,R> implements ITimeRange<M,R> {

    /*
        Used sonar warnings:
            java:S107 Methods should not have too many params
            java:S3358 Ternary operators should not be nested
            java:S3864 "Stream.peek" should be used with caution
     */
    Duration duration;

    /**
     * The size of the internal chunk-a division of the interval
     */
    private final Duration interval;

    /**
     * Constructor for the internal storage object from the incoming element
     */
    private final Transformer<M,S> preserver;

    /**
     * Getting the date from the preserved element
     */
    private final TemporalExtractor<S,? extends TemporalAccessor> expectation;

    /**
     * Getting result element from internal storage
     */
    private final Transformer<S,R> reducer;
    /**
     * Comparison of two internal elements objects
     */
    private final Comparator<? super S> comparator;

    // Beginning of the region validity period
    private final Instant startInstant;
    // Upper limit of the interval (exclude...)
    private final Instant lastInstant;
    // Last instant plus completion delay
    private final Instant completeInstant;

    // The moment of the nearest element. In case of absence - null
    private Instant nearestInstant = null;
    // A set of elements distributed over ranges of size interval
    // TreeMap storage structure that guarantees ascending traversal order
    // The internal elements are contained in a TreeSet, which also guarantees order.
    // Here we specify not the interface, but the implementation deliberately!!!
    private final TreeMap<Instant, TreeSet<S>> expectedMap = new TreeMap<>();

    /**
     * An object containing objects marked with a time-marker for the range to search for triggered
     *
     * @param time The moment limiting the region processing period (if duration is positive, then on the left, otherwise - on the right)
     * @param duration Duration of the period of the region (if negative, then to the left of instant, otherwise - to the right).
     * @param interval The intervals at which duration beats (if &gt; duration or &lt;= ZERO, then it is taken equal to duration.abs())
     * @param completeTimeout At a specified interval after the end of the described range, if there are no processed objects, the actor stops
     * @param preserver Method for converting an input element into an internal element
     * @param expectation Getting timestamp from incoming element
     * @param reducer Method for converting an internal element into a result element
     * @param comparator Redefining a comparator to order Expected objects not only in temporal ascending order, but also in internal content
     */
    @SuppressWarnings("java:S107")
    TimeRange(
            @NonNull  TemporalAccessor time,
            @NonNull  Duration duration,
            @NonNull  Duration interval,
            @Nullable Duration completeTimeout,
            @NonNull  Transformer<M,S> preserver,
            @NonNull  TemporalExtractor<S,? extends TemporalAccessor> expectation,
            @NonNull  Transformer<S,R> reducer,
            @Nullable Comparator<? super S> comparator
    ) {
        Objects.requireNonNull(time, "TimeRange::new - time is null");
        this.duration = Objects.requireNonNull(duration, "TimeRange::new - duration is null");
        this.interval = Objects.requireNonNull(interval, "TimeRange::new - interval is null");
        this.preserver = Objects.requireNonNull(preserver, "TimeRange::new - preserver is null");
        this.expectation = Objects.requireNonNull(expectation, "TimeRange::new - expectation is null");
        this.reducer = Objects.requireNonNull(reducer, "TimeRange::new - reducer is null");
        this.startInstant = Optional.of(this.duration).filter(Duration::isNegative).map(Instant.from(time)::plus).orElseGet(() -> Instant.from(time));
        this.lastInstant = Optional.of(this.duration).filter(value -> !value.isNegative()).map(Instant.from(time)::plus).orElseGet(() -> Instant.from(time));
        this.comparator = ofNullable(comparator).orElse(this::compareObjects);
        this.completeInstant = this.lastInstant.plus(Optional.ofNullable(completeTimeout).filter(value -> !value.isNegative()).orElse(Duration.ZERO));
    }

    @NonNull public Instant getStartInstant() {
        return this.startInstant;
    }

    @NonNull public Instant getLastInstant() {
        return this.lastInstant;
    }

    /**
     * The time interval, taking into account completeTimeout, has been exhausted by the transmitted moment
     * @param instant point in time at which the check is made
     * @return true if exhausted or false otherwise
     */
    public boolean isExpired(@Nullable Instant instant) {
        return !ofNullable(instant).orElseGet(Instant::now)
                .isBefore(this.completeInstant);
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
    public @NonNull Collection<M> addElements(@NonNull Collection<M> elements) {
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
    private void addElements(@NonNull Stream<S> stream) {
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
                ).forEach((key,value) -> this.expectedMap.merge(key, value, (set, added) -> {
                    // If present, expand (if missing, the set is added)
                    set.addAll(added);
                    return set;
                }));
    }

    /**
     * Extracting from the saved elements those that, according to the time marker, are considered
     * to have worked at the time passed by the parameter
     * @param instant point in time at which the check is made
     * @return List of triggered elements
     */
    public @NonNull Collection<R> extractFiredElements(@Nullable Instant instant) {
        Instant now = ofNullable(instant).orElseGet(Instant::now);
        // The key corresponding to the current moment
        Instant nowKey = getInstantKey(now);
        List<R> result = new ArrayList<>();
        // Since TreeMap, the head map goes in ascending order of the instantKey key: the chunks before the chunk of the
        // current moment have fired as a whole
        Iterator<TreeSet<S>> chunks = this.expectedMap.headMap(nowKey, false).values().iterator();
        while (chunks.hasNext()) {
            chunks.next().stream().map(this.reducer).forEach(result::add);
            chunks.remove();
        }
        // The elements of the chunk of the current moment are in ascending order of their time: the first of them have
        // fired, the others stay in the chunk
        TreeSet<S> chunk = this.expectedMap.get(nowKey);
        if (chunk != null) {
            while (!chunk.isEmpty() && happened(chunk.first(), now)) {
                result.add(this.reducer.apply(chunk.pollFirst()));
            }
            if (chunk.isEmpty()) {
                this.expectedMap.remove(nowKey);
            }
        }
        // Rows were deleted, and it is required to recalculate the time of the nearest element (if any)
        setNearestInstant(findNearestInstant());
        return result;
    }

    private void setNearestInstant(@Nullable Instant nearestInstant) {
        this.nearestInstant = nearestInstant;
    }

    private Instant findNearestInstant() {
        return ofNullable(this.expectedMap.firstEntry())
                .map(Map.Entry::getValue)
                .map(TreeSet::first)
                .map(this::instant)
                .orElse(null);
    }

    /**
     * Timeout until the nearest available Expected, but not less than delay, and if not, until the next time limit -
     * either startInstant or lastInstant + completeDuration
     * @param now point in time for which we calculate the value
     * @return timeout to the nearest event, taking into account delay
     */
    public @NonNull Duration duration(@NonNull Instant now) {
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
     * Remove all alarms from the TimeRange
     */
    @Override
    public void clean() {
        this.expectedMap.clear();
    }

    // Time until the moment after lastInstant by completeTimeout duration. If after this point we are in COMPLETE,
    // then the actor ends.
    // This time is given by AKKA System to deliver the message with the processing order to us. The fact is that it is not
    // supposed to receive tasks for processing after the moment of their occurrence. This actor only accepts messages for the future
    private @NonNull Duration durationToStop(Instant now) {
        return durationTo(this.completeInstant, now);
    }

    // The time from the specified moment until the first element fires, and in case of absence - until the end of the range of the current key
    private @NonNull Duration durationToExpect(@NonNull Instant now) {
        // If the nearest element is defined, then we wait until its moment, otherwise - until the end of the range time
        // P.S. - maybe you have to wait until the next key... It seems that there are no grounds yet, but there was a single hang-up precedent -
        //        previously associated with the use of BalancingPool, which is not supported by this Actor !!!.
        //        If it repeats, you need to look in the direction of optimizing durationToExpect
        return durationTo( // We take Delay to the nearest element, and if it is not there, then to the end of the range
                ofNullable(this.nearestInstant).orElse(this.lastInstant), now);
    }

    // The time from the specified moment until the first element is triggered, and in case of absence - until the start of the range activation
    private @NonNull Duration durationToStart(@NonNull Instant now) {
        return durationTo(
                ofNullable(this.nearestInstant).orElse(this.startInstant),
                now);
    }

    // Time until the specified moment from the moment of the now parameter
    private static @NonNull Duration durationTo(@NonNull Instant instant, @NonNull Instant now) {
        return instant.isAfter(now)
                ? Duration.between(now, instant)
                : Duration.ZERO;
    }

    private boolean checkRange(@NonNull S element) {
        return Optional.of(element)
                .map(this::instant)
                .filter(instant -> !this.startInstant.isAfter(instant))
                .filter(this.lastInstant::isAfter)
                .isPresent();
    }

    /**
     * Rounds the given instant to the beginning of the interval
     * @param instant The moment at which it is necessary to determine the key of the polling period
     * @return moment describing the range of the polling period
     */
    private Instant getInstantKey(@NonNull Instant instant) {
        return Instant.ofEpochMilli(instant.toEpochMilli() - instant.toEpochMilli() % this.interval.toMillis());
    }

    private Instant getTemporalKey(@Nullable TemporalAccessor temporalAccessor) {
        return getInstantKey(Instant.from(ofNullable(temporalAccessor).orElse(Instant.MIN)));
    }

    /**
     * This function compares two existing internal objects. If their trigger times do not match, then the result of the comparison
     * is the same as the result of comparing the trigger times of the objects.
     * Otherwise, the order is taken according to the result of comparing both objects by the registered comparator
     */
    private int compare(@NonNull S first, @NonNull S second) {
        return first == second ? 0
             : Optional.of(instant(first).compareTo(instant(second)))
                .filter(result -> result != 0)
                .orElseGet(() -> comparator.compare(first, second));

    }

    private int compareObjects(@NonNull Object first, @NonNull Object second) {
        return Integer.compare(first.hashCode(), second.hashCode());
    }

    // Check that element event occurs for now
    private boolean happened(@NonNull S element, @NonNull Instant now) {
        return !instant(element).isAfter(now);
    }

    // Extract instant from internal element
    private @NonNull Instant instant(@NonNull S element) {
        return Instant.from(this.expectation.apply(element));
    }
    
}
