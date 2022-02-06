package com.github.sftwnd.crayfish.alarms.akka.timerange;

import akka.actor.DeadLetter;
import akka.actor.testkit.typed.javadsl.ActorTestKit;
import akka.actor.testkit.typed.javadsl.TestProbe;
import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.eventstream.EventStream;
import akka.actor.typed.javadsl.Behaviors;
import com.github.sftwnd.crayfish.alarms.akka.timerange.TimeRange.Command;
import com.github.sftwnd.crayfish.alarms.akka.timerange.TimeRange.FiredElementsConsumer;
import com.github.sftwnd.crayfish.alarms.timerange.TimeRangeConfig;
import com.github.sftwnd.crayfish.common.expectation.ExpectedPackage;
import com.typesafe.config.ConfigFactory;
import lombok.SneakyThrows;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimeRangeTest {

    static ActorTestKit testKit;
    Instant now;
    TimeRangeConfig<ExpectedPackage<String, Instant>, String> timeRangeConfig;

    @Test
    void createWithFiredConsumerAndAddElementsTest() throws InterruptedException {
        CountDownLatch countDownLatch = new CountDownLatch(1);
        var collectionConsumer = Mockito.spy(new FiredElementsConsumer<String>() {
            @Override public void accept(@Nonnull Collection<String> t) { countDownLatch.countDown(); }
        });
        Behavior<Command<ExpectedPackage<String, Instant>>> behavior = TimeRange.processor(now, timeRangeConfig, collectionConsumer, null);
        ActorRef<Command<ExpectedPackage<String, Instant>>> timeRangeActor = testKit.spawn(behavior,"createWithFiredConsumerAndAddElementsTest");
        ExpectedPackage<String, Instant> elmA = ExpectedPackage.pack("A", now);
        ExpectedPackage<String, Instant> elmB = ExpectedPackage.pack("B", now.minus(1, ChronoUnit.MINUTES));
        CountDownLatch cdl = new CountDownLatch(1);
        List<ExpectedPackage<String, Instant>> rejected = new ArrayList<>();
        TimeRange.addElements(timeRangeActor, List.of(elmA, elmB)).thenAccept(elements -> {
            rejected.addAll(elements);
            cdl.countDown();
        });
        assertTrue(cdl.await(500, TimeUnit.MILLISECONDS), "TimeRange has to proceed AddElements call");
        assertEquals(List.of(elmB), rejected, "element 'B' has to be rejected");
        assertTrue(countDownLatch.await(500, TimeUnit.MILLISECONDS), "collectionConsumer has to be called");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> argumentCaptor = ArgumentCaptor.forClass(Collection.class);
        Mockito.verify(collectionConsumer, Mockito.times(1)).accept(argumentCaptor.capture());
        assertEquals(List.of(elmA.getElement()), argumentCaptor.getValue(), "element 'A' has to be processed by TimeRange actor");
    }

    @Test
    void createWithFiredActorAndAddElementsTest() throws InterruptedException {
        TestProbe<String> testProbe = testKit.createTestProbe(String.class);
        try {
            ActorRef<String> actorRef = testProbe.getRef();
            Behavior<Command<ExpectedPackage<String, Instant>>> behavior = TimeRange.processor(now, timeRangeConfig, actorRef, str -> "OK:" + str, null);
            ActorRef<Command<ExpectedPackage<String, Instant>>> timeRangeActor = testKit.spawn(behavior, "createWithFiredActorAndAddElementsTest");
            ExpectedPackage<String, Instant> elmC = ExpectedPackage.pack("C", now);
            ExpectedPackage<String, Instant> elmD = ExpectedPackage.pack("D", now.minus(1, ChronoUnit.MINUTES));
            CountDownLatch countDownLatch = new CountDownLatch(1);
            List<String> rejected = new ArrayList<>();
            TimeRange.addElements(timeRangeActor, List.of(elmC, elmD), rejects -> {
                rejects.forEach(pack -> rejected.add(pack.getElement()));
                countDownLatch.countDown();
            });
            //noinspection ResultOfMethodCallIgnored
            countDownLatch.await(750, TimeUnit.MILLISECONDS);
            assertEquals(List.of(elmD.getElement()), rejected, "element 'D' has to be rejected");
        } finally {
            testProbe.stop();
        }
    }

    @SneakyThrows
    @Nonnull <R> R throwOnResult() {
        throw new Exception();
    }

    @Test
    void createThrowsOnAddElementsTest() {
        TestProbe<String> testProbe = testKit.createTestProbe(String.class);
        try {
            ActorRef<String> actorRef = testProbe.getRef();
            Behavior<Command<ExpectedPackage<String, Instant>>> behavior = TimeRange.processor(now, timeRangeConfig, actorRef, str -> "OK:" + str, null);
            ActorRef<Command<ExpectedPackage<String, Instant>>> timeRangeActor = testKit.spawn(behavior, "createThrowsOnAddElementsTest");
            ExpectedPackage<String, Instant> elmX = ExpectedPackage.supply("X", this::throwOnResult);
            CompletableFuture<Collection<ExpectedPackage<String, Instant>>> completableFuture = new CompletableFuture<>();
            TimeRange.addElements(timeRangeActor, List.of(elmX), completableFuture::complete, completableFuture::completeExceptionally);
            assertThrows(Throwable.class, () -> completableFuture.get(500, TimeUnit.MILLISECONDS), "After throw on TimeRange::addElements onThrow consumer has to be fired");
        } finally {
            testProbe.stop();
        }
    }

    @Test
    void addEmptyElements() throws InterruptedException {
        Behavior<Command<ExpectedPackage<String, Instant>>> behavior = TimeRange.processor(now, timeRangeConfig, elements -> {}, null);
        ActorRef<Command<ExpectedPackage<String, Instant>>> timeRangeActor = testKit.spawn(behavior,"addEmptyElements");
        CountDownLatch cdl = new CountDownLatch(1);
        List<ExpectedPackage<String, Instant>> rejected = new ArrayList<>();
        TimeRange.addElements(timeRangeActor, Collections.emptyList()).thenAccept(elements -> { rejected.addAll(elements); cdl.countDown(); });
        assertTrue(cdl.await(500, TimeUnit.MILLISECONDS), "TimeRange has to process AddElements");
        assertEquals(Collections.emptyList(), rejected, "AddElements has return empty list for the empty request");
    }

    @Test
    void testAddOnAfterLastBeforeExpired() throws InterruptedException {
        now = Instant.now();
        Instant start = now.truncatedTo(ChronoUnit.MINUTES).minus(1, ChronoUnit.MINUTES);
        Duration duration = Duration.ofMinutes(1);
        Instant end = start.plus(duration);
        CountDownLatch fireCdl = new CountDownLatch(1);
        List<String> fired = new ArrayList<>();
        timeRangeConfig = TimeRangeConfig.packable(duration, Duration.ofSeconds(1), Duration.ofMillis(255), Duration.between(end,Instant.now()).plusMillis(50), null);
        Behavior<Command<ExpectedPackage<String, Instant>>> behavior = TimeRange.processor(start, timeRangeConfig, elm -> { fired.addAll(elm); fireCdl.countDown(); }, null);
        ActorRef<Command<ExpectedPackage<String, Instant>>> timeRangeActor = testKit.spawn(behavior,"testAddOnAfterLastBeforeExpired");
        try {
            ExpectedPackage<String, Instant> elmX = ExpectedPackage.pack("X", start.plusSeconds(1));
            assertTrue(elmX.happened(end), "elmX has to be happend on the end of TimeRanger");
            assertFalse(elmX.happened(start), "elmX hasn't got to be happend for the start of TimeRange");
            assertTrue(now.isAfter(end), "TimeRange period has to be expired");
            Collection<ExpectedPackage<String, Instant>> elements = List.of(elmX);
            CountDownLatch rejectCdl = new CountDownLatch(1);
            List<ExpectedPackage<String, Instant>> rejected = new ArrayList<>();
            TimeRange.addElements(timeRangeActor, elements).thenAccept(elm -> {
                rejected.addAll(elm);
                rejectCdl.countDown();
            });
            assertTrue(rejectCdl.await(500, TimeUnit.MILLISECONDS), "TimeRange has to process addElements");
            assertTrue(rejected.isEmpty(), "Elements hasn't got to be rejected until TimeRange is not expires");
            assertTrue(fireCdl.await(500, TimeUnit.MILLISECONDS), "TimeRange has to fire addElements");
            assertEquals(List.of(elmX.getElement()), fired, "Elements has to be fired until TimeRange is not expires");
        } finally {
            testKit.stop(timeRangeActor);
        }
    }

    @Test
    void testTimeRangeStop() throws InterruptedException {
        ActorRef<Command<ExpectedPackage<String, Instant>>> timeRangeActor = testKit.spawn(TimeRange.processor(now, timeRangeConfig, elements -> {}, null),"testTimeRangeStop");
        CountDownLatch cdl = new CountDownLatch(1);
        TimeRange.stop(timeRangeActor).thenAccept(ignore -> cdl.countDown());
        assertTrue(cdl.await(500, TimeUnit.MILLISECONDS), "TimeRange has to be stopped");
    }

    @Test
    void testDeadAllSubscriber() throws InterruptedException {
        now = Instant.now().minus(1, ChronoUnit.MINUTES);
        List<String> fired = new ArrayList<>();
        CountDownLatch fireLatch = new CountDownLatch(1);
        // Start timeRange actor
        ActorRef<Command<ExpectedPackage<String, Instant>>> timeRangeActor = testKit.spawn(
                TimeRange.processor(now, timeRangeConfig, elements -> { fired.addAll(elements); fireLatch.countDown();}, null),
                "testDeadAllSubscriber");
        // Start Shutdown actor
        ActorRef<DeadLetter> deadLetterSubscriber = testKit.spawn(Behaviors.setup(context -> TimeRange.timeRangeDeadLetterSubscriber(timeRangeActor)));
        try {
            ExpectedPackage<String, Instant> elmXZ = ExpectedPackage.pack("XZ", now.plus(10, ChronoUnit.SECONDS));
            // Subscribe to dead letters
            testKit.system().eventStream().tell(new EventStream.Subscribe<>(DeadLetter.class, deadLetterSubscriber));
            // Add elements to TimeRange
            TimeRange.addElements(timeRangeActor, List.of(elmXZ));
            assertTrue(fireLatch.await(500, TimeUnit.MILLISECONDS), "TimeRange.addElements command has to be proceed");
            assertEquals(List.of(elmXZ.getElement()), fired, "All elements has to be fired by AddElements");
            // Stop timeRangeActor and wait for complete
            CountDownLatch stopLatch = new CountDownLatch(1);
            TimeRange.stop(timeRangeActor).thenAccept(ignore -> stopLatch.countDown());
            stopLatch.await();
            // AddElements
            List<ExpectedPackage<String, Instant>> rejects = new ArrayList<>();
            CountDownLatch addLatch = new CountDownLatch(1);
            TimeRange.addElements(timeRangeActor, List.of(elmXZ)).thenAccept(rejected -> { rejects.addAll(rejected); addLatch.countDown(); });
            assertTrue(addLatch.await(500, TimeUnit.MILLISECONDS), "TimeRange.addElements command has to be proceed");
            assertEquals(List.of(elmXZ), rejects, "All elements has to be rejected by AddElements");
        } finally {
            testKit.stop(deadLetterSubscriber);
        }
    }

    @BeforeEach
    void timeRangeItems() {
        this.now = Instant.now().truncatedTo(ChronoUnit.MINUTES);
        timeRangeConfig = TimeRangeConfig.packable(Duration.ofSeconds(60), Duration.ofSeconds(5), Duration.ofMillis(255), Duration.ofSeconds(15), null);
    }

    @BeforeEach
    void clean() {
        this.now = null;
        this.timeRangeConfig = null;
    }

    @BeforeAll
    static void startUp() {
        testKit = ActorTestKit.create("timeRangeTest", ConfigFactory.load("akka-test.conf"));
    }

    @AfterAll
    static void tearDown() {
        testKit.shutdownTestKit();
        testKit = null;
    }

}