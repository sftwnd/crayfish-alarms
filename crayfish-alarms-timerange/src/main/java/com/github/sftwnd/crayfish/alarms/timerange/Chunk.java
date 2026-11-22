/*
 * Copyright © 2017-2026 Andrey D. Shindarev. All rights reserved.
 * This program is made available under the terms of the BSD 3-Clause License.
 * Contacts: ashindarev@gmail.com
 */
package com.github.sftwnd.crayfish.alarms.timerange;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

import java.util.Arrays;
import java.util.Comparator;
import java.util.function.Consumer;

/**
 * The elements of a chunk of a time range with their moments, the nanoseconds from the start of the chunk. The moments
 * and the elements are kept in two arrays, a {@code long} and a reference per element, instead of an object per element.
 * <p>
 * The order of the elements is the one of their moments, then of the comparator or, without one, of their hash codes,
 * then of their addition. An element of a moment that is equal to a waiting element of the moment by the comparator or,
 * without one, by its hash code and {@code equals}, is a duplicate: the element added first stays.
 * <p>
 * A cold chunk, which has fired nothing yet, appends the elements in the order of their addition: the sorted prefix
 * and the appended tail are merged, and the duplicates dropped, when the arrays are full and when the chunk fires for the
 * first time. A hot chunk, which has fired, keeps its sorted elements, finds the duplicates of a new element among them
 * by a binary search and keeps the new elements in a binary heap, which is merged into the sorted elements when it
 * reaches a quarter of them: an extraction from the chunk of the current moment costs no sort of the chunk.
 * <p>
 * A chunk with many elements, a cold one whose arrays are full and a hot one whose heap is due to be merged, is split
 * into parts of its interval, each a chunk of its own, which is split again when it has many elements: an extraction
 * sorts the elements of the parts that fire, not the elements of the chunk, and a merge of the heap takes the elements
 * of a part. The parts fire in the order of their moments, so their elements are not merged.
 * <p>
 * An exception of the comparator, or of {@code hashCode} or {@code equals} without one, leaves the elements of the
 * chunk as they were: the sorts and the merges work on copies, the heap is ordered by the moments and the numbers of
 * the addition without comparisons, a split compares no elements, and an extraction finds the elements that fire, with
 * their comparisons, before it takes them out (see {@link #prepare(long)}).
 * @param <S> Element type of the internal storage
 */
final class Chunk<S> {

    private static final int INITIAL_CAPACITY = 4;
    // the elements of a run shorter than this are sorted by insertions
    private static final int INSERTION_SORT = 32;
    // the heap of the new elements of a hot chunk is merged into its sorted elements at a quarter of them, or at this size
    private static final int LATE_MINIMUM = 64;
    // a chunk with this many elements is split into parts, when its arrays are full or its heap is due to be merged
    private static final int SPLIT_SIZE = 4096;
    // the number of the parts of a split chunk
    private static final int PART_COUNT = 16;
    private static final long[] NO_MOMENTS = new long[0];
    private static final Object[] NO_ELEMENTS = new Object[0];

    /**
     * The order of the elements of a moment; without a comparator, the order of their hash codes
     */
    private final Comparator<? super S> comparator;
    // The nanoseconds of the interval of the chunk: its moments are less than it
    private final long span;
    // The number of the elements from which the chunk is split
    private final int splitSize;

    // The sorted elements [head, sorted) without duplicates, then the elements appended to a cold chunk [sorted, size)
    private long[] moments;
    private Object[] elements;
    private int head;
    private int sorted;
    private int size;
    // The earliest moment of a cold chunk
    private long minimum = Long.MAX_VALUE;
    private boolean hot;

    // The binary heap of the elements added to a hot chunk, with the numbers of their addition: it is ordered by the
    // moments and the numbers only, without a comparison of the elements
    private long[] lateMoments;
    private Object[] lateElements;
    private int[] lateNumbers;
    private int lateSize;
    private int lateNumber;

