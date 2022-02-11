package com.github.sftwnd.crayfish.alarms.timerange.spring.boot.starter;

import com.github.sftwnd.crayfish.alarms.akka.timerange.TimeRange;
import com.github.sftwnd.crayfish.alarms.akka.timerange.service.TimeRangeService;
import com.github.sftwnd.crayfish.alarms.timerange.TimeRangeHolder;
import com.github.sftwnd.crayfish.common.expectation.Expectation;
import com.typesafe.config.Config;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static java.util.Optional.ofNullable;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;
import static org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS;


@SpringBootTest(classes = {TimeRangeServiceConfigurationApplication.class})
@DirtiesContext(classMode = AFTER_CLASS)
class TimeRangeServiceConfigurationTest {

    private final TimeRangeServiceConfiguration serviceConfig;

    public TimeRangeServiceConfigurationTest(
            @Autowired TimeRangeServiceConfiguration serviceConfig
    ) {
        this.serviceConfig = serviceConfig;
    }

    @Test
    void timeRangeServiceOnIncompleteTest() {
        TimeRangeServiceConfiguration incompleteConfig = spy(this.serviceConfig);
        when(incompleteConfig.isCompleted()).thenReturn(false);
        try(var timeRangeService = serviceConfig.timeRangeService()) {
            assertNotNull(timeRangeService, "TimeRangeService hasn't got to be created on incomplete config");
        }
    }

    @Test
    void timeRangeServiceTest() {
        try(var timeRangeService = serviceConfig.timeRangeService()) {
            assertNotNull(timeRangeService, "TimeRangeService has to be created");
        }
    }

}