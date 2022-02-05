package com.github.sftwnd.crayfish.alarms.akka.timerange;

import akka.actor.testkit.typed.javadsl.ActorTestKit;
import akka.actor.typed.ActorRef;
import akka.actor.typed.javadsl.Behaviors;
import com.github.sftwnd.crayfish.alarms.akka.timerange.TimeRange.FiredElementsConsumer;
import com.github.sftwnd.crayfish.alarms.timerange.TimeRangeConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;

class TimeRangeServiceTest {

    private static TimeRangeConfig<Instant,Instant> config;
    private static ActorTestKit actorTestKit;

    @Test
    void testRegionReject() throws InterruptedException {
        @SuppressWarnings("unchecked")
        ActorRef<TimeRange.Command<Instant>> service = actorTestKit.spawn(
                Behaviors.setup(context -> TimeRange.service(context, config, Mockito.mock(FiredElementsConsumer.class), Duration.ZERO, 1, 1, Mockito.mock(TimeRange.TimeRangeWakedUp.class)))
                , "testRegionReject");
        Instant instant = Instant.now().plus(1, ChronoUnit.HOURS);
        CountDownLatch cdl = new CountDownLatch(1);
        List<Instant> rejects = new ArrayList<>();
        TimeRange.addElements(service, List.of(instant)).thenAccept(elements -> { rejects.addAll(elements); cdl.countDown(); });
        assertTrue(cdl.await(1, TimeUnit.SECONDS), "command has to pe processed");
        assertEquals(List.of(instant), rejects, "all commands has to be rejected");
    }

    @Test
    @SuppressWarnings("unchecked")
    void testStartRegion() throws InterruptedException {
        Instant now = Instant.now();
        AtomicBoolean firstBefore = new AtomicBoolean(false);
        CountDownLatch countDownLatch = new CountDownLatch(2);
        TimeRange.TimeRangeWakedUp timeRangeWakedUp = (start, end) -> {
            assertTrue(end.isAfter(now), "timeRangeWakedUp has to be after now");
            if (!start.isAfter(now)) {
                assertTrue(firstBefore.compareAndSet(false, true), "just one region before now has to be started" );
            }
            countDownLatch.countDown();
        };
        ActorRef<TimeRange.Command<Instant>> service = actorTestKit.spawn(
                Behaviors.setup(context -> TimeRange.service(context, config, Mockito.mock(FiredElementsConsumer.class), Duration.ZERO, 1, 1, timeRangeWakedUp))
                , "testStartRegion");
        assertTrue(countDownLatch.await(1, TimeUnit.SECONDS), "two timeRangeWakedUp calls has to be produced");
    }

    @Test
    @SuppressWarnings("unchecked")
    void testFiredCallback() throws InterruptedException {
        FiredElementsConsumer<Instant> firedElementsConsumer = Mockito.mock(FiredElementsConsumer.class);
        Instant now = Instant.now();
        ActorRef<TimeRange.Command<Instant>> service = actorTestKit.spawn(
                Behaviors.setup(context -> TimeRange.service(context, config, firedElementsConsumer, Duration.ZERO, 1, 1, Mockito.mock(TimeRange.TimeRangeWakedUp.class)))
                , "testFiredCallback");
        CountDownLatch cdl = new CountDownLatch(1);
        List<Instant> rejects = new ArrayList<>();
        TimeRange.addElements(service, List.of(now)).thenAccept(elements -> { rejects.addAll(elements); cdl.countDown(); });
        assertTrue(cdl.await(1, TimeUnit.SECONDS), "command has to pe processed");
        assertTrue(rejects.isEmpty(), "no one command has to be rejected");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<Instant>> firedCollection = ArgumentCaptor.forClass(Collection.class);
        verify(firedElementsConsumer).accept(firedCollection.capture());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testStartAfterTermination() throws InterruptedException {
        CountDownLatch countDownLatch = new CountDownLatch(3);
        TimeRange.TimeRangeWakedUp timeRangeWakedUp = (start, end) -> countDownLatch.countDown();
        ActorRef<TimeRange.Command<Instant>> service = actorTestKit.spawn(
                Behaviors.setup( context -> TimeRange.service(
                        context,
                        TimeRangeConfig.create(Duration.ofMillis(125), Duration.ofMillis(50), Duration.ofMillis(1), Duration.ofMillis(25), i->i, null, i->i),
                        Mockito.mock(FiredElementsConsumer.class), Duration.ZERO, 1, 1, timeRangeWakedUp))
                , "testStartRegion");
        assertTrue(countDownLatch.await(1, TimeUnit.SECONDS), "at least three timeRangeWakedUp calls has to be produced (with at least one region termination)");
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        config = TimeRangeConfig.create(Duration.ofSeconds(10), Duration.ofMillis(100), Duration.ofMillis(5), Duration.ofMillis(100), i->i, null, i->i);
        actorTestKit = ActorTestKit.create("timeRangeServiceTest");
    }

    @AfterEach
    void tearDown() {
        config = null;
        actorTestKit.shutdownTestKit();
        actorTestKit = null;
    }

}
