package com.github.sftwnd.crayfish.alarms.akka.timerange.service;

import com.github.sftwnd.crayfish.alarms.akka.timerange.TimeRange;
import com.github.sftwnd.crayfish.alarms.timerange.TimeRangeHolder;
import com.github.sftwnd.crayfish.common.expectation.Expectation;
import com.typesafe.config.Config;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimeRangeServiceTest {

    private List<Instant> firedElements;
    private CountDownLatch firedElementsLatch;
    private CountDownLatch regionListenerLatch;
    private TimeRangeService.ServiceFactory<Instant,Instant> timeRangeServiceFactory;
    private TimeRangeService<Instant> timeRangeService;

    @Test
    void timeRangeServiceTest() throws InterruptedException, ExecutionException, TimeoutException {
        assertNotNull(timeRangeServiceFactory, "TimeRangeServiceFactory has to be created");
        assertDoesNotThrow(() -> { this.timeRangeService = timeRangeServiceFactory.timeRangeService(); }, "TimeRangeServiceFactory::timeRangeService has to be processed without throws");
        assertNotNull(timeRangeService, "TimeRangeService has to be created");
        assertTrue(regionListenerLatch.await(750, TimeUnit.SECONDS), "regionListener has to be called at least 4 times");
        Instant element = Instant.now().plusMillis(50);
        Instant reject = Instant.now().plusSeconds(3600*24);
        CompletionStage<Collection<Instant>> completionStage = timeRangeService.addElements(List.of(element, reject));
        assertEquals(Set.of(reject), completionStage.toCompletableFuture().get(500, TimeUnit.MILLISECONDS), "One element has to be rejected");
        assertTrue(firedElementsLatch.await (500, TimeUnit.MILLISECONDS), "Fired elements consumer has to be called");
        assertEquals(List.of(element), firedElements, "One element has to be fired");
        timeRangeService.complete();
        CountDownLatch stopServiceCdl = new CountDownLatch(1);
        CountDownLatch completeServiceCdl = new CountDownLatch(1);
        timeRangeService.stopStage().thenAccept(ignored -> stopServiceCdl.countDown());
        timeRangeService.completionStage().thenAccept(ignored -> completeServiceCdl.countDown());
        assertTrue(stopServiceCdl.await(1, TimeUnit.SECONDS), "Service actor has to be stopped after stop() call");
        assertTrue(completeServiceCdl.await(2, TimeUnit.SECONDS), "Service actor has to be completed after stop() call");
    }

    @BeforeEach
    void startUp() {
        this.firedElements = new ArrayList<>();
        this.firedElementsLatch = new CountDownLatch(1);
        this.regionListenerLatch = new CountDownLatch(4);
        Expectation<Instant,Instant> expectation = instant -> instant;
        TimeRangeHolder.ResultTransformer<Instant, Instant> extractor = instant -> instant;
        TimeRange.FiredElementsConsumer<Instant> firedElementsConsumer = elements -> { firedElements.addAll(elements); firedElementsLatch.countDown(); };
        TimeRange.TimeRangeWakedUp regionListener = (start, end) -> regionListenerLatch.countDown();
        TimeRangeService.Configuration configuration = config(expectation, extractor, firedElementsConsumer, regionListener);
        this.timeRangeServiceFactory = TimeRangeService.serviceFactory(configuration);
    }

    @AfterEach
    void tearDown() {
        if (timeRangeService != null) {
            timeRangeService.close();
        }
        this.timeRangeServiceFactory = null;
        this.firedElements = null;
        this.firedElementsLatch = null;
        this.regionListenerLatch = null;
    }

    @SuppressWarnings("unchecked")
    <M, R, T extends TemporalAccessor> TimeRangeService.Configuration config(
            @Nonnull Expectation<Instant,Instant> expectation,
            @Nonnull TimeRangeHolder.ResultTransformer<M, R> extractor,
            @Nonnull TimeRange.FiredElementsConsumer<R> firedElementsConsumer,
            @Nonnull TimeRange.TimeRangeWakedUp regionListener
            ) {
        return new TimeRangeService.Configuration() {
            @Nonnull @Override public String getServiceName() { return "test-TimeRangeServiceTest"; }
            @Nonnull @Override public Duration getDuration() { return Duration.ofSeconds(30); }
            @Nonnull @Override public Duration getInterval() { return Duration.ofSeconds(1); }
            @Nonnull @Override public Duration getDelay() { return Duration.ofMillis(125); }
            @Nonnull @Override public Duration getCompleteTimeout() { return Duration.ofSeconds(3); }
            @Override public Duration getWithCheckDuration() { return Duration.ofSeconds(0); }
            @Override public Integer getTimeRangeDepth() { return 3; }
            @Override public Integer getNrOfInstances() { return 1; }
            @Override public Duration getDeadLetterTimeout() { return Duration.ofSeconds(1); }
            @Override public Duration getDeadLetterCompleteTimeout() { return Duration.ofSeconds(1); }
            @Override public Config getAkkaConfig() { return null; }
            @Override public TimeRange.TimeRangeWakedUp getRegionListener() { return regionListener; }
            @Override public Comparator<M> getComparator() { return null; }
            @Override public Expectation<M, T> getExpectation() { return (Expectation<M, T>)expectation; }
            @Override public TimeRangeHolder.ResultTransformer<M, R> getExtractor() { return extractor; }
            @Override public TimeRange.FiredElementsConsumer<R> getFiredConsumer() { return firedElementsConsumer; }
        };
    }

}
