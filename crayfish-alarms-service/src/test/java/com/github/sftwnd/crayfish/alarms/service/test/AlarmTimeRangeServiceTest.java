package com.github.sftwnd.crayfish.alarms.service.test;

import com.github.sftwnd.crayfish.alarms.service.AlarmTimeRangeService;
import com.github.sftwnd.crayfish.alarms.service.IAlarmService;
import com.github.sftwnd.crayfish.alarms.timerange.ITimeRange;
import com.github.sftwnd.crayfish.alarms.timerange.ITimeRangeFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class AlarmTimeRangeServiceTest {

    @Test
    void startTwice() throws InterruptedException {
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
            assertTrue(startCdl.await(1, TimeUnit.SECONDS), "process thread has to be started");
            assertDoesNotThrow(() -> alarmService.addElement(Instant.now()).toCompletableFuture().get(1, TimeUnit.SECONDS), "process thread has to be active");
            processThread1.start();
            assertTrue(startCdl1.await(1, TimeUnit.SECONDS), "process thread 2 has to be started");
            ExecutionException exception = assertThrows(ExecutionException.class, () -> completableFuture.get(1, TimeUnit.SECONDS),
                    "process thread 2 has to throws exception in the case of active process presented");
            assertEquals(IllegalStateException.class, exception.getCause().getClass(),
                    "process thread 2 has to throws IllegalStateException in the case of active process presented");
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
        Thread thread = new Thread(() -> alarmService.process(ignore -> {
            cdl.countDown();
            Thread.currentThread().interrupt();
        }));
        thread.start();
        alarmService.addElement(Instant.now());
        assertTrue(cdl.await(1, TimeUnit.SECONDS), "Instant in TimeRange period has to be fired");
    }

    @Test
    void concurrentAddElement() throws InterruptedException {
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
                // all the alarms are added at once; an alarm that is not added fails the test, which waits for all
                if (addCdl.await(1, TimeUnit.SECONDS)) {
                    alarmService.addElement(instant.plusNanos(i));
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        })).collect(Collectors.toList());
        threads.forEach(Thread::start);
        try {
            assertTrue(startedCdl.await(1, TimeUnit.SECONDS), "All add-tasks has to be started");
            addCdl.countDown();
            assertTrue(firedCdl.await(1, TimeUnit.SECONDS), "All alarms has to be fired");
        } finally {
            threads.forEach(Thread::interrupt);
            alarmServiceThread.interrupt();
        }
    }

    @Test
    void wakeUpOnEveryRegistration() {
        // without a registration the process waits for the end of the range, an hour
        ITimeRange<Instant, Instant> timeRange = ITimeRangeFactory.<Instant>temporal(
                Duration.ofHours(1), Duration.ofMinutes(1), Duration.ZERO, null).timeRange(Instant.now());
        IAlarmService<Instant, Instant> alarmService = alarmService(timeRange, Duration.ZERO);
        Thread thread = new Thread(() -> alarmService.process(ignore -> {}));
        thread.start();
        // an element before the range is rejected when it is registered: the process waits again right after every
        // request, while the next request comes
        Instant instant = timeRange.getStartInstant().minusSeconds(1);
        try {
            for (int request = 1; request <= 10_000; request++) {
                CompletableFuture<Instant> completableFuture = alarmService.addElement(instant).toCompletableFuture();
                int number = request;
                assertDoesNotThrow(() -> completableFuture.get(2, TimeUnit.SECONDS),
                        () -> "AlarmService has to register the request " + number + " at once");
            }
        } finally {
            thread.interrupt();
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
            try {
                // the process starts after the first element is added; without the signal the test fails on its end
                if (!sCdl.await(1, TimeUnit.SECONDS)) {
                    return;
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
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
            } catch (IllegalStateException expected) {
                // the consumer of the first element ends the process with it
            } finally {
                eCdl.countDown();
            }
        });
        thread.start();
        alarmService.addElement(firstInstant);
        sCdl.countDown();
        assertTrue(eCdl.await(1, TimeUnit.SECONDS), "The process has to end after the first element fires");
        assertNotNull(futureRef.get(), "CompletionStage after the first element fires has to be filled");
        CompletableFuture<Instant> future = futureRef.get().toCompletableFuture();
        assertDoesNotThrow(() -> future.get(1, TimeUnit.SECONDS), "CompletionStage has to be done");
        assertEquals(secondInstant, future.get(), "Not registered before crush elements has to be rejected");

    }

    @Test
    void addAfterTheEnd() throws InterruptedException {
        // the process ends with its range, 200 ms from now
        ITimeRange<Instant, Instant> timeRange = ITimeRangeFactory.<Instant>temporal(
                Duration.ofMillis(200), Duration.ofMillis(100), Duration.ZERO, null).timeRange(Instant.now());
        IAlarmService<Instant, Instant> alarmService = alarmService(timeRange, Duration.ZERO);
        Thread thread = new Thread(() -> alarmService.process(ignore -> {}));
        thread.start();
        thread.join(2000);
        assertFalse(thread.isAlive(), "AlarmService has to end its process at the end of the range");
        Instant instant = timeRange.getStartInstant();
        CompletableFuture<Instant> completableFuture = alarmService.addElement(instant).toCompletableFuture();
        assertEquals(instant, assertDoesNotThrow(() -> completableFuture.get(1, TimeUnit.SECONDS),
                "AlarmService has to complete a request after the end of the process at once"),
                "AlarmService has to reject the elements of a request after the end of the process");
    }

    @Test
    void rejectedAtTheEndIsNotApplied() throws InterruptedException {
        ITimeRange<Instant, Instant> timeRange = timeRange();
        IAlarmService<Instant, Instant> alarmService = alarmService(timeRange, Duration.ZERO);
        Instant first = timeRange.getStartInstant();
        Instant second = first.plusMillis(1);
        AtomicReference<CompletableFuture<Instant>> secondFuture = new AtomicReference<>();
        alarmService.addElement(first);
        // the consumer of the first alarm adds the second one and ends the process
        Thread thread = new Thread(() -> {
            try {
                alarmService.process(ignore -> {
                    secondFuture.set(alarmService.addElement(second).toCompletableFuture());
                    throw new IllegalStateException();
                });
            } catch (IllegalStateException ignore) {
                // the end of the process
            }
        });
        thread.start();
        thread.join(2000);
        assertNotNull(secondFuture.get(), "The consumer of the first alarm has to add the second one");
        assertEquals(second, assertDoesNotThrow(() -> secondFuture.get().get(1, TimeUnit.SECONDS),
                "AlarmService has to complete the request at the end of the process"),
                "AlarmService has to reject the second alarm at the end of the process");
        // the next process does not fire the rejected alarm, which is due
        CompletableFuture<Collection<Instant>> fired = new CompletableFuture<>();
        Thread next = new Thread(() -> alarmService.process(fired::complete));
        next.start();
        try {
            assertThrows(TimeoutException.class, () -> fired.get(500, TimeUnit.MILLISECONDS),
                    "AlarmService must not apply a request that it has rejected");
        } finally {
            next.interrupt();
        }
    }

    @Test
    void failedRegistration() throws InterruptedException {
        ITimeRange<TemporalAccessor, TemporalAccessor> timeRange = ITimeRangeFactory.<TemporalAccessor>temporal(
                Duration.ofSeconds(5), Duration.ofMillis(100), Duration.ofMillis(250), null).timeRange(Instant.now());
        IAlarmService<TemporalAccessor, TemporalAccessor> alarmService = new AlarmTimeRangeService<>(timeRange, Duration.ZERO);
        CountDownLatch firedCdl = new CountDownLatch(1);
        Thread thread = new Thread(() -> alarmService.singleProcess(ignore -> firedCdl.countDown()));
        thread.start();
        try {
            CompletableFuture<TemporalAccessor> accepted = alarmService.addElement(Instant.now().plusMillis(300)).toCompletableFuture();
            assertNull(assertDoesNotThrow(() -> accepted.get(1, TimeUnit.SECONDS), "AlarmService has to register the alarm"),
                    "AlarmService has to accept the alarm");
            // a LocalDateTime is no instant: its registration fails
            CompletableFuture<TemporalAccessor> failed = alarmService.addElement(LocalDateTime.now()).toCompletableFuture();
            ExecutionException exception = assertThrows(ExecutionException.class, () -> failed.get(1, TimeUnit.SECONDS),
                    "AlarmService has to complete a failed registration with its exception");
            assertEquals(DateTimeException.class, exception.getCause().getClass(), "AlarmService has to pass the exception of the registration");
            assertTrue(firedCdl.await(2, TimeUnit.SECONDS), "AlarmService has to go on after a failed registration and fire the alarm");
        } finally {
            thread.interrupt();
        }
    }

    @Test
    @SuppressWarnings("ConstantConditions")
    void processNull() throws InterruptedException {
        ITimeRange<Instant, Instant> timeRange = timeRange();
        IAlarmService<Instant, Instant> alarmService = alarmService(timeRange, Duration.ZERO);
        assertThrows(NullPointerException.class, () -> alarmService.process(null), "process with a null consumer has to throw NPE");
        CountDownLatch firedCdl = new CountDownLatch(1);
        Thread thread = new Thread(() -> alarmService.singleProcess(ignore -> firedCdl.countDown()));
        thread.start();
        try {
            alarmService.addElement(timeRange.getStartInstant());
            assertTrue(firedCdl.await(1, TimeUnit.SECONDS), "AlarmService has to process after process(null)");
        } finally {
            thread.interrupt();
        }
    }

    @Test
    void elementsCopied() throws InterruptedException {
        ITimeRange<Instant, Instant> timeRange = timeRange();
        IAlarmService<Instant, Instant> alarmService = alarmService(timeRange, Duration.ZERO);
        List<Instant> buffer = new ArrayList<>(Arrays.asList(timeRange.getStartInstant(), timeRange.getStartInstant().plusMillis(1)));
        CompletableFuture<Collection<Instant>> completableFuture = alarmService.addElements(buffer).toCompletableFuture();
        // the caller uses its collection again before the process takes the request
        buffer.clear();
        CountDownLatch firedCdl = new CountDownLatch(2);
        Thread thread = new Thread(() -> alarmService.singleProcess(ignore -> firedCdl.countDown()));
        thread.start();
        try {
            assertEquals(Collections.emptyList(), assertDoesNotThrow(() -> completableFuture.get(1, TimeUnit.SECONDS),
                    "AlarmService has to register the request"), "AlarmService has to accept both alarms");
            assertTrue(firedCdl.await(1, TimeUnit.SECONDS), "AlarmService has to fire the alarms of a request whatever the caller does with its collection");
        } finally {
            thread.interrupt();
        }
    }

    @Test
    void waitByDefault() throws InterruptedException {
        ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
        assumeTrue(threadMXBean.isCurrentThreadCpuTimeSupported(), "The test needs the CPU time of a thread");
        ITimeRange<Instant, Instant> timeRange = timeRange();
        // the default minimal wait; the alarms are 60 ms apart
        IAlarmService<Instant, Instant> alarmService = alarmService(timeRange, null);
        Instant start = Instant.now();
        alarmService.addElements(IntStream.rangeClosed(1, 20).mapToObj(i -> start.plusMillis(60L * i)).collect(Collectors.toList()));
        // the CPU time and the time of the thread of the process at every alarm
        List<long[]> times = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch firedCdl = new CountDownLatch(20);
        Thread thread = new Thread(() -> alarmService.singleProcess(ignore -> {
            times.add(new long[] {threadMXBean.getCurrentThreadCpuTime(), System.nanoTime()});
            firedCdl.countDown();
        }));
        thread.start();
        try {
            assertTrue(firedCdl.await(5, TimeUnit.SECONDS), "AlarmService has to fire the alarms");
            long[] first = times.get(0);
            long[] last = times.get(19);
            long share = 100 * (last[0] - first[0]) / (last[1] - first[1]);
            assertTrue(share < 50, () -> "AlarmService has to wait for its alarms by default, not spin: " + share + "% of a core");
        } finally {
            thread.interrupt();
        }
    }

    @Test
    void interruptIsNoWarning() throws InterruptedException {
        Logger logger = Logger.getLogger("com.github.sftwnd.crayfish.alarms.service.AlarmService");
        Level level = logger.getLevel();
        List<Level> levels = Collections.synchronizedList(new ArrayList<>());
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord logRecord) {
                levels.add(logRecord.getLevel());
            }
            @Override
            public void flush() {
                // the records are kept in the list
            }
            @Override
            public void close() {
                // nothing to release
            }
        };
        logger.setLevel(Level.ALL);
        logger.addHandler(handler);
        try {
            IAlarmService<Instant, Instant> alarmService = alarmService(timeRange(), Duration.ZERO);
            // an interrupt is the normal stop of a process before the end of its range
            Thread thread = new Thread(() -> alarmService.process(ignore -> {}));
            thread.start();
            thread.interrupt();
            thread.join(1000);
            assertFalse(thread.isAlive(), "AlarmService has to end its process on an interrupt");
            assertEquals(Collections.singletonList(Level.FINE), levels, "AlarmService has to log the interrupt of its process at FINE");
        } finally {
            logger.removeHandler(handler);
            logger.setLevel(level);
        }
    }

    @Test
    void nullTimeOffset() throws InterruptedException {
        // the supplier of the process gives null
        assertFiresWithoutTimeOffset((alarmService, consumer) -> alarmService.process(consumer, (Duration) null));
    }

    @Test
    void nullTimeOffsetSupplier() throws InterruptedException {
        assertFiresWithoutTimeOffset((alarmService, consumer) -> alarmService.process(consumer, (Supplier<Duration>) null));
    }

    private void assertFiresWithoutTimeOffset(
            BiConsumer<IAlarmService<Instant, Instant>, Consumer<Collection<Instant>>> process) throws InterruptedException {
        ITimeRange<Instant, Instant> timeRange = timeRange();
        IAlarmService<Instant, Instant> alarmService = alarmService(timeRange, Duration.ZERO);
        CountDownLatch firedCdl = new CountDownLatch(1);
        Thread thread = new Thread(() -> process.accept(alarmService, ignore -> firedCdl.countDown()));
        thread.start();
        try {
            alarmService.addElement(timeRange.getStartInstant());
            assertTrue(firedCdl.await(1, TimeUnit.SECONDS), "AlarmService has to fire the alarm without a time offset");
        } finally {
            thread.interrupt();
        }
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
                // without the signal the process does not start, and the test fails on its start
                if (serviceCdl.await(1, TimeUnit.SECONDS)) {
                    processCdl.countDown();
                    alarmService.process(elements -> elements.stream().findFirst().ifPresent(instant -> {
                        firedFuture.complete(Instant.now());
                        throw new IllegalStateException();
                    }), Duration.ofSeconds(3));
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (IllegalStateException expected) {
                // the consumer of the alarm ends the process with it
            }
        });
        serviceThread.start();
        serviceCdl.countDown();
        assertTrue(processCdl.await(1, TimeUnit.SECONDS), "AlarmService process thread has to be started");
        // three seconds after the second the range was created in: inside the range, which ends a second later,
        // whenever the test gets here (a new reading of the clock may be in the next second); without the time offset
        // of three seconds the alarm would fire two to three seconds later
        Instant instant = timeRange.getStartInstant().plusSeconds(4);
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
    static void startUp() {
        Logger.getLogger("com.github.sftwnd.crayfish.alarms.service.AlarmService")
                .setLevel(Level.OFF);
    }

}