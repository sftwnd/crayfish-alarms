package com.github.sftwnd.crayfish.alarms.timerange.spring.boot.starter;

import com.github.sftwnd.crayfish.common.expectation.Expectation;

import javax.annotation.Nonnull;
import java.time.Instant;

public class TimeRangeServiceConfigurationTestExpectation implements Expectation<Instant,Instant> {
    @Nonnull
    @Override public Instant apply(@Nonnull Instant element) {
        return element;
    }
}
