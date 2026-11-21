/*
 * Copyright © 2017-2026 Andrey D. Shindarev. All rights reserved.
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
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Consumer;

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
     */
    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    // A range at least as long as this has no offset in a long of nanoseconds: its chunks are of whole seconds
    private static final Duration NANOS_LIMIT = Duration.ofNanos(Long.MAX_VALUE);
    // The longest chunk of a range of whole seconds: a moment in it fits in a long of nanoseconds
    private static final Duration LONGEST_CHUNK = Duration.ofDays(36_500);

    Duration duration;

    /**
     * The size of the internal chunk-a division of the interval: in whole seconds, or else in nanoseconds
     */
    private final long intervalSeconds;
    private final long intervalNanos;

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
     * Comparison of two internal elements objects of the same moment, null if there is none
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
    // The chunks of the elements by their numbers from the start of the range: the chunk of a number takes the moments
    // from the start of the range plus the number of intervals, up to the next one. A TreeMap goes in ascending order of
    // the numbers; a chunk keeps the moments of its elements as nanoseconds from its start (see Chunk).
    // Here we specify not the interface, but the implementation deliberately!!!
    private final TreeMap<Long, Chunk<S>> chunks = new TreeMap<>();
    // The chunk of the last element added, by its number: the elements of a call often go to one chunk
    private long lastNumber;
    private Chunk<S> lastChunk;

    /**
     * An object containing objects marked with a time-marker for the range to search for triggered
     *
     * @param time The moment limiting the region processing period (if duration is positive, then on the left, otherwise - on the right)
     * @param duration Duration of the period of the region (if negative, then to the left of instant, otherwise - to the right).
     * @param interval The intervals at which duration beats (if &gt; duration or &lt;= ZERO, then it is taken equal to duration.abs(); at least a millisecond)
     * @param completeTimeout The time after the end of the range, from which on an empty range is complete (null or negative: none)
     * @param preserver Method for converting an input element into an internal element
     * @param expectation Getting timestamp from incoming element
     * @param reducer Method for converting an internal element into a result element
     * @param comparator The order of the elements of the same time marker, which also finds their duplicates; null: the elements of a time marker are ordered by their hash codes, and equal ones are duplicates. It is called, or the hash codes and equals are, when the elements of a chunk of the range are sorted, in addElements and in extractFiredElements, and must not throw (see {@link ITimeRange#addElements(java.util.Collection)})
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
        Duration chunk = chunk(interval(Objects.requireNonNull(interval, "TimeRange::new - interval is null"), this.duration), this.duration);
        this.intervalSeconds = chunk.getNano() == 0 ? chunk.getSeconds() : 0L;
        this.intervalNanos = chunk.getNano() == 0 ? 0L : chunk.toNanos();
        this.preserver = Objects.requireNonNull(preserver, "TimeRange::new - preserver is null");
        this.expectation = Objects.requireNonNull(expectation, "TimeRange::new - expectation is null");
        this.reducer = Objects.requireNonNull(reducer, "TimeRange::new - reducer is null");
        this.startInstant = Optional.of(this.duration).filter(Duration::isNegative).map(Instant.from(time)::plus).orElseGet(() -> Instant.from(time));
        this.lastInstant = Optional.of(this.duration).filter(value -> !value.isNegative()).map(Instant.from(time)::plus).orElseGet(() -> Instant.from(time));
        this.comparator = comparator;
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
        return this.chunks.isEmpty() && isExpired(instant);
    }

    /**
     * Add the elements to the range. An element out of the range is not added and is returned. A null element is skipped,
     * and a duplicate of an element of the range is not added; neither is returned. An element that cannot be transformed
     * fails the call before any element of it is added.
     * <p>
     * The comparator of the range, or {@code hashCode} and {@code equals} without one, must not throw. It is called
     * when the elements of a chunk of the range are sorted: when its arrays are full, when it fires for the first time
     * and when a new element of a chunk that has fired is checked for a duplicate. An exception of it fails the call or
     * the extraction, and can come from an element added earlier, which stays in the range and fails the sorts of its
     * chunk again. The range stays consistent: the elements of the call added before the exception stay in it, the
     * others are not added, and an extraction that fails takes nothing out of the range.
     * @param elements collection of added elements
     * @return the elements out of the range, which are not added
     * @throws RuntimeException the exception of the transformation of an element, for example
     *         {@link java.time.DateTimeException} for a tick that is no instant: no element of the call is added; or
     *         the exception of the comparator, of {@code hashCode} or of {@code equals}
     */
    public @NonNull Collection<M> addElements(@NonNull Collection<M> elements) {
        Objects.requireNonNull(elements, "TimeRange::addElement - elements is null");
        List<M> excludes = new LinkedList<>();
        // All the elements are transformed first: an element that cannot be transformed fails the call before any
        // element of it is added
        List<S> storeElements = new ArrayList<>(elements.size());
        List<Instant> instants = new ArrayList<>(elements.size());
        for (M element : elements) {
            if (element != null) {
                S storeElement = this.preserver.apply(element); // Transform element to internal store format
                Instant instant = instant(storeElement); // The moment of the element is computed once
                // Checking for range
                if (checkRange(instant)) {
                    storeElements.add(storeElement);
                    instants.add(instant);
                } else {
                    excludes.add(element);
                }
            }
        }
        for (int i = 0; i < storeElements.size(); i++) {
            addElement(instants.get(i), storeElements.get(i));
        }
        return excludes;
    }

    // Add an element in the internal format to its chunk of the time range storage, unless it is a duplicate: an element
    // of the moment of the element that is equal to it by the comparator or, without one, by the hash code and equals
    private void addElement(@NonNull Instant instant, @NonNull S element) {
        long number = chunkNumber(instant);
        Chunk<S> chunk = this.lastChunk != null && this.lastNumber == number
                ? this.lastChunk
                : this.chunks.computeIfAbsent(number, key -> new Chunk<>(this.comparator));
        this.lastNumber = number;
        this.lastChunk = chunk;
        chunk.add(chunkMoment(instant, number), element);
        // If the element is the earliest, then mark it with Instant (a duplicate is not: its moment has an element already)
        if (instant.isBefore(ofNullable(this.nearestInstant).orElse(Instant.MAX))) {
            setNearestInstant(instant);
        }
    }

    /**
     * Extracting from the saved elements those that, according to the time marker, are considered
     * to have worked at the time passed by the parameter
     * @param instant point in time at which the check is made
     * @return List of triggered elements
     * @throws RuntimeException the exception of the comparator, of {@code hashCode} or of {@code equals} (see
     *         {@link #addElements(Collection)}), or of the reducer: no element is taken out of the range
     */
    public @NonNull Collection<R> extractFiredElements(@Nullable Instant instant) {
        Instant now = ofNullable(instant).orElseGet(Instant::now);
        List<R> result = new ArrayList<>();
        // Before the start of the range nothing has fired
        if (now.isBefore(this.startInstant) || this.chunks.isEmpty()) {
            return result;
        }
        // From the end of the range on, every chunk has fired as a whole: the moment is clamped to the range before it
        // becomes the number of a chunk and a moment in it
        boolean whole = !now.isBefore(this.lastInstant);
        long nowNumber = whole ? Long.MAX_VALUE : chunkNumber(now);
        // The elements that fire are found first, with the comparisons, then reduced, and only then taken out of the
        // chunks: an exception of the comparator or of the reducer leaves every element in the range
        List<Chunk<S>.Firing> firings = new ArrayList<>();
        // Since TreeMap, the head map goes in ascending order of the numbers: the chunks before the chunk of the current
        // moment have fired as a whole
        Map<Long, Chunk<S>> due = this.chunks.headMap(nowNumber, false);
        for (Chunk<S> dueChunk : due.values()) {
            firings.add(dueChunk.prepare(Long.MAX_VALUE));
        }
        // The elements of the chunk of the current moment up to the moment have fired, the others stay in the chunk
        Chunk<S> chunk = whole ? null : this.chunks.get(nowNumber);
        if (chunk != null) {
            firings.add(chunk.prepare(chunkMoment(now, nowNumber)));
        }
        Consumer<S> fired = element -> result.add(this.reducer.apply(element));
        for (Chunk<S>.Firing firing : firings) {
            if (firing != null) {
                firing.forEach(fired);
            }
        }
        for (Chunk<S>.Firing firing : firings) {
            if (firing != null) {
                firing.commit();
            }
        }
        due.clear();
        if (chunk != null && chunk.isEmpty()) {
            this.chunks.remove(nowNumber);
        }
        // A chunk that has fired as a whole is no longer in the map
        if (this.lastChunk != null && this.lastChunk.isEmpty()) {
            this.lastChunk = null;
        }
        // Rows were deleted, and it is required to recalculate the time of the nearest element (if any)
        setNearestInstant(findNearestInstant());
        return result;
    }

    private void setNearestInstant(@Nullable Instant nearestInstant) {
        this.nearestInstant = nearestInstant;
    }

    private Instant findNearestInstant() {
        Map.Entry<Long, Chunk<S>> first = this.chunks.firstEntry();
        return first == null ? null : instant(first.getKey(), first.getValue().firstMoment());
    }

    /**
     * The time from the moment to the next event of the range: with elements, the moment of the nearest of them;
     * without elements, the start of the range before it, and the end of the range plus its completeTimeout after it
     * @param now point in time for which we calculate the value
     * @return the time to the next event, zero if it has come
     */
    public @NonNull Duration duration(@NonNull Instant now) {
        // If the time is before the start of the range
        if (now.isBefore(this.startInstant)) {
            return durationToStart(now);
        // If the start time of the range has passed and there are no elements
        } else if (this.chunks.isEmpty()) {
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
        this.chunks.clear();
        this.lastChunk = null;
        setNearestInstant(null);
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

    private boolean checkRange(@NonNull Instant instant) {
        return !this.startInstant.isAfter(instant) && this.lastInstant.isAfter(instant);
    }

    // The interval of the javadoc: the absolute duration instead of an interval that is not positive or longer than the
    // duration, and at least a millisecond, the unit of the keys of the chunks
    private static @NonNull Duration interval(@NonNull Duration interval, @NonNull Duration duration) {
        Duration range = duration.abs();
        Duration chunk = interval.isNegative() || interval.isZero() || interval.compareTo(range) > 0 ? range : interval;
        return chunk.toMillis() < 1 ? Duration.ofMillis(1) : chunk;
    }

    // The chunk of a range at least as long as a long of nanoseconds is of whole seconds and at most a century: a moment
    // in it fits in a long of nanoseconds. The chunks are an internal division, which changes no result of the range.
    private static @NonNull Duration chunk(@NonNull Duration interval, @NonNull Duration duration) {
        if (duration.abs().compareTo(NANOS_LIMIT) < 0) {
            return interval;
        }
        Duration chunk = interval.compareTo(LONGEST_CHUNK) > 0 ? LONGEST_CHUNK : interval;
        return Duration.ofSeconds(Math.max(chunk.getSeconds(), 1L));
    }

    /**
     * The number of the chunk of a moment of the range: the number of whole intervals from the start of the range
     * @param instant a moment of the range
     * @return the number of the chunk
     */
    private long chunkNumber(@NonNull Instant instant) {
        long seconds = instant.getEpochSecond() - this.startInstant.getEpochSecond();
        int nanos = instant.getNano() - this.startInstant.getNano();
        if (nanos < 0) {
            seconds--;
            nanos += NANOS_PER_SECOND;
        }
        return this.intervalSeconds > 0
                ? seconds / this.intervalSeconds
                : (seconds * NANOS_PER_SECOND + nanos) / this.intervalNanos;
    }

    /**
     * The moment in its chunk: the nanoseconds from the start of the chunk, never negative and less than the interval
     * @param instant a moment of the range
     * @param number the number of the chunk of the moment
     * @return the nanoseconds from the start of the chunk
     */
    private long chunkMoment(@NonNull Instant instant, long number) {
        long seconds = instant.getEpochSecond() - this.startInstant.getEpochSecond();
        int nanos = instant.getNano() - this.startInstant.getNano();
        if (nanos < 0) {
            seconds--;
            nanos += NANOS_PER_SECOND;
        }
        return this.intervalSeconds > 0
                ? (seconds - number * this.intervalSeconds) * NANOS_PER_SECOND + nanos
                : seconds * NANOS_PER_SECOND + nanos - number * this.intervalNanos;
    }

    // The moment of the nanoseconds from the start of a chunk
    private @NonNull Instant instant(long number, long moment) {
        return this.intervalSeconds > 0
                ? this.startInstant.plusSeconds(number * this.intervalSeconds).plusNanos(moment)
                : this.startInstant.plusNanos(number * this.intervalNanos + moment);
    }

    // Extract instant from internal element
    private @NonNull Instant instant(@NonNull S element) {
        return Instant.from(this.expectation.apply(element));
    }

}