    // The parts of a split chunk, of partLength nanoseconds each, the last one shorter maybe: a part without elements is
    // null, and the parts before firstPart have none
    private Chunk<S>[] parts;
    private long partLength;
    private int firstPart;

    /**
     * A chunk without elements
     * @param comparator The order of the elements of a moment, which also finds their duplicates; null: the elements of
     *                   a moment are ordered by their hash codes, and equal ones are duplicates
     */
    Chunk(@Nullable Comparator<? super S> comparator) {
        this(comparator, Long.MAX_VALUE);
    }

    /**
     * A chunk of an interval without elements
     * @param comparator The order of the elements of a moment, which also finds their duplicates; null: the elements of
     *                   a moment are ordered by their hash codes, and equal ones are duplicates
     * @param span the nanoseconds of the interval of the chunk: the moments of its elements are less than it
     */
    Chunk(@Nullable Comparator<? super S> comparator, long span) {
        this(comparator, span, SPLIT_SIZE, INITIAL_CAPACITY);
    }

    /**
     * A chunk of an interval without elements, which is split from a number of elements on
     * @param comparator The order of the elements of a moment, which also finds their duplicates; null: the elements of
     *                   a moment are ordered by their hash codes, and equal ones are duplicates
     * @param span the nanoseconds of the interval of the chunk: the moments of its elements are less than it
     * @param splitSize the number of the elements from which the chunk is split into parts
     */
    Chunk(@Nullable Comparator<? super S> comparator, long span, int splitSize) {
        this(comparator, span, splitSize, INITIAL_CAPACITY);
    }

    private Chunk(@Nullable Comparator<? super S> comparator, long span, int splitSize, int capacity) {
        this.comparator = comparator;
        this.span = span;
        this.splitSize = splitSize;
        this.moments = new long[capacity];
        this.elements = new Object[capacity];
    }

    /**
     * Adds an element, unless it is a duplicate
     * @param moment the nanoseconds from the start of the chunk
     * @param element the element
     */
    void add(long moment, @NonNull S element) {
        if (this.parts != null) {
            addToPart(moment, element);
            return;
        }
        if (this.hot) {
            addLate(moment, element);
            return;
        }
        if (this.size == this.moments.length) {
            if (this.size >= this.splitSize && canSplit()) {
                split();
                addToPart(moment, element);
                return;
            }
            compact();
            // the arrays grow when they are still full, or when the duplicates have not freed a quarter of them
            int capacity = this.moments.length;
            if (this.size == capacity || this.size > capacity - (capacity >> 2)) {
                grow(capacity + Math.max(capacity >> 1, INITIAL_CAPACITY));
            }
        }
        this.moments[this.size] = moment;
        this.elements[this.size++] = element;
        if (moment < this.minimum) {
            this.minimum = moment;
        }
    }

    /**
     * @return true if the chunk has no element
     */
    boolean isEmpty() {
        return this.parts != null ? this.firstPart == PART_COUNT : this.head == this.size && this.lateSize == 0;
    }

    /**
     * @return true if the chunk is split into parts
     */
    boolean isSplit() {
        return this.parts != null;
    }

    /**
     * @return the number of the elements kept: the appended elements of a cold chunk and the heap of a hot one can have
     *         duplicates that are not dropped yet
     */
    int size() {
        if (this.parts != null) {
            int count = 0;
            for (Chunk<S> part : this.parts) {
                count += part == null ? 0 : part.size();
            }
            return count;
        }
        return this.size - this.head + this.lateSize;
    }

    /**
     * @return the earliest moment of the elements, {@link Long#MAX_VALUE} without elements
     */
    long firstMoment() {
        if (this.parts != null) {
            return this.firstPart == PART_COUNT
                    ? Long.MAX_VALUE
                    : this.firstPart * this.partLength + this.parts[this.firstPart].firstMoment();
        }
        if (!this.hot) {
            return this.minimum;
        }
        long first = this.head < this.size ? this.moments[this.head] : Long.MAX_VALUE;
        return this.lateSize > 0 && this.lateMoments[0] < first ? this.lateMoments[0] : first;
    }

