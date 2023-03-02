package com.github.sftwnd.crayfish.alarms.test;

import com.github.sftwnd.crayfish.alarms.service.AlarmTimeRangeService;
import com.github.sftwnd.crayfish.alarms.service.IAlarmService;
import com.github.sftwnd.crayfish.alarms.timerange.ITimeRange;
import com.github.sftwnd.crayfish.alarms.timerange.ITimeRangeFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlarmTimeRangeServiceTest {

    @Test
    void startTwice() {
        IAlarmService<Instant, ?> alarmService = alarmService(timeRange());
        CountDownLatch startCdl = new CountDownLatch(1);
        Thread processThread = new Thread(() -> {
            startCdl.countDown();
            alarmService.singleProcess(ignore -> {});
        });
        CompletableFuture<Void> completableFuture = new CompletableFuture<>();
        CountDownLatch startCdl1 = new CountDownLatch(1);
        Thread processThread1 = new Thread(() -> {
            try {
                startCdl1.countDown();
                alarmService.process(ignore -> {});
                completableFuture.complete(null);
            } catch (Throwable throwable) {
                completableFuture.completeExceptionally(throwable);
            }
        });
        //
        try {
            processThread.start();
            assertDoesNotThrow(() -> startCdl.await(1, TimeUnit.SECONDS), "process thread has to be started");
            assertDoesNotThrow(() -> alarmService.addElement(Instant.now()).toCompletableFuture().get(1, TimeUnit.SECONDS), "process thread has to be active");
            processThread1.start();
            assertDoesNotThrow(() -> startCdl1.await(1, TimeUnit.SECONDS), "process thread 2 has to be started");
            try {
                assertThrows(ExecutionException.class, () -> completableFuture.get(1, TimeUnit.SECONDS), "process thread 2 has to throws exception in the case of active process presented");
            } catch (CompletionException exception) {
                assertEquals(IllegalStateException.class, exception.getCause().getClass(), "process thread 2 has to throws IllegalStateException in the case of active process presented");
            }
        } finally {
            processThread.interrupt();
            processThread1.interrupt();
        }
    }

    @Test
    void addNullElementS() {
        IAlarmService<?, ?> alarmService = alarmService(timeRange());
        assertThrows(NullPointerException.class, () -> alarmService.addElement(null), "addElement with null value has to throws NPE");
        assertThrows(NullPointerException.class, () -> alarmService.addElements(null), "addElements with null value has to throws NPE");
    }


    @Test
    void addEmptyElements() {
        IAlarmService<Instant, Instant> alarmService = alarmService(timeRange());
        CompletionStage<Collection<Instant>> completionStage = alarmService.addElements(Collections.emptyList());
        AtomicReference<Collection<Instant>> completed = new AtomicReference<>();
        assertDoesNotThrow(() -> completed.set(completionStage.toCompletableFuture().get(100, TimeUnit.MILLISECONDS)), "addElements on empty list has to return completed future");
        assertNotNull(completed.get(), "addElements on empty list has to return non null value in future");
        assertTrue(completed.get().isEmpty(), "addElements on empty list has to return empty collection in future");
    }

    @Test
    void addBeforeRangeElement() {
        ITimeRange<Instant, Instant> timeRange = timeRange();
        IAlarmService<Instant, Instant> alarmService = alarmService(timeRange);
        Instant instant = timeRange.getStartInstant().minusNanos(1);
        CompletionStage<Instant> completionStage = alarmService.addElement(instant);
        Thread thread = new Thread(() -> alarmService.process(ignore -> {}));
        thread.start();
        Instant result = completionStage.toCompletableFuture().join();
        thread.interrupt();
        assertEquals(instant, result, "Instant before TimeRange period has to be rejected");
    }

    @Test
    void addAfterRangeElement() throws ExecutionException, InterruptedException, TimeoutException {
        ITimeRange<Instant, Instant> timeRange = timeRange();
        IAlarmService<Instant, Instant> alarmService = alarmService(timeRange);
        Instant instant = timeRange.getLastInstant();
        CompletionStage<Instant> completionStage = alarmService.addElement(instant);
        Thread thread = new Thread(() -> alarmService.process(ignore -> {}));
        thread.start();
        Instant result = completionStage.toCompletableFuture().get(1, TimeUnit.SECONDS);
        thread.interrupt();
        assertEquals(instant, result, "Instant before TimeRange period has to be rejected");
    }

    @Test
    void addInRangeElement() throws InterruptedException, ExecutionException, TimeoutException {
        ITimeRange<Instant, Instant> timeRange = timeRange();
        IAlarmService<Instant, Instant> alarmService = alarmService(timeRange);
        Instant instant = timeRange.getStartInstant();
        CompletionStage<Instant> completionStage = alarmService.addElement(instant);
        Thread thread = new Thread(() -> alarmService.singleProcess(ignore -> {}));
        thread.start();
        try {
            Instant result = completionStage.toCompletableFuture().get(1, TimeUnit.SECONDS);
            assertNull(result, "Instant in TimeRange period has to be approved");
        } finally {
            thread.interrupt();
        }
    }

    @Test
    void fireElement() throws InterruptedException, ExecutionException, TimeoutException {
        ITimeRange<Instant, Instant> timeRange = timeRange();
        IAlarmService<Instant, Instant> alarmService = alarmService(timeRange);
        Instant instant = timeRange.getStartInstant();
        CompletionStage<Instant> completionStage = alarmService.addElement(instant);
        CountDownLatch cdl = new CountDownLatch(1);
        Thread thread = new Thread(() -> alarmService.process(ignore -> Thread.currentThread().interrupt()));
        thread.start();
        try {
            assertDoesNotThrow(() -> cdl.await(1, TimeUnit.SECONDS), "Instant in TimeRange period has to be fired");
        } finally {
            thread.interrupt();
        }
    }

    @Test
    void concurrentAddElement() throws InterruptedException, ExecutionException, TimeoutException {
        ITimeRange<Instant, Instant> timeRange = timeRange();
        IAlarmService<Instant, Instant> alarmService = alarmService(timeRange);
        Instant instant = timeRange.getStartInstant();
        int degree = Math.min(4, Runtime.getRuntime().availableProcessors());
        CountDownLatch startedCdl = new CountDownLatch(degree);
        CountDownLatch addCdl = new CountDownLatch(1);
        CountDownLatch firedCdl = new CountDownLatch(degree);
        Thread alarmServiceThread = new Thread(() -> alarmService.singleProcess(
                ignore -> firedCdl.countDown()
        ));
        alarmServiceThread.start();
        Collection<Thread> threads = IntStream.range(0, degree).mapToObj(i -> new Thread(() -> {
            try {
                startedCdl.countDown();
                addCdl.await(1, TimeUnit.SECONDS);
                alarmService.addElement(instant.plusNanos(i));
            } catch (InterruptedException ignore) {
            }
        })).collect(Collectors.toList());
        threads.forEach(Thread::start);
        try {
            assertDoesNotThrow(() -> startedCdl.await(1, TimeUnit.SECONDS), "All add-tasks has to be started");
            addCdl.countDown();
            assertDoesNotThrow(() -> firedCdl.await(1, TimeUnit.SECONDS), "All alarms has to be fired");
        } finally {
            threads.forEach(Thread::interrupt);
            alarmServiceThread.interrupt();
        }
    }

    private IAlarmService<Instant, Instant> alarmService(ITimeRange<Instant, Instant> timeRange) {
        return alarmService(timeRange, null);
    }

    private IAlarmService<Instant, Instant> alarmService(ITimeRange<Instant, Instant> timeRange, Duration minimalWait) {
        return new AlarmTimeRangeService<>(timeRange, null);
    }

    private ITimeRange<Instant, Instant> timeRange() {
        return timeRangeFactory.timeRange(Instant.now());
    }
    private final ITimeRangeFactory<Instant, Instant> timeRangeFactory = ITimeRangeFactory.temporal(
            Duration.ofSeconds(2), Duration.ofMillis(100), Duration.ofMillis(250), null
    );

    @BeforeAll
    public static void startUp() {
        Logger.getLogger("com.github.sftwnd.crayfish.alarms.service.AlarmTimeRangeService")
                .setLevel(Level.OFF);
    }

}