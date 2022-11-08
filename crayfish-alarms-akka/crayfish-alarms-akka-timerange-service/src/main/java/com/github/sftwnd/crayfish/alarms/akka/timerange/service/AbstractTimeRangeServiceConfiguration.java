package com.github.sftwnd.crayfish.alarms.akka.timerange.service;

import com.github.sftwnd.crayfish.alarms.akka.timerange.TimeRangeProcessor.FiredElementsConsumer;
import com.github.sftwnd.crayfish.alarms.akka.timerange.TimeRangeProcessor.TimeRangeWakedUp;
import com.github.sftwnd.crayfish.alarms.timerange.TimeRangeHolder.ResultTransformer;
import com.github.sftwnd.crayfish.common.expectation.Expectation;
import com.typesafe.config.Config;
import lombok.Getter;
import lombok.Setter;

import javax.annotation.Nonnull;
import java.time.Duration;
import java.time.temporal.TemporalAccessor;
import java.util.Comparator;

import static java.util.Optional.ofNullable;

public abstract class AbstractTimeRangeServiceConfiguration implements TimeRangeService.Configuration {

    public static final Duration DEFAULT_DURATION = Duration.ofMinutes(1);
    public static final Duration DEFAULT_INTERVAL = Duration.ofSeconds(15);
    public static final Duration DEFAULT_DELAY = Duration.ofMillis(125);
    public static final Duration DEFAULT_COMPLETE_TIMEOUT = Duration.ofSeconds(12);

    @Setter private Duration duration;
    @Setter private Duration interval;
    @Setter private Duration delay;
    @Setter private Duration completeTimeout;
    @Getter @Setter private Duration withCheckDuration;
    @Getter @Setter private Integer timeRangeDepth;
    @Getter @Setter private Integer nrOfInstances;
    @Getter @Setter private Duration deadLetterTimeout;
    @Getter @Setter private Duration deadLetterCompleteTimeout;
    private Expectation<?,?> expectation;
    private Comparator<?> comparator;
    private ResultTransformer<?,?> extractor;
    private FiredElementsConsumer<?> firedConsumer;
    @Getter private TimeRangeWakedUp regionListener;
    @Getter private Config akkaConfig;

    @Override public @Nonnull Duration getDuration() { return ofNullable(duration).orElse(DEFAULT_DURATION); }
    @Override public @Nonnull Duration getInterval() { return ofNullable(interval).orElse(DEFAULT_INTERVAL); }
    @Override public @Nonnull Duration getDelay() { return ofNullable(delay).orElse(DEFAULT_DELAY); }
    @Override public @Nonnull Duration getCompleteTimeout() { return ofNullable(completeTimeout).orElse(DEFAULT_COMPLETE_TIMEOUT); }

    @Override public @SuppressWarnings("unchecked") <M,T extends TemporalAccessor> Expectation<M,T> getExpectation() { return (Expectation<M,T>) expectation; }
    @Override public @SuppressWarnings("unchecked") <M> Comparator<M> getComparator() { return (Comparator<M>) comparator; }
    @Override public @SuppressWarnings("unchecked") <M,R> ResultTransformer<M,R> getExtractor() { return (ResultTransformer<M,R>) extractor; }
    @Override public @SuppressWarnings("unchecked") <R> FiredElementsConsumer<R> getFiredConsumer() { return (FiredElementsConsumer<R>) firedConsumer; }

    protected <M> void setComparator(Comparator<M> comparator) { this.comparator = comparator; }
    protected <M,R> void setExtractor(ResultTransformer<M,R> extractor) { this.extractor = extractor; }
    protected <R> void setFiredConsumer(FiredElementsConsumer<R> firedConsumer) { this.firedConsumer = firedConsumer; }
    protected <M,T extends TemporalAccessor> void setExpectation(Expectation<M,T>  expectation) { this.expectation = expectation; }
    protected void setRegionListener(TimeRangeWakedUp regionListener) { this.regionListener = regionListener; }
    protected void setAkkaConfig(Config akkaConfig) { this.akkaConfig = akkaConfig; }

}