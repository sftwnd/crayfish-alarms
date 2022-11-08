package com.github.sftwnd.crayfish.alarms.spring.boot.timerange.starter;

import com.github.sftwnd.crayfish.alarms.akka.timerange.TimeRangeProcessor;
import com.github.sftwnd.crayfish.alarms.akka.timerange.service.TimeRangeService;
import com.github.sftwnd.crayfish.alarms.timerange.TimeRangeHolder;
import com.github.sftwnd.crayfish.common.expectation.Expectation;
import com.typesafe.config.Config;
import org.junit.jupiter.api.Test;
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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS;


@SpringBootTest(classes = {TimeRangeServiceConfigurationApplication.class})
@DirtiesContext(classMode = AFTER_CLASS)
class TimeRangeServiceStarterConfigurationPojoTest {

    private final TimeRangeServiceStarterConfiguration serviceConfig;
    private final TimeRangeProcessor.TimeRangeWakedUp regionListener;
    private final Expectation<Instant, Instant> expectation;
    private final TimeRangeProcessor.FiredElementsConsumer<Instant> firedElementsConsumer;
    private final TimeRangeHolder.ResultTransformer<Instant,Instant> extractor;
    private final Comparator<Instant> comparator;
    private final Config akkaConfig;

    public TimeRangeServiceStarterConfigurationPojoTest(
            @Autowired TimeRangeServiceStarterConfiguration serviceConfig,
            @Autowired @Qualifier("test-regionListener") TimeRangeProcessor.TimeRangeWakedUp regionListener,
            @Autowired @Qualifier("test-expectation") Expectation<Instant, Instant> expectation,
            @Autowired @Qualifier("test-firedElementsConsumer") TimeRangeProcessor.FiredElementsConsumer<Instant> firedElementsConsumer,
            @Autowired @Qualifier("test-extractor") TimeRangeHolder.ResultTransformer<Instant,Instant> extractor,
            @Autowired @Qualifier("test-comparator") Comparator<Instant> comparator,
            @Autowired @Qualifier("test-akkaConfig") Config akkaConfig
    ) {
        this.serviceConfig = serviceConfig;
        this.regionListener = regionListener;
        this.expectation = expectation;
        this.firedElementsConsumer = firedElementsConsumer;
        this.extractor = extractor;
        this.comparator = comparator;
        this.akkaConfig = akkaConfig;
    }

    @Value("${crayfish.alarms.time-range-service.service-name:#{null}}")
    String serviceName;
    @Test
    void serviceNameTest() {
        assertNotNull(serviceConfig.getServiceName(), "serviceConfig.getServiceName() has to be not null");
        assertEquals(serviceName, serviceConfig.getServiceName(), "Wrong serviceName value");
        serviceConfig.setServiceName(" ");
        assertEquals(TimeRangeServiceStarterConfiguration.DEFAULT_SERVICE_NAME, serviceConfig.getServiceName(), "setServiceName(blank) has to produce default serviceName value");
    }

    @Value("${crayfish.alarms.time-range-service.duration:#{null}}")
    Duration duration;
    @Test
    void durationTest() {
        assertNotNull(serviceConfig.getDuration(), "serviceConfig.getDuration() has to be not null");
        assertEquals(duration, serviceConfig.getDuration(), "Wrong duration value");
    }

    @Value("${crayfish.alarms.time-range-service.interval:#{null}}")
    Duration interval;
    @Test
    void intervalTest() {
        assertNotNull(serviceConfig.getInterval(), "serviceConfig.getInterval() has to be not null");
        assertEquals(interval, serviceConfig.getInterval(), "Wrong interval value");
    }

    @Value("${crayfish.alarms.time-range-service.delay:#{null}}")
    Duration delay;
    @Test
    void delayTest() {
        assertNotNull(serviceConfig.getDelay(), "serviceConfig.getDelay() has to be not null");
        assertEquals(delay, serviceConfig.getDelay(), "Wrong delay value");
    }

