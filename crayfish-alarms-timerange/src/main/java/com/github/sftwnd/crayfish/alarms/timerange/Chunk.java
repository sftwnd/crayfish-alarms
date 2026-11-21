/*
 * Copyright © 2017-2026 Andrey D. Shindarev. All rights reserved.
 * This program is made available under the terms of the BSD 3-Clause License.
 * Contacts: ashindarev@gmail.com
 */
package com.github.sftwnd.crayfish.alarms.timerange;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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
 * @param <S> Element type of the internal storage
 */
final class Chunk<S> {

    private static final int INITIAL_CAPACITY = 4;
    // the elements of a run shorter than this are sorted by insertions
    private static final int INSERTION_SORT = 32;
    // the heap of the new elements of a hot chunk is merged into its sorted elements at a quarter of them, or at this size
    private static final int LATE_MINIMUM = 64;
    private static final long[] NO_MOMENTS = new long[0];
    private static final Object[] NO_ELEMENTS = new Object[0];

    /**
     * The order of the elements of a moment; without a comparator, the order of their hash codes
     */
    private final Comparator<? super S> comparator;

    // The sorted elements [head, sorted) without duplicates, then the elements appended to a cold chunk [sorted, size)
    private long[] moments = new long[INITIAL_CAPACITY];
    private Object[] elements = new Object[INITIAL_CAPACITY];
    private int head;
    private int sorted;
    private int size;
    // The earliest moment of a cold chunk
    private long minimum = Long.MAX_VALUE;
    private boolean hot;

    // The binary heap of the elements added to a hot chunk, with the numbers of their addition, which order the elements
    // of a moment that are equal by the comparator or by the hash code
    private long[] lateMoments;
    private Object[] lateElements;
    private int[] lateNumbers;
    private int lateSize;
    private int lateNumber;

    /**
     * A chunk without elements
     * @param comparator The order of the elements of a moment, which also finds their duplicates; null: the elements of
     *                   a moment are ordered by their hash codes, and equal ones are duplicates
     */
    Chunk(@Nullable Comparator<? super S> comparator) {
        this.comparator = comparator;
    }

