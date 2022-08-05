package com.github.sftwnd.crayfish.alarms.spring.boot.timerange.starter;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;
import static org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS;


@SpringBootTest(classes = {TimeRangeServiceConfigurationApplication.class})
@DirtiesContext(classMode = AFTER_CLASS)
class TimeRangeServiceStarterConfigurationTest {

    private final TimeRangeServiceStarterConfiguration serviceConfig;

    public TimeRangeServiceStarterConfigurationTest(
            @Autowired TimeRangeServiceStarterConfiguration serviceConfig
    ) {
        this.serviceConfig = serviceConfig;
    }

    @Test
    void timeRangeServiceOnIncompleteTest() {
        TimeRangeServiceStarterConfiguration incompleteConfig = spy(this.serviceConfig);
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