    @Value("${crayfish.alarms.time-range-service.complete-timeout:#{null}}")
    Duration completeTimeout;
    @Test
    void completeTimeoutTest() {
        assertNotNull(serviceConfig.getCompleteTimeout(), "serviceConfig.getCompleteTimeout() has to be not null");
        assertEquals(completeTimeout, serviceConfig.getCompleteTimeout(), "Wrong completeTimeout value");
    }

    @Value("${crayfish.alarms.time-range-service.with-check-duration:#{null}}")
    Duration withCheckDuration;
    @Test
    void withCheckDurationTest() {
        assertNotNull(serviceConfig.getWithCheckDuration(), "serviceConfig.getWithCheckDuration() has to be not null");
        assertEquals(withCheckDuration, serviceConfig.getWithCheckDuration(), "Wrong withCheckDuration value");
    }

    @Value("${crayfish.alarms.time-range-service.time-range-depth:#{null}}")
    Integer timeRangeDepth;
    @Test
    void timeRangeDepthTest() {
        assertNotNull(serviceConfig.getTimeRangeDepth(), "serviceConfig.getTimeRangeDepth() has to be not null");
        assertEquals(timeRangeDepth, serviceConfig.getTimeRangeDepth(), "Wrong timeRangeDepth value");
    }

    @Value("${crayfish.alarms.time-range-service.nr-of-instances:#{null}}")
    Integer nrOfInstances;
    @Test
    void nrOfInstancesTest() {
        assertNotNull(serviceConfig.getNrOfInstances(), "serviceConfig.getNrOfInstances() has to be not null");
        assertEquals(nrOfInstances, serviceConfig.getNrOfInstances(), "Wrong nrOfInstances value");
    }

    @Value("${crayfish.alarms.time-range-service.dead-letter-timeout:#{null}}")
    Duration deadLetterTimeout;
    @Test
    void deadLetterTimeoutTest() {
        assertNotNull(serviceConfig.getDeadLetterTimeout(), "serviceConfig.getDeadLetterTimeout() has to be not null");
        assertEquals(deadLetterTimeout, serviceConfig.getDeadLetterTimeout(), "Wrong deadLetterTimeout value");
    }

    @Value("${crayfish.alarms.time-range-service.dead-letter-complete-timeout:#{null}}")
    Duration deadLetterCompleteTimeout;
    @Test
    void deadLetterCompleteTimeoutTest() {
        assertNotNull(serviceConfig.getDeadLetterCompleteTimeout(), "serviceConfig.getDeadLetterCompleteTimeout() has to be not null");
        assertEquals(deadLetterCompleteTimeout, serviceConfig.getDeadLetterCompleteTimeout(), "Wrong deadLetterCompleteTimeout value");
    }

    @Test
    void expectationTest() {
        assertSame(expectation, serviceConfig.getExpectation(), "Wrong expectation value");
        assertDoesNotThrow(() -> serviceConfig.setExpectation(" "), "setExpectation(blank) hasn't got to raise exception");
        assertNull(serviceConfig.getExpectation(), "setExpectation(blank) has got to make null result value");
    }

    @Test
    void comparatorTest() {
        assertSame(comparator, serviceConfig.getComparator(), "Wrong comparator value");
        assertDoesNotThrow(() -> serviceConfig.setComparator(" "), "setComparator(blank) hasn't got to raise exception");
        assertNull(serviceConfig.getComparator(), "setComparator(blank) has got to make null result value");
    }

    @Test
    void extractorTest() {
        assertSame(extractor, serviceConfig.getExtractor(), "Wrong extractor value");
        assertDoesNotThrow(() -> serviceConfig.setExtractor(" "), "setExtractor(blank) hasn't got to raise exception");
        assertNull(serviceConfig.getExtractor(), "setExtractor(blank) has got to make null result value");
    }

    @Test
    void firedConsumerTest() {
        assertSame(firedElementsConsumer, serviceConfig.getFiredConsumer(), "Wrong firedElementsConsumer value");
        assertDoesNotThrow(() -> serviceConfig.setFiredConsumer(" "), "setFiredConsumer(blank) hasn't got to raise exception");
        assertNull(serviceConfig.getFiredConsumer(), "setFiredConsumer(blank) has got to make null result value");
    }