    /**
     * Adds an element, unless it is a duplicate
     * @param moment the nanoseconds from the start of the chunk
     * @param element the element
     */
    void add(long moment, @NonNull S element) {
        if (this.hot) {
            addLate(moment, element);
            return;
        }
        if (this.size == this.moments.length) {
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
        return this.head == this.size && this.lateSize == 0;
    }

    /**
     * @return the number of the elements kept: the appended elements of a cold chunk and the heap of a hot one can have
     *         duplicates that are not dropped yet
     */
    int size() {
        return this.size - this.head + this.lateSize;
    }

    /**
     * @return the earliest moment of the elements, {@link Long#MAX_VALUE} without elements
     */
    long firstMoment() {
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
    @SuppressWarnings("unchecked")
    void fire(long limit, @NonNull Consumer<? super S> consumer) {
        if (!heat(limit)) {
            return;
        }
        Fired fired = new Fired();
        boolean due = true;
        while (due && !isEmpty()) {
            boolean sortedNext = isSortedNext();
            long moment = sortedNext ? this.moments[this.head] : this.lateMoments[0];
            due = moment <= limit;
            if (due) {
                Object element = sortedNext ? pollSorted() : pollLate();
                if (fired.isNew(moment, element)) {
                    consumer.accept((S) element);
                }
            }
        }
        releaseFiredSorted();
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

    // The arrays of the sorted elements go when the sorted elements have all fired
    private void releaseFiredSorted() {
        if (this.head == this.size) {
            this.moments = NO_MOMENTS;
            this.elements = NO_ELEMENTS;
            this.head = 0;
            this.sorted = 0;
            this.size = 0;
        }
    }

    // Sorts the appended elements of a cold chunk, merges them with the sorted ones and drops the duplicates
    private void compact() {
        if (this.sorted < this.size) {
            sort(this.moments, this.elements, null, this.sorted, this.size);
            // the sorted elements were added before the appended ones, and go first among the elements of their order
            long[] prefixMoments = new long[this.sorted];
            Object[] prefixElements = new Object[this.sorted];
            System.arraycopy(this.moments, 0, prefixMoments, 0, this.sorted);
            System.arraycopy(this.elements, 0, prefixElements, 0, this.sorted);
            // in place: the merged elements never pass the next appended one to read
            int count = mergeDistinct(prefixMoments, prefixElements, 0, this.sorted,
                    this.moments, this.elements, this.sorted, this.size, this.moments, this.elements);
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
        // sift up: the new element has the greatest number, and goes after the elements of its order
        int index = this.lateSize++;
        int number = this.lateNumber++;
        while (index > 0) {
            int parent = (index - 1) >>> 1;
            if (compare(moment, element, this.lateMoments[parent], this.lateElements[parent]) >= 0) {
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

    // The next element to fire is a sorted one: on a tie the sorted elements go first, they were added before the heap
    private boolean isSortedNext() {
        return this.lateSize == 0 || this.head < this.size
                && compare(this.moments[this.head], this.elements[this.head], this.lateMoments[0], this.lateElements[0]) <= 0;
    }

    private Object pollSorted() {
        Object first = this.elements[this.head];
        this.elements[this.head++] = null;
        return first;
    }

    private Object pollLate() {
        Object first = this.lateElements[0];
        int last = --this.lateSize;
        long moment = this.lateMoments[last];
        Object element = this.lateElements[last];
        int number = this.lateNumbers[last];
        this.lateElements[last] = null;
        if (last == 0) {
            return first;
        }
        // sift down
        int index = 0;
        int half = last >>> 1;
        while (index < half) {
            int child = (index << 1) + 1;
            int right = child + 1;
            if (right < last && lateBefore(right, child)) {
                child = right;
            }
            int order = compare(moment, element, this.lateMoments[child], this.lateElements[child]);
            if (order < 0 || order == 0 && number < this.lateNumbers[child]) {
                break;
            }
            setLate(index, this.lateMoments[child], this.lateElements[child], this.lateNumbers[child]);
            index = child;
        }
        setLate(index, moment, element, number);
        return first;
    }

    private boolean lateBefore(int first, int second) {
        int order = compare(this.lateMoments[first], this.lateElements[first], this.lateMoments[second], this.lateElements[second]);
        return order < 0 || order == 0 && this.lateNumbers[first] < this.lateNumbers[second];
    }

    private void setLate(int index, long moment, Object element, int number) {
        this.lateMoments[index] = moment;
        this.lateElements[index] = element;
        this.lateNumbers[index] = number;
    }

    // The heap goes into the sorted elements: they were added before it, and go first among the elements of their order
    private void mergeLate() {
        sort(this.lateMoments, this.lateElements, this.lateNumbers, 0, this.lateSize);
        int capacity = Math.max(this.size - this.head + this.lateSize, INITIAL_CAPACITY);
        long[] mergedMoments = new long[capacity];
        Object[] mergedElements = new Object[capacity];
        int count = mergeDistinct(this.moments, this.elements, this.head, this.size,
                this.lateMoments, this.lateElements, 0, this.lateSize, mergedMoments, mergedElements);
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
    // elements of its order. The target can be the arrays of the second run when the merged elements never pass the next
    // element of the second run to read.
    @SuppressWarnings("java:S107")
    private int mergeDistinct(long[] firstMoments, Object[] firstElements, int first, int firstEnd,
                              long[] secondMoments, Object[] secondElements, int second, int secondEnd,
                              long[] targetMoments, Object[] targetElements) {
        int target = 0;
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
            if (!isMergedDuplicate(targetMoments, targetElements, target, moment, element)) {
                targetMoments[target] = moment;
                targetElements[target++] = element;
            }
        }
        return target;
    }

    // An element is a duplicate of a merged element of its order: they are the last ones merged
    private boolean isMergedDuplicate(long[] targetMoments, Object[] targetElements, int target, long moment, Object element) {
        for (int i = target - 1; i >= 0 && targetMoments[i] == moment && tie(targetElements[i], element) == 0; i--) {
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
     * The fired elements of the moment and of the order of the last one: an element of the heap can be a duplicate of
     * an earlier element of the heap, and they fire one after the other
     */
    private final class Fired {
        private final List<Object> group = new ArrayList<>();
        private long moment;

        // false for a duplicate of a fired element of its moment and order
        private boolean isNew(long elementMoment, Object element) {
            if (this.group.isEmpty() || this.moment != elementMoment || tie(this.group.get(0), element) != 0) {
                this.group.clear();
                this.moment = elementMoment;
            } else if (comparator != null || this.group.contains(element)) {
                return false;
            }
            this.group.add(element);
            return true;
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