    /**
     * Takes the elements with a moment up to the limit out of the chunk in their order
     * @param limit the latest moment of the elements to take
     * @param consumer the consumer of the elements taken
     */
    void fire(long limit, @NonNull Consumer<? super S> consumer) {
        Firing firing = prepare(limit);
        if (firing != null) {
            firing.forEach(consumer);
            firing.commit();
        }
    }

    /**
     * Finds the elements with a moment up to the limit in their order, without taking them out of the chunk: an
     * exception of the comparator leaves the elements of the chunk as they were. Nothing may change the chunk until the
     * firing is committed.
     * @param limit the latest moment of the elements to fire
     * @return the elements to fire, null without any
     */
    @Nullable Firing prepare(long limit) {
        if (this.parts != null) {
            return prepareParts(limit);
        }
        if (!heat(limit)) {
            return null;
        }
        int end = this.head;
        while (end < this.size && this.moments[end] <= limit) {
            end++;
        }
        int late = countLate(0, limit);
        if (late == 0) {
            // the sorted elements have no duplicates: they fire as they are
            return end == this.head ? null : new Firing(end, 0, this.elements, this.head, end);
        }
        if (late == 1 && end == this.head) {
            // the first element of the heap alone fires as it is
            return new Firing(end, 1, this.lateElements, 0, 1);
        }
        // the due elements of the heap in their order, merged with the due sorted elements without the duplicates
        long[] dueMoments = new long[late];
        Object[] dueElements = new Object[late];
        int[] dueNumbers = new int[late];
        collectLate(0, limit, dueMoments, dueElements, dueNumbers, 0);
        sort(dueMoments, dueElements, dueNumbers, 0, late);
        Object[] firedElements = new Object[end - this.head + late];
        int fired = mergeDistinct(this.moments, this.elements, this.head, end,
                dueMoments, dueElements, 0, late, null, firedElements);
        return new Firing(end, late, firedElements, 0, fired);
    }

    // The number of the elements of the heap from the index on with a moment up to the limit: the moment of an element
    // of the heap is never earlier than the one of its parent
    private int countLate(int index, long limit) {
        if (index >= this.lateSize || this.lateMoments[index] > limit) {
            return 0;
        }
        return 1 + countLate((index << 1) + 1, limit) + countLate((index << 1) + 2, limit);
    }

    // Copies the elements of the heap from the index on with a moment up to the limit to the arrays from the position on
    private int collectLate(int index, long limit, long[] dueMoments, Object[] dueElements, int[] dueNumbers, int position) {
        if (index >= this.lateSize || this.lateMoments[index] > limit) {
            return position;
        }
        dueMoments[position] = this.lateMoments[index];
        dueElements[position] = this.lateElements[index];
        dueNumbers[position] = this.lateNumbers[index];
        int next = collectLate((index << 1) + 1, limit, dueMoments, dueElements, dueNumbers, position + 1);
        return collectLate((index << 1) + 2, limit, dueMoments, dueElements, dueNumbers, next);
    }

    // The firings of the parts up to the limit, in the order of the parts: the parts are not changed until the firing is
    // committed
    @SuppressWarnings("unchecked")
    private @Nullable Firing prepareParts(long limit) {
        int last = limit / this.partLength >= PART_COUNT ? PART_COUNT - 1 : (int) (limit / this.partLength);
        Firing[] firings = null;
        int[] indexes = null;
        int count = 0;
        for (int index = this.firstPart; index <= last; index++) {
            Chunk<S> part = this.parts[index];
            Firing firing = part == null ? null : part.prepare(limit - index * this.partLength);
            if (firing != null) {
                if (firings == null) {
                    firings = (Firing[]) new Chunk<?>.Firing[last - index + 1];
                    indexes = new int[last - index + 1];
                }
                firings[count] = firing;
                indexes[count++] = index;
            }
        }
        return count == 0 ? null : new Firing(firings, indexes, count);
    }

    private boolean canSplit() {
        return this.span >= PART_COUNT;
    }