    @Test
    void regionListenerTest() {
        assertSame(regionListener, serviceConfig.getRegionListener(), "Wrong regionListener value");
        assertDoesNotThrow(() -> serviceConfig.setRegionListener(" "), "setRegionListener(blank) hasn't got to raise exception");
        assertNull(serviceConfig.getRegionListener(), "setRegionListener(blank) has got to make null result value");
    }

    @Test
    void akkaConfigTest() {
        Config config = serviceConfig.getAkkaConfig();
        assertNotNull(config, "akkaConfig has to be loaded");
        assertDoesNotThrow(() -> config.getValue("akka.event-handlers"), "akkaConfig.getValue('akka.event-handlers') hasn't got to raise exception");
        assertEquals(List.of("akka.event.slf4j.Slf4jEventHandler"), config.getValue("akka.event-handlers").unwrapped(), "wrong value for 'akka.event-handlers' in config");
        assertDoesNotThrow(() -> serviceConfig.setAkkaConfig("test-akkaConfig"), "akkaConfig.setValue(bean) hasn't got to raise exception");
        assertNotNull(config, "akkaConfig.setValue(bean) has to save not null value");
        assertSame(this.akkaConfig, serviceConfig.getAkkaConfig(), "Wrong akkaConfig value");
    }

    @Test
    void isCompleteTest() {
        serviceConfig.setFiredConsumer("test-firedElementsConsumer");
        serviceConfig.setRegionListener("test-regionListener");
        serviceConfig.setExpectation("test-expectation");
        serviceConfig.setExtractor("test-extractor");
        assertTrue(serviceConfig.isCompleted(), "serviceConfig.isCompleted() has to be true with firedConsumer, regionListener, expectation and extractor is defined");
        // FiredConsumer check
        try {
            serviceConfig.setFiredConsumer("");
            assertFalse(serviceConfig.isCompleted(), "serviceConfig.isCompleted() has to be false with firedConsumer is not defined");
        } finally {
            serviceConfig.setFiredConsumer("test-firedElementsConsumer");
        }
        // RegionListener check
        try {
            serviceConfig.setRegionListener("");
            assertFalse(serviceConfig.isCompleted(), "serviceConfig.isCompleted() has to be false with regionListener is not defined");
        } finally {
            serviceConfig.setRegionListener("test-regionListener");
        }
        // Expectation check
        try {
            serviceConfig.setExpectation("");
            assertFalse(serviceConfig.isCompleted(), "serviceConfig.isCompleted() has to be false with expectation is not defined");
        } finally {
            serviceConfig.setExpectation("test-expectation");
        }
        // Extractor check
        try {
            serviceConfig.setExtractor("");
            assertFalse(serviceConfig.isCompleted(), "serviceConfig.isCompleted() has to be false with extractor is not defined");
        } finally {
            serviceConfig.setExtractor("test-extractor");
        }
    }

    @Test
    void methodCreationByClassTest() {
        try {
            serviceConfig.setExpectation(TimeRangeServiceConfigurationTestExpectation.class.getCanonicalName()+".class");
            var expectationClass = Optional.ofNullable(serviceConfig.getExpectation()).map(Object::getClass).orElse(null);
            assertEquals(TimeRangeServiceConfigurationTestExpectation.class, expectationClass, "Wrong regionListener result class");
        } finally {
            serviceConfig.setExpectation("test-expectation");
        }
    }

    @Test
    void throwsOnIncompleteTest() {
        try {
            this.serviceConfig.setExtractor("");
            assertThrows(TimeRangeService.ConfigurationException.class,
                    () -> TimeRangeService.serviceFactory(serviceConfig),
                    "serviceConfig::timeRangeConfig has to raise TimeRangeService.ConfigurationException on incomplete config"
                    );
        } finally {
            serviceConfig.setExtractor("test-extractor");
        }
    }

}