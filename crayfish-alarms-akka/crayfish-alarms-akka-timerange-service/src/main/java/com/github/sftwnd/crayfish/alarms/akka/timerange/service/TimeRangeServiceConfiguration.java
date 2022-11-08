package com.github.sftwnd.crayfish.alarms.akka.timerange.service;

import com.github.sftwnd.crayfish.alarms.akka.timerange.TimeRangeProcessor.FiredElementsConsumer;
import com.github.sftwnd.crayfish.alarms.akka.timerange.TimeRangeProcessor.TimeRangeWakedUp;
import com.github.sftwnd.crayfish.alarms.timerange.TimeRangeHolder.ResultTransformer;
import com.github.sftwnd.crayfish.common.expectation.Expectation;
import com.typesafe.config.Config;

import java.time.temporal.TemporalAccessor;
import java.util.Comparator;

public class TimeRangeServiceConfiguration extends AbstractTimeRangeServiceConfiguration {

    @Override public <M> void setComparator(Comparator<M> comparator) { super.setComparator(comparator); }
    @Override public <M,R> void setExtractor(ResultTransformer<M,R> extractor) { super.setExtractor(extractor); }
    @Override public <R> void setFiredConsumer(FiredElementsConsumer<R> firedConsumer) { super.setFiredConsumer(firedConsumer); }
    @Override public <M,T extends TemporalAccessor> void setExpectation(Expectation<M,T>  expectation) { super.setExpectation(expectation); }
    @Override public void setRegionListener(TimeRangeWakedUp regionListener) { super.setRegionListener(regionListener); }
    @Override public void setAkkaConfig(Config akkaConfig) { super.setAkkaConfig(akkaConfig); }

}