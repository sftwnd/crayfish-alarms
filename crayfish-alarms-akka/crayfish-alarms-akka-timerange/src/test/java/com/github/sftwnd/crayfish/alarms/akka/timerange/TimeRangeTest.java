package com.github.sftwnd.crayfish.alarms.akka.timerange;

import akka.actor.testkit.typed.javadsl.ActorTestKit;
import akka.actor.testkit.typed.javadsl.TestProbe;
import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
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

    /*
    @Test
    void testAddOnExpired() throws ExecutionException, InterruptedException, TimeoutException {
        now = Instant.now().minus(1, ChronoUnit.MINUTES);
        timeRangeConfig = TimeRangeConfig.packable(Duration.ofMinutes(-1), Duration.ofSeconds(5), Duration.ofMillis(255), Duration.ofSeconds(15), null);
        Behavior<Command<ExpectedPackage<String, Instant>>> behavior = TimeRange.processor(now, timeRangeConfig, elements -> {}, null);
        ActorRef<Command<ExpectedPackage<String, Instant>>> timeRangeActor = testKit.spawn(behavior,"testAddOnExpired");
        CountDownLatch latch = new CountDownLatch(1);
        ActorRef<DeadLetter> deadLetterSubscriber = testKit.spawn(Behaviors.setup(context -> new DeadCommandTestActor<>(context, timeRangeActor, latch, "testAddOnExpired-dead")));
        try {
            latch.await();
            ExpectedPackage<String, Instant> elmX = ExpectedPackage.pack("X", now.minus(30, ChronoUnit.SECONDS));
            assertTrue(elmX.happened(now.minusMillis(1)), "elmX hasn't got to be happend near the end of timeRangeHolder");
            assertFalse(elmX.happened(now.plus(timeRangeConfig.getDuration())), "elmX has to be happend on the end of timeRangeHolder");
            assertTrue(now.plus(timeRangeConfig.getDuration()).plus(timeRangeConfig.getCompleteTimeout()).isBefore(Instant.now()), "TimeRange period has to be expired");
            Collection<ExpectedPackage<String, Instant>> elements = List.of(elmX);
            CountDownLatch cdl = new CountDownLatch(1);
            List<ExpectedPackage<String, Instant>> rejected = new ArrayList<>();
            TimeRange.addElements(timeRangeActor, elements).thenAccept(elm -> {
                rejected.addAll(elm);
                cdl.countDown();
            });
            assertTrue(cdl.await(500, TimeUnit.MILLISECONDS), "TimeRange has to process addElements");
            assertEquals(elements, rejected, "Elements has to be rejected by addElements to expired range");
        } finally {
            testKit.stop(deadLetterSubscriber);
        }
    }
    */
    @Test
    void testDeadAllSubscriber() {//throws ExecutionException, InterruptedException, TimeoutException {
        // // A minute ago.
        // now = Instant.now().minus(1, ChronoUnit.MINUTES);
        // // Range to now.
        // timeRangeConfig = TimeRangeConfig.packable(Duration.ofSeconds(45), Duration.ofSeconds(5), Duration.ofMillis(255), Duration.ofSeconds(15).plusMillis(15), null);
        // CompletableFuture<ActorRef<Command<ExpectedPackage<String, Instant>>>> timeRangeFuture = new CompletableFuture<>();
        // CompletableFuture<Void> terminatedFuture = new CompletableFuture<>();
        // Behavior<Command<ExpectedPackage<String, Instant>>> timeRangeBehavior = TimeRange.processor(now, timeRangeConfig, elements -> {}, null);
        // ActorRef<Command<ExpectedPackage<String, Instant>>> spySupervisor = testKit.spawn(
        //         Behaviors.setup(context -> new WatchSupervisor<>(context, timeRangeBehavior, timeRangeFuture, terminatedFuture)),
        //         "testDeadAllSubscriber-spySupervisor"
        // );
        // CountDownLatch countDownLatch = new CountDownLatch(2);
        // timeRangeFuture.thenAccept(ignore -> countDownLatch.countDown());
        // terminatedFuture.thenAccept(ignore -> countDownLatch.countDown());
        // System.out.println(countDownLatch.await(500, TimeUnit.MILLISECONDS));


        // // now = Instant.now().minus(1, ChronoUnit.MINUTES);
        // // timeRangeConfig = TimeRangeConfig.packable(Duration.ofMinutes(-1), Duration.ofSeconds(5), Duration.ofMillis(255), Duration.ofSeconds(15), null);
        // // ActorRef<Command<ExpectedPackage<String, Instant>>> timeRangeActor = testKit.spawn(TimeRange.processor(now, timeRangeConfig, elements -> {}, null),"testDeadSubscriber");
        // // CountDownLatch latch = new CountDownLatch(1);
        // // ActorRef<DeadLetter> deadLetterSubscriber = testKit.spawn(Behaviors.setup(context -> new DeadCommandTestActor<>(context, timeRangeActor, latch, "testDeadAllSubscriber-dead")));
        // // latch.await();
        // // ExpectedPackage<String, Instant> elmX = ExpectedPackage.pack("Z", now.minus(30, ChronoUnit.SECONDS));
        // // Collection<ExpectedPackage<String, Instant>> elements = List.of(elmX);
        // // CountDownLatch cdl = new CountDownLatch(1);
        // // List<ExpectedPackage<String, Instant>> rejected = new ArrayList<>();
        // // TimeRange.addElements(timeRangeActor, elements).thenAccept(elm -> { rejected.addAll(elm); cdl.countDown(); });
        // // assertTrue(cdl.await(500, TimeUnit.MILLISECONDS), "TimeRange has to process addElements");
        // // assertEquals(elements, rejected, "Elements has to be rejected by addElements to expired range");
        // // testKit.stop(deadLetterSubscriber);
    }

    // @Test
    // void testTimeRangeStop() throws ExecutionException, InterruptedException, TimeoutException {
    //     ActorRef<Command<ExpectedPackage<String, Instant>>> timeRangeActor = testKit.spawn(TimeRange.processor(now, timeRangeConfig, elements -> {}, null),"testTimeRangeStop");
    //     CountDownLatch latch = new CountDownLatch(1);
    //     ActorRef<DeadLetter> deadLetterSubscriber = testKit.spawn(Behaviors.setup(context -> new DeadCommandTestActor<>(context, timeRangeActor, latch, "testTimeRangeStop-dead")));
    //     latch.await();
    //     ExpectedPackage<String, Instant> elmX = ExpectedPackage.pack("Z", now.minus(30, ChronoUnit.SECONDS));
    //     Collection<ExpectedPackage<String, Instant>> elements = List.of(elmX);
    //     CountDownLatch cdl = new CountDownLatch(1);
    //     List<ExpectedPackage<String, Instant>> rejected = new ArrayList<>();
    //     TimeRange.addElements(timeRangeActor, elements).thenAccept(elm -> { rejected.addAll(elm); cdl.countDown(); });
    //     assertTrue(cdl.await(500, TimeUnit.MILLISECONDS), "TimeRange has to process addElements");
    //     assertEquals(elements, rejected, "Elements has to be rejected by addElements to expired range");
    //     testKit.stop(deadLetterSubscriber);
    // }

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

    /*
    static class DeadCommandTestActor<X,M> extends AbstractBehavior<X> {
        public DeadCommandTestActor(ActorContext<X> context, ActorRef<Command<M>> spyActor, CountDownLatch latch, String deadActorName) {
            super(context);
            System.out.println("spyActor: "+spyActor.path().toStringWithoutAddress()+", "+deadActorName);
            TimeRange.subscribeToDeadCommands(context, spyActor, deadActorName).thenAccept(ignore -> Optional.ofNullable(latch).ifPresent(CountDownLatch::countDown));
        }
        @Override
        public Receive<X> createReceive() {
            return newReceiveBuilder()
                    .onAnyMessage(message -> this)
                    .build();
        }
    }

    static class WatchSupervisor<M> extends AbstractBehavior<M> {
        private final CompletableFuture<Void> terminationFuture;
        private final ActorRef<M> actorRef;
        public WatchSupervisor(ActorContext<M> actorContext,
                               Behavior<M> behavior,
                               CompletableFuture<ActorRef<M>> watchFuture,
                               CompletableFuture<Void> terminationFuture
                               ) {
            super(actorContext);
            this.terminationFuture = terminationFuture;
            this.actorRef = actorContext.spawn(behavior, "watch-child");
            TimeRange.subscribeToDeadCommands(getContext(), this.actorRef)

            actorContext.spawn(Behaviors.setup(
                    context -> new DeadCommandTestActor(context, this.actorRef, null, )
                    ), "dead-letter-watch-child");
            actorContext.watch(actorRef);
            watchFuture.complete(actorRef);
        }

        @Override
        public Receive<M> createReceive() {
            return newReceiveBuilder()
                    .onSignal(Terminated.class, this::onTerminated)
                    .build();
        }

        private akka.actor.typed.Behavior onTerminated(M ) {
            if (terminated.getRef().path().name().equals("watch-child") && !this.terminationFuture.isDone()) {
                this.terminationFuture.complete(null);
            }
            return this;
        }
    }
*/
}