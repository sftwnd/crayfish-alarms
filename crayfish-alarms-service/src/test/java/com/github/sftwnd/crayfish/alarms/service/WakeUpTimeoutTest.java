package com.github.sftwnd.crayfish.alarms.service;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The process wakes up for a moment at the next multiple of the step of a wake-up on the clock
 */
class WakeUpTimeoutTest {

    private static final long MILLISECOND = 1_000_000L;
    private static final long SECOND = 1_000_000_000L;

    @Test
    void millisecondTest() {
        // the moment 1.234567890 s on the clock: the process wakes up at 1.235 s, whenever the wait starts
        assertEquals(432_120L, AlarmService.wakeUpTimeout(10L, 1_234_567_890L, MILLISECOND), "A wait has to end at the next millisecond of the clock");
        assertEquals(732_110L, AlarmService.wakeUpTimeout(300_000L, 1_234_567_890L, MILLISECOND), "A wait has to end at the next millisecond of the clock");
        assertEquals(1_000_432_110L, AlarmService.wakeUpTimeout(1_000_000_000L, 1_234_567_890L, MILLISECOND), "A wait has to end at the next millisecond of the clock");
        assertEquals(300_000L, AlarmService.wakeUpTimeout(300_000L, 1_235_000_000L, MILLISECOND), "A wait for a millisecond of the clock has to stay");
    }

    @Test
    void noStepTest() {
        assertEquals(1L, AlarmService.wakeUpTimeout(1L, 1_234_567_890L, 0L), "Without a step a wait has to keep its nanoseconds");
        assertEquals(Long.MAX_VALUE, AlarmService.wakeUpTimeout(Long.MAX_VALUE, 1L, 0L), "Without a step the longest wait has to stay");
    }

    @Test
    void coarseStepTest() {
        long step = 50 * MILLISECOND;
        // the moment 140 ms + 1 ns on the clock, a nanosecond from now: the process wakes up at 150 ms
        assertEquals(10 * MILLISECOND, AlarmService.wakeUpTimeout(1L, 140 * MILLISECOND + 1L, step), "A wait has to end at the next step of the clock");
        assertEquals(40 * MILLISECOND, AlarmService.wakeUpTimeout(30 * MILLISECOND, 140 * MILLISECOND, step), "A wait has to end at the next step of the clock");
        assertEquals(step, AlarmService.wakeUpTimeout(step, 3 * step, step), "A wait for a step of the clock has to stay");
    }

    @Test
    void beforeTheEpochTest() {
        // a millisecond of the clock before the epoch: -1 ns is 1 ns before the epoch
        assertEquals(2L, AlarmService.wakeUpTimeout(1L, -1L, MILLISECOND), "A wait before the epoch has to end at the next millisecond of the clock");
    }

    @Test
    void longestWaitTest() {
        assertEquals(Long.MAX_VALUE, AlarmService.wakeUpTimeout(Long.MAX_VALUE - 1L, 1L, MILLISECOND), "A timeout past the longest wait has to be the longest wait");
        assertEquals(Long.MAX_VALUE, AlarmService.wakeUpTimeout(1L, 1L, Long.MAX_VALUE), "The longest step has to wait the longest wait");
    }

    @Test
    void epochNanosTest() {
        assertEquals(0L, AlarmService.epochNanos(Instant.EPOCH), "The epoch has to be zero");
        assertEquals(SECOND + 5L, AlarmService.epochNanos(Instant.ofEpochSecond(1L, 5L)), "A moment has to be the nanoseconds from the epoch");
        assertEquals(-SECOND + 5L, AlarmService.epochNanos(Instant.ofEpochSecond(-1L, 5L)), "A moment before the epoch has to be negative");
        assertEquals(0L, AlarmService.epochNanos(Instant.MAX), "A moment after 2262 has to be a moment of the grid");
        assertEquals(0L, AlarmService.epochNanos(Instant.MIN), "A moment before 1678 has to be a moment of the grid");
    }

}
