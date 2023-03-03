package com.github.sftwnd.crayfish.alarms.service.test;

import com.github.sftwnd.crayfish.alarms.service.AlarmTimeRangeService;
import com.github.sftwnd.crayfish.alarms.service.IAlarmService;
import com.github.sftwnd.crayfish.alarms.timerange.ITimeRange;
import com.github.sftwnd.crayfish.alarms.timerange.ITimeRangeFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
        IAlarmService<Instant, ?> alarmService = alarmService(timeRange(), null);
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
    @SuppressWarnings("ConstantConditions")
    void addNullElementS() {
        IAlarmService<?, ?> alarmService = alarmService(timeRange(), Duration.ZERO);
        assertThrows(NullPointerException.class, () -> alarmService.addElement(null), "addElement with null value has to throws NPE");
        assertThrows(NullPointerException.class, () -> alarmService.addElements(null), "addElements with null value has to throws NPE");
    }


    @Test
    void addEmptyElements() {
        IAlarmService<Instant, Instant> alarmService = alarmService(timeRange(), Duration.ZERO);
        CompletionStage<Collection<Instant>> completionStage = alarmService.addElements(Collections.emptyList());
        AtomicReference<Collection<Instant>> completed = new AtomicReference<>();
        assertDoesNotThrow(() -> completed.set(completionStage.toCompletableFuture().get(100, TimeUnit.MILLISECONDS)), "addElements on empty list has to return completed future");
        assertNotNull(completed.get(), "addElements on empty list has to return non null value in future");
        assertTrue(completed.get().isEmpty(), "addElements on empty list has to return empty collection in future");
    }

    @Test
    void addBeforeRangeElement() {
        ITimeRange<Instant, Instant> timeRange = timeRange();
        IAlarmService<Instant, Instant> alarmService = alarmService(timeRange, Duration.ZERO);
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
        IAlarmService<Instant, Instant> alarmService = alarmService(timeRange, Duration.ZERO);
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
        IAlarmService<Instant, Instant> alarmService = alarmService(timeRange, Duration.ZERO);
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
    void fireElement() throws InterruptedException {
        ITimeRange<Instant, Instant> timeRange = timeRange();
        IAlarmService<Instant, Instant> alarmService = alarmService(timeRange, Duration.ZERO);
        CountDownLatch cdl = new CountDownLatch(1);
        Thread thread = new Thread(() -> {
            try {
                alarmService.process(ignore -> {
                    cdl.countDown();
                    Thread.currentThread().interrupt();
                });
            } catch (Throwable ignore) {
            }
        });
        thread.start();
        alarmService.addElement(Instant.now());
        assertTrue(cdl.await(1, TimeUnit.SECONDS), "Instant in TimeRange period has to be fired");
    }

    @Test
    void concurrentAddElement() {
        ITimeRange<Instant, Instant> timeRange = timeRange();
        IAlarmService<Instant, Instant> alarmService = alarmService(timeRange, Duration.ZERO);
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
                boolean ignore = addCdl.await(1, TimeUnit.SECONDS);
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

    @Test
    void rejectTest() throws InterruptedException, ExecutionException {
        ITimeRange<Instant, Instant> timeRange = timeRangeFactory
                .timeRange(Instant.now().truncatedTo(ChronoUnit.SECONDS).minusSeconds(1));
        IAlarmService<Instant, Instant> alarmService = alarmService(timeRange, Duration.ZERO);
        Instant firstInstant = timeRange.getStartInstant();
        Instant secondInstant = timeRange.getStartInstant().plusMillis(1);
        AtomicReference<CompletionStage<Instant>> futureRef = new AtomicReference<>();
        CountDownLatch sCdl = new CountDownLatch(1);
        CountDownLatch eCdl = new CountDownLatch(1);
        Thread thread = new Thread( () -> {
            try { boolean ignore = sCdl.await(1, TimeUnit.SECONDS); } catch (InterruptedException ignore) { }
            try {
                alarmService.process(elements -> elements.stream()
                        .filter(firstInstant::equals)
                        .findFirst()
                        .ifPresent(element -> {
                            synchronized (futureRef) {
                                futureRef.set(alarmService.addElement(secondInstant));
                                futureRef.notify();
                                throw new IllegalStateException();
                            }
                        }));
            } catch (IllegalStateException ignore) {
            } finally {
                eCdl.countDown();
            }
        });
        thread.start();
        alarmService.addElement(firstInstant);
        sCdl.countDown();
        boolean ignore = eCdl.await(1, TimeUnit.SECONDS);
        assertNotNull(futureRef.get(), "CompletionStage after the first element fires has to be filled");
        CompletableFuture<Instant> future = futureRef.get().toCompletableFuture();
        assertDoesNotThrow(() -> future.get(1, TimeUnit.SECONDS), "CompletionStage has to be done");
        assertEquals(secondInstant, future.get(), "Not registered before crush elements has to be rejected");

    }

    @Test
    void timeOffsetTest() throws InterruptedException, ExecutionException {
        ITimeRange<Instant, Instant> timeRange = timeRangeFactory
                .timeRange(Instant.now().truncatedTo(ChronoUnit.SECONDS).minusSeconds(1));
        IAlarmService<Instant, Instant> alarmService = alarmService(timeRange, Duration.ZERO);
        CompletableFuture<Instant> firedFuture = new CompletableFuture<>();
        CountDownLatch serviceCdl = new CountDownLatch(1);
        CountDownLatch processCdl = new CountDownLatch(1);
        Thread serviceThread = new Thread(() -> {
            try {
                boolean ignore = serviceCdl.await(1, TimeUnit.SECONDS);
                processCdl.countDown();
                alarmService.process(elements -> elements.stream().findFirst().ifPresent(instant -> {
                    firedFuture.complete(Instant.now());
                    throw new RuntimeException();
                }), Duration.ofSeconds(3));
            } catch (Throwable ignore) {
            }
        });
        serviceThread.start();
        serviceCdl.countDown();
        assertTrue(processCdl.await(1, TimeUnit.SECONDS), "AlarmService process thread has to be started");
        Instant instant = Instant.now().truncatedTo(ChronoUnit.SECONDS).plusSeconds(3);
        alarmService.addElement(instant);
        assertDoesNotThrow(() -> firedFuture.get(1, TimeUnit.SECONDS), "AlarmService has to fire alarm");
        assertTrue(Duration.between(instant, firedFuture.get()).toMillis() < -500L, "AlarmService has to fire alarm with timeOffset");
    }

    private IAlarmService<Instant, Instant> alarmService(ITimeRange<Instant, Instant> timeRange, Duration minimalWait) {
        return new AlarmTimeRangeService<>(timeRange, minimalWait);
    }

    private ITimeRange<Instant, Instant> timeRange() {
        return timeRangeFactory.timeRange(Instant.now());
    }
    private final ITimeRangeFactory<Instant, Instant> timeRangeFactory = ITimeRangeFactory.temporal(
            Duration.ofSeconds(5), Duration.ofMillis(100), Duration.ofMillis(250), null
    );

    @BeforeAll
    public static void startUp() {
        Logger.getLogger("com.github.sftwnd.crayfish.alarms.service.AlarmService")
                .setLevel(Level.OFF);
    }

}