    // The element goes to the part of its moment, a new part if the part has no elements
    private void addToPart(long moment, @NonNull S element) {
        int index = (int) (moment / this.partLength);
        Chunk<S> part = this.parts[index];
        if (part == null) {
            part = new Chunk<>(this.comparator, partSpan(index), this.splitSize, INITIAL_CAPACITY);
            this.parts[index] = part;
            if (index < this.firstPart) {
                this.firstPart = index;
            }
        }
        part.add(moment - index * this.partLength, element);
    }

    // The nanoseconds of the interval of a part: the last part ends with the chunk
    private long partSpan(int index) {
        return Math.min(this.partLength, this.span - index * this.partLength);
    }

    // Splits the chunk into parts of its interval: its elements go to the parts of their moments in the order of their
    // addition, the sorted ones and then the heap by the numbers of the addition, so that a part keeps the element added
    // first of the duplicates. No element is compared: a split cannot fail. A part with many elements is split again.
    @SuppressWarnings("unchecked")
    private void split() {
        long length = this.span / PART_COUNT + (this.span % PART_COUNT == 0L ? 0L : 1L);
        int[] lateOrder = lateOrder();
        int[] counts = new int[PART_COUNT];
        for (int i = this.head; i < this.size; i++) {
            counts[(int) (this.moments[i] / length)]++;
        }
        for (int index : lateOrder) {
            counts[(int) (this.lateMoments[index] / length)]++;
        }
        this.partLength = length;
        this.parts = (Chunk<S>[]) new Chunk<?>[PART_COUNT];
        this.firstPart = PART_COUNT;
        for (int index = PART_COUNT - 1; index >= 0; index--) {
            if (counts[index] > 0) {
                this.parts[index] = new Chunk<>(this.comparator, partSpan(index), this.splitSize, counts[index]);
                this.firstPart = index;
            }
        }
        for (int i = this.head; i < this.size; i++) {
            appendToPart(this.moments[i], this.elements[i]);
        }
        for (int index : lateOrder) {
            appendToPart(this.lateMoments[index], this.lateElements[index]);
        }
        for (Chunk<S> part : this.parts) {
            if (part != null && part.size >= this.splitSize && part.canSplit()) {
                part.split();
            }
        }
        this.moments = NO_MOMENTS;
        this.elements = NO_ELEMENTS;
        this.head = 0;
        this.sorted = 0;
        this.size = 0;
        this.lateMoments = null;
        this.lateElements = null;
        this.lateNumbers = null;
        this.lateSize = 0;
        this.lateNumber = 0;
        this.hot = false;
    }

    // The indexes of the heap in the order of the numbers of the addition
    private int[] lateOrder() {
        long[] keys = new long[this.lateSize];
        for (int i = 0; i < this.lateSize; i++) {
            keys[i] = ((long) this.lateNumbers[i] << 32) | i;
        }
        Arrays.sort(keys);
        int[] order = new int[this.lateSize];
        for (int i = 0; i < this.lateSize; i++) {
            order[i] = (int) keys[i];
        }
        return order;
    }

    // Appends an element to the part of its moment, made with room for its elements
    private void appendToPart(long moment, Object element) {
        int index = (int) (moment / this.partLength);
        Chunk<S> part = this.parts[index];
        long partMoment = moment - index * this.partLength;
        part.moments[part.size] = partMoment;
        part.elements[part.size++] = element;
        if (partMoment < part.minimum) {
            part.minimum = partMoment;
        }
    }

    // A cold chunk that has an element up to the limit becomes hot: its elements are sorted, the duplicates dropped.
    // A cold chunk with nothing to fire stays cold, and its new elements are appended.
    private boolean heat(long limit) {
        if (!this.hot) {
            if (this.minimum > limit) {
                return false;
            }
            compact();
            this.hot = true;
        }
        return true;
    }

