package com.github.sftwnd.crayfish.alarms.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The timeout of a wait is rounded up to a multiple of the step of a wake-up
 */
class WakeUpTimeoutTest {

    private static final long MILLISECOND = 1_000_000L;

    @Test
    void millisecondTest() {
        // the default step rounds as Object.wait(ms, nanos): a millisecond more when the nanoseconds are not zero
        assertEquals(MILLISECOND, AlarmService.wakeUpTimeout(1L, MILLISECOND), "A nanosecond has to wait a millisecond");
        assertEquals(MILLISECOND, AlarmService.wakeUpTimeout(MILLISECOND, MILLISECOND), "A millisecond has to stay");
        assertEquals(2 * MILLISECOND, AlarmService.wakeUpTimeout(MILLISECOND + 1L, MILLISECOND), "A millisecond and a nanosecond have to wait two milliseconds");
        assertEquals(1_235 * MILLISECOND, AlarmService.wakeUpTimeout(1_234_567_890L, MILLISECOND), "The nanoseconds have to round the milliseconds up");
    }

    @Test
    void noStepTest() {
        assertEquals(1L, AlarmService.wakeUpTimeout(1L, 0L), "Without a step a wait has to keep its nanoseconds");
        assertEquals(1_234_567_890L, AlarmService.wakeUpTimeout(1_234_567_890L, 0L), "Without a step a wait has to keep its nanoseconds");
        assertEquals(Long.MAX_VALUE, AlarmService.wakeUpTimeout(Long.MAX_VALUE, 0L), "Without a step the longest wait has to stay");
    }

    @Test
    void coarseStepTest() {
        long step = 50 * MILLISECOND;
        assertEquals(step, AlarmService.wakeUpTimeout(1L, step), "A short wait has to wait a step");
        assertEquals(step, AlarmService.wakeUpTimeout(step, step), "A step has to stay");
        assertEquals(3 * step, AlarmService.wakeUpTimeout(2 * step + 1L, step), "A wait has to be rounded up to the steps");
    }

    @Test
    void longestWaitTest() {
        long step = Long.MAX_VALUE / 2 + 1L;
        assertEquals(Long.MAX_VALUE, AlarmService.wakeUpTimeout(step + 1L, step), "A rounding past the longest wait has to be the longest wait");
        assertEquals(Long.MAX_VALUE, AlarmService.wakeUpTimeout(Long.MAX_VALUE, MILLISECOND), "A rounding past the longest wait has to be the longest wait");
        assertEquals(Long.MAX_VALUE, AlarmService.wakeUpTimeout(1L, Long.MAX_VALUE), "The longest step has to wait the longest wait");
    }

}
