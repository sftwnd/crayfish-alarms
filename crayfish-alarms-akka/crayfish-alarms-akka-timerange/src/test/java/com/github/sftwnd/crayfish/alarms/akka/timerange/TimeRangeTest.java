package com.github.sftwnd.crayfish.alarms.akka.timerange;

import akka.actor.DeadLetter;
import akka.actor.testkit.typed.javadsl.ActorTestKit;
import akka.actor.testkit.typed.javadsl.TestProbe;
import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
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
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimeRangeTest {

    static ActorTestKit testKit;
    Instant now;
    TimeRangeConfig<ExpectedPackage<String, Instant>, String> timeRangeConfig;

    @Test
    void createWithFiredConsumerAndAddElementsTest() throws ExecutionException, InterruptedException {
        CountDownLatch countDownLatch = new CountDownLatch(1);
        var collectionConsumer = Mockito.spy(new FiredElementsConsumer<String>() {
            @Override public void accept(@Nonnull Collection<String> t) { countDownLatch.countDown(); }
        });
        Behavior<Command<ExpectedPackage<String, Instant>>> behavior = TimeRange.processor(now, timeRangeConfig, collectionConsumer, null);
        ActorRef<Command<ExpectedPackage<String, Instant>>> timeRangeActor = testKit.spawn(behavior,"createWithFiredConsumerAndAddElementsTest");
        ExpectedPackage<String, Instant> elmA = ExpectedPackage.pack("A", now);
        ExpectedPackage<String, Instant> elmB = ExpectedPackage.pack("B", now.minus(1, ChronoUnit.MINUTES));
        CompletableFuture<Collection<ExpectedPackage<String, Instant>>> completableFuture = TimeRange.addElements(timeRangeActor, List.of(elmA, elmB));
        assertDoesNotThrow(() -> completableFuture.get(500, TimeUnit.MILLISECONDS), "TimeRange hasn't got to throw on AddElements call");
        Collection<String> rejected = completableFuture.get().stream().map(ExpectedPackage::getElement).collect(Collectors.toList());
        assertEquals(List.of(elmB.getElement()), rejected, "element 'B' has to be rejected");
        assertTrue(countDownLatch.await(500, TimeUnit.MILLISECONDS), "collectionConsumer has to be called");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> argumentCaptor = ArgumentCaptor.forClass(Collection.class);
        Mockito.verify(collectionConsumer, Mockito.times(1)).accept(argumentCaptor.capture());
        assertEquals(Set.of(elmA.getElement()), argumentCaptor.getValue(), "element 'A' has to be processed by TimeRange actor");
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
    void addEmptyElements() throws ExecutionException, InterruptedException, TimeoutException {
        Behavior<Command<ExpectedPackage<String, Instant>>> behavior = TimeRange.processor(now, timeRangeConfig, elements -> {}, null);
        ActorRef<Command<ExpectedPackage<String, Instant>>> timeRangeActor = testKit.spawn(behavior,"addEmptyElements");
        CompletableFuture<Collection<ExpectedPackage<String, Instant>>> completableFuture = TimeRange.addElements(timeRangeActor, Collections.emptyList());
        assertDoesNotThrow(() -> completableFuture.get(500, TimeUnit.MILLISECONDS), "TimeRange hasn't got to throw on AddElements with empty list call");
        assertEquals(Collections.emptySet(), completableFuture.get(500, TimeUnit.MILLISECONDS), "AddElements has return empty list for the empty request");
    }

    @Test
    void testAddOnExpired() throws ExecutionException, InterruptedException, TimeoutException {
        now = Instant.now().minus(1, ChronoUnit.MINUTES);
        timeRangeConfig = TimeRangeConfig.packable(Duration.ofMinutes(-1), Duration.ofSeconds(5), Duration.ofMillis(255), Duration.ofSeconds(15), null);
        Behavior<Command<ExpectedPackage<String, Instant>>> behavior = TimeRange.processor(now, timeRangeConfig, elements -> {}, null);
        ActorRef<Command<ExpectedPackage<String, Instant>>> timeRangeActor = testKit.spawn(behavior,"testAddOnExpired");
        CountDownLatch latch = new CountDownLatch(1);
        ActorRef<DeadLetter> deadLetterSubscriber = testKit.spawn(Behaviors.setup(context -> new DeadCommandTestActor<>(context, timeRangeActor, latch, "testAddOnExpired-dead")));
        latch.await();
        ExpectedPackage<String, Instant> elmX = ExpectedPackage.pack("X", now.minus(30, ChronoUnit.SECONDS));
        assertTrue(elmX.happened(now.minusMillis(1)), "elmX hasn't got to be happend near the end of timeRangeHolder");
        assertFalse(elmX.happened(now.plus(timeRangeConfig.getDuration())), "elmX has to be happend on the end of timeRangeHolder");
        assertTrue(now.plus(timeRangeConfig.getDuration()).plus(timeRangeConfig.getCompleteTimeout()).isBefore(Instant.now()), "TimeRange period has to be expired");
        Collection<ExpectedPackage<String, Instant>> elements = List.of(elmX);
        CompletableFuture<Collection<ExpectedPackage<String, Instant>>> completableFuture = TimeRange.addElements(timeRangeActor, elements);
        assertDoesNotThrow(() -> completableFuture.get(500, TimeUnit.MILLISECONDS), "TimeRange has to process addElements without throws");
        assertEquals(elements, completableFuture.get(500, TimeUnit.MILLISECONDS), "Elements has to be rejected by addElements to expired range");
        testKit.stop(deadLetterSubscriber);
    }

    @Test
    void testDeadAllSubscriber() throws ExecutionException, InterruptedException, TimeoutException {
        now = Instant.now().minus(1, ChronoUnit.MINUTES);
        timeRangeConfig = TimeRangeConfig.packable(Duration.ofMinutes(-1), Duration.ofSeconds(5), Duration.ofMillis(255), Duration.ofSeconds(15), null);
        ActorRef<Command<ExpectedPackage<String, Instant>>> timeRangeActor = testKit.spawn(TimeRange.processor(now, timeRangeConfig, elements -> {}, null),"testDeadSubscriber");
        CountDownLatch latch = new CountDownLatch(1);
        ActorRef<DeadLetter> deadLetterSubscriber = testKit.spawn(Behaviors.setup(context -> new DeadCommandTestActor<>(context, timeRangeActor, latch, "testDeadAllSubscriber-dead")));
        latch.await();
        ExpectedPackage<String, Instant> elmX = ExpectedPackage.pack("Z", now.minus(30, ChronoUnit.SECONDS));
        Collection<ExpectedPackage<String, Instant>> elements = List.of(elmX);
        CompletableFuture<Collection<ExpectedPackage<String, Instant>>> completableFuture = TimeRange.addElements(timeRangeActor, elements);
        assertDoesNotThrow(() -> completableFuture.get(500, TimeUnit.MILLISECONDS), "TimeRange has to process addElements without throws");
        assertEquals(elements, completableFuture.get(500, TimeUnit.MILLISECONDS), "Elements has to be rejected by addElements to expired range");
        testKit.stop(deadLetterSubscriber);
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
        testKit = ActorTestKit.create(ConfigFactory.load("akka-test.conf"));
    }

    @AfterAll
    static void tearDown() {
        testKit.shutdownTestKit();
        testKit = null;
    }

    static class DeadCommandTestActor<X,M> extends AbstractBehavior<X> {
        public DeadCommandTestActor(ActorContext<X> context, ActorRef<Command<M>> spyActor, CountDownLatch latch, String deadActorname) {
            super(context);
            TimeRange.subscribeToDeadCommands(context, spyActor, deadActorname);
            Optional.ofNullable(latch).ifPresent(CountDownLatch::countDown);
        }
        @Override
        public Receive<X> createReceive() {
            return newReceiveBuilder().build();
        }
    }

}