    // Sorts the appended elements of a cold chunk, merges them with the sorted ones and drops the duplicates. The sort
    // works on a copy of the appended elements, and the merge into the arrays of the chunk puts them back if it fails:
    // an exception of the comparator leaves the chunk as it was.
    private void compact() {
        if (this.sorted < this.size) {
            int appended = this.size - this.sorted;
            long[] appendedMoments = Arrays.copyOfRange(this.moments, this.sorted, this.size);
            Object[] appendedElements = Arrays.copyOfRange(this.elements, this.sorted, this.size);
            sort(appendedMoments, appendedElements, null, 0, appended);
            // the sorted elements were added before the appended ones, and go first among the elements of their order
            long[] prefixMoments = Arrays.copyOf(this.moments, this.sorted);
            Object[] prefixElements = Arrays.copyOf(this.elements, this.sorted);
            int count = -1;
            try {
                count = mergeDistinct(prefixMoments, prefixElements, 0, this.sorted,
                        appendedMoments, appendedElements, 0, appended, this.moments, this.elements);
            } finally {
                if (count < 0) {
                    // the sorted elements and the appended ones, now in their order, are put back
                    System.arraycopy(prefixMoments, 0, this.moments, 0, this.sorted);
                    System.arraycopy(prefixElements, 0, this.elements, 0, this.sorted);
                    System.arraycopy(appendedMoments, 0, this.moments, this.sorted, appended);
                    System.arraycopy(appendedElements, 0, this.elements, this.sorted, appended);
                }
            }
            for (int i = count; i < this.size; i++) {
                this.elements[i] = null;
            }
            this.size = count;
            this.sorted = count;
        }
    }

    // The arrays of a cold chunk with room for more elements
    private void grow(int capacity) {
        long[] extendedMoments = new long[capacity];
        Object[] extendedElements = new Object[capacity];
        System.arraycopy(this.moments, 0, extendedMoments, 0, this.size);
        System.arraycopy(this.elements, 0, extendedElements, 0, this.size);
        this.moments = extendedMoments;
        this.elements = extendedElements;
    }

    // An element added to a hot chunk goes to the heap, unless it is a duplicate of a sorted element. A full heap is
    // merged first, so that the element is checked against the merged elements too.
    private void addLate(long moment, @NonNull S element) {
        if (this.lateSize == 0) {
            // an empty heap numbers its elements again
            this.lateNumber = 0;
        } else if (this.lateNumber == Integer.MAX_VALUE
                || this.lateSize == this.lateMoments.length && this.lateSize >= Math.max(LATE_MINIMUM, (this.size - this.head) >> 2)) {
            if (this.size - this.head + this.lateSize >= this.splitSize && canSplit()) {
                split();
                addToPart(moment, element);
                return;
            }
            mergeLate();
        }
        if (isSortedDuplicate(moment, element)) {
            return;
        }
        if (this.lateMoments == null) {
            this.lateMoments = new long[INITIAL_CAPACITY];
            this.lateElements = new Object[INITIAL_CAPACITY];
            this.lateNumbers = new int[INITIAL_CAPACITY];
        } else if (this.lateSize == this.lateMoments.length) {
            int extended = this.lateSize << 1;
            long[] extendedMoments = new long[extended];
            Object[] extendedElements = new Object[extended];
            int[] extendedNumbers = new int[extended];
            System.arraycopy(this.lateMoments, 0, extendedMoments, 0, this.lateSize);
            System.arraycopy(this.lateElements, 0, extendedElements, 0, this.lateSize);
            System.arraycopy(this.lateNumbers, 0, extendedNumbers, 0, this.lateSize);
            this.lateMoments = extendedMoments;
            this.lateElements = extendedElements;
            this.lateNumbers = extendedNumbers;
        }
        // sift up: the new element has the greatest number, and goes after the elements of its moment
        int index = this.lateSize++;
        int number = this.lateNumber++;
        while (index > 0) {
            int parent = (index - 1) >>> 1;
            if (moment >= this.lateMoments[parent]) {
                break;
            }
            setLate(index, this.lateMoments[parent], this.lateElements[parent], this.lateNumbers[parent]);
            index = parent;
        }
        setLate(index, moment, element, number);
    }

