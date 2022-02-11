package com.github.sftwnd.crayfish.alarms.spring.boot.timerange.starter;

import com.github.sftwnd.crayfish.alarms.akka.timerange.TimeRange;
import com.github.sftwnd.crayfish.alarms.timerange.TimeRangeHolder;
import com.github.sftwnd.crayfish.common.expectation.Expectation;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.time.Instant;
import java.util.Comparator;
import java.util.Optional;

@SpringBootApplication(
        scanBasePackages = {"com.github.sftwnd.crayfish.alarms.spring.boot.timerange.starter"},
        scanBasePackageClasses = TimeRangeServiceStarterConfiguration.class
)
public class TimeRangeServiceConfigurationApplication {

    @Bean("test-regionListener")
    TimeRange.TimeRangeWakedUp regionListener() {
        return (start,end) -> {};
    }

    @Bean("test-expectation")
    Expectation<Instant, Instant> expectation() {
        return instant -> instant;
    }

    @Bean("test-firedElementsConsumer")
    TimeRange.FiredElementsConsumer<Instant> firedElementsConsumer() {
        return elements -> {};
    }

    @Bean("test-extractor")
    TimeRangeHolder.ResultTransformer<Instant,Instant> extractor() {
        return instant -> instant;
    }

    @Bean("test-comparator")
    Comparator<Instant> comparator() {
        return (left,right) -> Optional.ofNullable(left)
                .map(l -> l.compareTo(right))
                .orElseGet(() -> right == null ? 0 : -1 );
    }

    @Bean("test-akkaConfig")
    Config akkaConfig() {
        return ConfigFactory.load();
    }

}
