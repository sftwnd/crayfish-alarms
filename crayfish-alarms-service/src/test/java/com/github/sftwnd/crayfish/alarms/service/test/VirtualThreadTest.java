package com.github.sftwnd.crayfish.alarms.service.test;

import com.github.sftwnd.crayfish.alarms.service.AlarmTimeRangeService;
import com.github.sftwnd.crayfish.alarms.timerange.ITimeRange;
import com.github.sftwnd.crayfish.alarms.timerange.ITimeRangeFactory;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A virtual thread that runs the process does not keep the only carrier of the scheduler while the process waits.
 * The scheduler takes its parallelism at its start, so the virtual threads run in a JVM of their own, with one carrier
 * and no compensation of a pinned one. The tests are compiled for Java 8: the virtual threads are made by reflection.
 */
class VirtualThreadTest {

    private static final String DELAY = "DELAY_MS=";
    // the process waits 3 s for its alarm: a pinned carrier would run the other virtual thread after it
    private static final long WAIT_MS = 3_000L;
    private static final long DELAY_LIMIT_MS = 1_000L;

    @Test
    void carrierIsNotPinnedTest() throws IOException, InterruptedException {
        assumeTrue(feature() >= 21, "Virtual threads need JDK 21");
        List<String> command = new ArrayList<>(Arrays.asList(
                Paths.get(System.getProperty("java.home"), "bin", "java").toString(),
                "-Djdk.virtualThreadScheduler.parallelism=1",
                "-Djdk.virtualThreadScheduler.maxPoolSize=1",
                "-cp", System.getProperty("java.class.path"),
                Carrier.class.getName()));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        List<String> output = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                output.add(line);
            }
        }
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "The JVM of the virtual threads has to end");
        assertEquals(0, process.exitValue(), () -> "The JVM of the virtual threads has to end normally: " + output);
        long delay = output.stream()
                .filter(line -> line.startsWith(DELAY))
                .mapToLong(line -> Long.parseLong(line.substring(DELAY.length())))
                .findFirst()
                .orElseThrow(() -> new AssertionError("The JVM of the virtual threads has to print the delay: " + output));
        assertTrue(delay < DELAY_LIMIT_MS, () -> "Another virtual thread has to get the carrier while the process waits, not after "
                + delay + " ms");
    }

    private static int feature() {
        String version = System.getProperty("java.specification.version");
        return Integer.parseInt(version.startsWith("1.") ? version.substring(2) : version);
    }

    /**
     * The process waits in a virtual thread for its alarm; another virtual thread is started then, and the time until
     * it runs is printed
     */
    static final class Carrier {

        private Carrier() {
        }

        public static void main(String[] args) throws ReflectiveOperationException, InterruptedException {
            Logger.getLogger("com.github.sftwnd.crayfish.alarms.service.AlarmService").setLevel(Level.OFF);
            ITimeRange<Instant, Instant> timeRange = ITimeRangeFactory.<Instant>temporal(
                    Duration.ofSeconds(10), Duration.ofSeconds(1), Duration.ZERO, null).timeRange(Instant.now());
            AlarmTimeRangeService<Instant, Instant> service = new AlarmTimeRangeService<>(timeRange, null);
            service.addElement(Instant.now().plusMillis(WAIT_MS));
            Thread processThread = startVirtualThread(() -> service.process(ignore -> {}));
            // the process waits for its alarm
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (processThread.getState() != Thread.State.TIMED_WAITING && System.nanoTime() < deadline) {
                Thread.sleep(1);
            }
            if (processThread.getState() != Thread.State.TIMED_WAITING) {
                throw new IllegalStateException("The process does not wait: " + processThread.getState());
            }
            CountDownLatch ran = new CountDownLatch(1);
            long start = System.nanoTime();
            startVirtualThread(ran::countDown);
            if (!ran.await(WAIT_MS * 4, TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("The other virtual thread has not run");
            }
            System.out.println(DELAY + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
            processThread.interrupt();
            processThread.join(TimeUnit.SECONDS.toMillis(5));
        }

        private static Thread startVirtualThread(Runnable runnable) throws ReflectiveOperationException {
            Object builder = Thread.class.getMethod("ofVirtual").invoke(null);
            try {
                return (Thread) Class.forName("java.lang.Thread$Builder").getMethod("start", Runnable.class).invoke(builder, runnable);
            } catch (InvocationTargetException exception) {
                throw new IllegalStateException(exception.getCause());
            }
        }

    }

}