    // A binary search of the order of the element among the sorted elements, then of an equal one among the elements of
    // its order
    private boolean isSortedDuplicate(long moment, @NonNull Object element) {
        int low = this.head;
        int high = this.size;
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (compare(this.moments[middle], this.elements[middle], moment, element) < 0) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        for (int i = low; i < this.size && this.moments[i] == moment && tie(this.elements[i], element) == 0; i++) {
            if (this.comparator != null || this.elements[i].equals(element)) {
                return true;
            }
        }
        return false;
    }

    private void setLate(int index, long moment, Object element, int number) {
        this.lateMoments[index] = moment;
        this.lateElements[index] = element;
        this.lateNumbers[index] = number;
    }

    // The heap goes into the sorted elements: they were added before it, and go first among the elements of their order.
    // The sort and the merge work on copies, which the chunk takes at the end: an exception of the comparator leaves the
    // chunk as it was.
    private void mergeLate() {
        long[] sortedMoments = Arrays.copyOf(this.lateMoments, this.lateSize);
        Object[] sortedElements = Arrays.copyOf(this.lateElements, this.lateSize);
        int[] sortedNumbers = Arrays.copyOf(this.lateNumbers, this.lateSize);
        sort(sortedMoments, sortedElements, sortedNumbers, 0, this.lateSize);
        int capacity = Math.max(this.size - this.head + this.lateSize, INITIAL_CAPACITY);
        long[] mergedMoments = new long[capacity];
        Object[] mergedElements = new Object[capacity];
        int count = mergeDistinct(this.moments, this.elements, this.head, this.size,
                sortedMoments, sortedElements, 0, this.lateSize, mergedMoments, mergedElements);
        this.moments = mergedMoments;
        this.elements = mergedElements;
        this.head = 0;
        this.sorted = count;
        this.size = count;
        this.lateMoments = null;
        this.lateElements = null;
        this.lateNumbers = null;
        this.lateSize = 0;
        this.lateNumber = 0;
    }

    // Merges two sorted runs into the target from its start without the duplicates: the first run goes first among the
    // elements of its order. The target is neither of the runs; without target moments only the elements are merged.
    @SuppressWarnings("java:S107")
    private int mergeDistinct(long[] firstMoments, Object[] firstElements, int first, int firstEnd,
                              long[] secondMoments, Object[] secondElements, int second, int secondEnd,
                              @Nullable long[] targetMoments, Object[] targetElements) {
        int target = 0;
        // the merged elements of the moment of the last one start here: a duplicate is one of them
        int group = 0;
        long groupMoment = 0L;
        while (first < firstEnd || second < secondEnd) {
            long moment;
            Object element;
            if (second == secondEnd || first < firstEnd
                    && compare(firstMoments[first], firstElements[first], secondMoments[second], secondElements[second]) <= 0) {
                moment = firstMoments[first];
                element = firstElements[first++];
            } else {
                moment = secondMoments[second];
                element = secondElements[second++];
            }
            if (target == group || moment != groupMoment) {
                group = target;
                groupMoment = moment;
            }
            if (!isMergedDuplicate(targetElements, group, target, element)) {
                if (targetMoments != null) {
                    targetMoments[target] = moment;
                }
                targetElements[target++] = element;
            }
        }
        return target;
    }

    // An element is a duplicate of a merged element of its moment and order: they are the last ones merged
    private boolean isMergedDuplicate(Object[] targetElements, int group, int target, Object element) {
        for (int i = target - 1; i >= group && tie(targetElements[i], element) == 0; i--) {
            if (this.comparator != null || targetElements[i].equals(element)) {
                return true;
            }
        }
        return false;
    }


