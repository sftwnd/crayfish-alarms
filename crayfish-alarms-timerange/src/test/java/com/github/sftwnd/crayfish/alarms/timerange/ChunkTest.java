package com.github.sftwnd.crayfish.alarms.timerange;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The duplicates take no place in a chunk for long: the ones of a cold chunk go when its arrays are full, the ones of the
 * sorted elements of a hot chunk are not added, and the ones of the heap of a hot chunk go when the heap is merged
 */
class ChunkTest {

    private static final int COPIES = 10_000;
    // the strings of the same hash code: equal ones are duplicates without a comparator
    private static final List<String> SAME_HASH = Arrays.asList("AaAa", "AaBB", "BBAa", "BBBB");

    @Test
    void coldDuplicatesTest() {
        for (Comparator<String> comparator : comparators()) {
            Chunk<String> chunk = new Chunk<>(comparator);
            for (int i = 0; i < COPIES; i++) {
                chunk.add(100L, SAME_HASH.get(i % SAME_HASH.size()));
            }
            assertTrue(chunk.size() <= 2 * SAME_HASH.size(), () -> "A cold chunk has to drop the duplicates when its arrays are full: " + chunk.size());
            assertEquals(SAME_HASH, fire(chunk, Long.MAX_VALUE), "A cold chunk has to fire one element of the duplicates in the order of addition");
        }
    }

    @Test
    void sortedDuplicatesTest() {
        for (Comparator<String> comparator : comparators()) {
            Chunk<String> chunk = new Chunk<>(comparator);
            chunk.add(0L, "first");
            SAME_HASH.forEach(element -> chunk.add(100L, element));
            assertEquals(Collections.singletonList("first"), fire(chunk, 0L), "A chunk has to fire its first element");
            for (int i = 0; i < COPIES; i++) {
                chunk.add(100L, SAME_HASH.get(i % SAME_HASH.size()));
            }
            assertEquals(SAME_HASH.size(), chunk.size(), "A hot chunk has to add no duplicate of its sorted elements");
            assertEquals(SAME_HASH, fire(chunk, Long.MAX_VALUE), "A hot chunk has to fire its sorted elements");
        }
    }

    @Test
    void lateDuplicatesTest() {
        for (Comparator<String> comparator : comparators()) {
            Chunk<String> chunk = new Chunk<>(comparator);
            chunk.add(0L, "first");
            assertEquals(Collections.singletonList("first"), fire(chunk, 0L), "A chunk has to fire its first element");
            for (int i = 0; i < COPIES; i++) {
                chunk.add(100L, SAME_HASH.get(i % SAME_HASH.size()));
            }
            assertEquals(SAME_HASH.size(), chunk.size(), "A hot chunk has to drop the duplicates of its heap when it merges the heap, and then add none");
            assertEquals(SAME_HASH, fire(chunk, Long.MAX_VALUE), "A hot chunk has to fire one element of the duplicates in the order of addition");
            assertTrue(chunk.isEmpty(), "A chunk that has fired every element has to be empty");
        }
    }

    @Test
    void firstMomentTest() {
        Chunk<String> chunk = new Chunk<>(null);
        assertEquals(Long.MAX_VALUE, chunk.firstMoment(), "An empty chunk has no first moment");
        chunk.add(300L, "c");
        chunk.add(100L, "a");
        chunk.add(200L, "b");
        assertEquals(100L, chunk.firstMoment(), "A cold chunk has to give its earliest moment");
        assertEquals(Collections.emptyList(), fire(chunk, 99L), "A chunk has to fire nothing before its first moment");
        assertEquals(Collections.singletonList("a"), fire(chunk, 100L), "A chunk has to fire the element of its first moment");
        chunk.add(150L, "late");
        assertEquals(150L, chunk.firstMoment(), "A hot chunk has to give the earliest moment of its heap");
        assertEquals(Arrays.asList("late", "b"), fire(chunk, 200L), "A hot chunk has to fire its heap and its sorted elements in order");
        assertEquals(300L, chunk.firstMoment(), "A hot chunk has to give the earliest moment of its sorted elements");
    }

    // without a comparator, the elements are ordered by their hash codes and equal ones are duplicates; the comparator
    // of the strings themselves finds the same duplicates and gives the order of addition here
    private static List<Comparator<String>> comparators() {
        Comparator<String> byAddition = Comparator.comparingInt(SAME_HASH::indexOf);
        return Arrays.asList(null, byAddition);
    }

    private static List<String> fire(Chunk<String> chunk, long limit) {
        List<String> fired = new ArrayList<>();
        chunk.fire(limit, fired::add);
        return fired;
    }
}