    // A stable merge sort of [from, to) of the arrays by the order of the elements, then of the numbers if there are
    @SuppressWarnings("java:S3776")
    private void sort(long[] sortMoments, Object[] sortElements, @Nullable int[] sortNumbers, int from, int to) {
        int count = to - from;
        for (int low = from; low < to; low += INSERTION_SORT) {
            insertionSort(sortMoments, sortElements, sortNumbers, low, Math.min(low + INSERTION_SORT, to));
        }
        if (count <= INSERTION_SORT) {
            return;
        }
        Run source = new Run(sortMoments, sortElements, sortNumbers, from);
        Run target = new Run(new long[count], new Object[count], sortNumbers == null ? null : new int[count], 0);
        for (int width = INSERTION_SORT; width < count; width <<= 1) {
            for (int low = 0; low < count; low += width << 1) {
                int middle = Math.min(low + width, count);
                int high = Math.min(low + (width << 1), count);
                mergeRuns(source, target, low, middle, high);
            }
            Run swap = source;
            source = target;
            target = swap;
        }
        if (source.moments != sortMoments) {
            System.arraycopy(source.moments, 0, sortMoments, from, count);
            System.arraycopy(source.elements, 0, sortElements, from, count);
            if (sortNumbers != null) {
                System.arraycopy(source.numbers, 0, sortNumbers, from, count);
            }
        }
    }

    private void insertionSort(long[] sortMoments, Object[] sortElements, @Nullable int[] sortNumbers, int from, int to) {
        for (int i = from + 1; i < to; i++) {
            long moment = sortMoments[i];
            Object element = sortElements[i];
            int number = sortNumbers == null ? 0 : sortNumbers[i];
            int j = i - 1;
            while (j >= from && compare(sortMoments[j], sortElements[j], sortNumbers == null ? 0 : sortNumbers[j], moment, element, number) > 0) {
                sortMoments[j + 1] = sortMoments[j];
                sortElements[j + 1] = sortElements[j];
                if (sortNumbers != null) {
                    sortNumbers[j + 1] = sortNumbers[j];
                }
                j--;
            }
            sortMoments[j + 1] = moment;
            sortElements[j + 1] = element;
            if (sortNumbers != null) {
                sortNumbers[j + 1] = number;
            }
        }
    }

    // Merges the runs [low, middle) and [middle, high) of the source into the target: the first run goes first among the
    // elements of its order
    private void mergeRuns(Run source, Run target, int low, int middle, int high) {
        int first = low;
        int second = middle;
        for (int i = low; i < high; i++) {
            int from;
            if (second == high || first < middle && source.compare(this, first, second) <= 0) {
                from = first++;
            } else {
                from = second++;
            }
            target.moments[target.offset + i] = source.moments[source.offset + from];
            target.elements[target.offset + i] = source.elements[source.offset + from];
            if (target.numbers != null) {
                target.numbers[target.offset + i] = source.numbers[source.offset + from];
            }
        }
    }

    private int compare(long firstMoment, Object firstElement, int firstNumber, long secondMoment, Object secondElement, int secondNumber) {
        int order = compare(firstMoment, firstElement, secondMoment, secondElement);
        return order == 0 ? Integer.compare(firstNumber, secondNumber) : order;
    }

    private int compare(long firstMoment, Object firstElement, long secondMoment, Object secondElement) {
        return firstMoment == secondMoment ? tie(firstElement, secondElement) : Long.compare(firstMoment, secondMoment);
    }

    // The order of two elements of a moment
    @SuppressWarnings("unchecked")
    private int tie(Object first, Object second) {
        return this.comparator == null
                ? Integer.compare(first.hashCode(), second.hashCode())
                : this.comparator.compare((S) first, (S) second);
    }

    /**
     * The elements of a chunk that fire up to a moment, in their order: the chunk gives them away when the firing is
     * committed. Nothing may change the chunk between the preparation and the commit.
     */
    final class Firing {
        // the end of the sorted elements that fire, and the number of the elements of the heap that fire
        private final int end;
        private final int late;
        // the elements that fire: [from, to) of the array
        private final Object[] fired;
        private final int from;
        private final int to;
        // the firings of the parts of a split chunk, and the indexes of their parts
        private final Firing[] partFirings;
        private final int[] partIndexes;

        private Firing(int end, int late, Object[] fired, int from, int to) {
            this.end = end;
            this.late = late;
            this.fired = fired;
            this.from = from;
            this.to = to;
            this.partFirings = null;
            this.partIndexes = null;
        }

        private Firing(Firing[] partFirings, int[] partIndexes, int count) {
            this.end = 0;
            this.late = 0;
            this.fired = null;
            this.from = 0;
            this.to = count;
            this.partFirings = partFirings;
            this.partIndexes = partIndexes;
        }

        /**
         * Gives the elements that fire in their order, before the commit
         * @param consumer the consumer of the elements
         */
        @SuppressWarnings("unchecked")
        void forEach(@NonNull Consumer<? super S> consumer) {
            if (this.partFirings != null) {
                for (int i = 0; i < this.to; i++) {
                    this.partFirings[i].forEach(consumer);
                }
                return;
            }
            for (int i = this.from; i < this.to; i++) {
                consumer.accept((S) this.fired[i]);
            }
        }

        /**
         * Takes the elements that fire out of the chunk. No element is compared: the elements of the heap up to the
         * moment are its first ones by their moments and numbers.
         */
        void commit() {
            if (this.partFirings != null) {
                commitParts();
                return;
            }
            for (int i = head; i < this.end; i++) {
                elements[i] = null;
            }
            head = this.end;
            for (int i = 0; i < this.late; i++) {
                pollLate();
            }
            releaseFiredSorted();
        }

        // The firings of the parts take their elements out, and a part without elements goes
        private void commitParts() {
            for (int i = 0; i < this.to; i++) {
                this.partFirings[i].commit();
                int index = this.partIndexes[i];
                if (parts[index].isEmpty()) {
                    parts[index] = null;
                }
            }
            while (firstPart < PART_COUNT && parts[firstPart] == null) {
                firstPart++;
            }
        }

        // Takes the first element out of the heap, by its moment and number: no comparison of the elements
        private void pollLate() {
            int last = --lateSize;
            long moment = lateMoments[last];
            Object element = lateElements[last];
            int number = lateNumbers[last];
            lateElements[last] = null;
            if (last == 0) {
                return;
            }
            // sift down
            int index = 0;
            int half = last >>> 1;
            while (index < half) {
                int child = (index << 1) + 1;
                int right = child + 1;
                if (right < last && lateBefore(lateMoments[right], lateNumbers[right], lateMoments[child], lateNumbers[child])) {
                    child = right;
                }
                if (lateBefore(moment, number, lateMoments[child], lateNumbers[child])) {
                    break;
                }
                setLate(index, lateMoments[child], lateElements[child], lateNumbers[child]);
                index = child;
            }
            setLate(index, moment, element, number);
        }

        // The order of the heap: the moment, then the number of the addition
        private boolean lateBefore(long firstMoment, int firstNumber, long secondMoment, int secondNumber) {
            return firstMoment < secondMoment || firstMoment == secondMoment && firstNumber < secondNumber;
        }

        // The arrays of the sorted elements go when the sorted elements have all fired
        private void releaseFiredSorted() {
            if (head == size) {
                moments = NO_MOMENTS;
                elements = NO_ELEMENTS;
                head = 0;
                sorted = 0;
                size = 0;
            }
        }
    }

    /**
     * The arrays of a sort, from an offset
     */
    private static final class Run {
        private final long[] moments;
        private final Object[] elements;
        private final int[] numbers;
        private final int offset;

        private Run(long[] moments, Object[] elements, @Nullable int[] numbers, int offset) {
            this.moments = moments;
            this.elements = elements;
            this.numbers = numbers;
            this.offset = offset;
        }

        private int compare(Chunk<?> chunk, int first, int second) {
            int firstIndex = this.offset + first;
            int secondIndex = this.offset + second;
            return chunk.compare(this.moments[firstIndex], this.elements[firstIndex], this.numbers == null ? 0 : this.numbers[firstIndex],
                    this.moments[secondIndex], this.elements[secondIndex], this.numbers == null ? 0 : this.numbers[secondIndex]);
        }
    }

}
