package com.github.sftwnd.crayfish.alarms.timerange.spring.boot.starter;

import com.github.sftwnd.crayfish.alarms.akka.timerange.TimeRange.FiredElementsConsumer;
import com.github.sftwnd.crayfish.alarms.akka.timerange.TimeRange.TimeRangeWakedUp;
import com.github.sftwnd.crayfish.alarms.akka.timerange.service.TimeRangeService;
import com.github.sftwnd.crayfish.alarms.timerange.TimeRangeHolder.ResultTransformer;
import com.github.sftwnd.crayfish.common.expectation.Expectation;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.Duration;
import java.time.temporal.TemporalAccessor;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Configuration("timeRangeServiceConfiguration")
@ConfigurationProperties(prefix = "crayfish.alarms.time-range-service")
@ConfigurationPropertiesScan
@Slf4j
public class TimeRangeServiceConfiguration implements TimeRangeService.Configuration {

    public static final String DEFAULT_SERVICE_NAME = "time-range-service";
    public static final Duration DEFAULT_DURATION = Duration.ofMinutes(1);
    public static final Duration DEFAULT_INTERVAL = Duration.ofSeconds(15);
    public static final Duration DEFAULT_DELAY = Duration.ofMillis(125);
    public static final Duration DEFAULT_COMPLETE_TIMEOUT = Duration.ofSeconds(12);

    public TimeRangeServiceConfiguration(@Autowired ApplicationContext applicationContext) {
        this.beanFactory = applicationContext.getAutowireCapableBeanFactory();
    }

    @Setter String serviceName;
    @Setter Duration duration;
    @Setter Duration interval;
    @Setter Duration delay;
    @Setter Duration completeTimeout;
    @Getter @Setter Duration withCheckDuration;
    @Getter @Setter Integer timeRangeDepth;
    @Getter @Setter Integer nrOfInstances;
    @Getter @Setter Duration deadLetterTimeout;
    @Getter @Setter Duration deadLetterCompleteTimeout;
    private Expectation<?,?> expectation;
    private Comparator<?> comparator;
    private ResultTransformer<?,?> extractor;
    private FiredElementsConsumer<?> firedConsumer;
    @Getter private TimeRangeWakedUp regionListener;
    @Getter private Config akkaConfig;

    public @Nonnull String getServiceName() { return Optional.ofNullable(serviceName).orElse(DEFAULT_SERVICE_NAME); }
    public @Nonnull Duration getDuration() { return Optional.ofNullable(duration).orElse(DEFAULT_DURATION); }
    public @Nonnull Duration getInterval() { return Optional.ofNullable(interval).orElse(DEFAULT_INTERVAL); }
    public @Nonnull Duration getDelay() { return Optional.ofNullable(delay).orElse(DEFAULT_DELAY); }
    public @Nonnull Duration getCompleteTimeout() { return Optional.ofNullable(completeTimeout).orElse(DEFAULT_COMPLETE_TIMEOUT); }

    public @SuppressWarnings("unchecked") <M,T extends TemporalAccessor> Expectation<M,T> getExpectation() { return (Expectation<M,T>) expectation; }
    public @SuppressWarnings("unchecked") <M> Comparator<M> getComparator() { return (Comparator<M>) comparator; }
    public @SuppressWarnings("unchecked") <M,R> ResultTransformer<M,R> getExtractor() { return (ResultTransformer<M,R>) extractor; }
    public @SuppressWarnings("unchecked") <R> FiredElementsConsumer<R> getFiredConsumer() { return (FiredElementsConsumer<R>) firedConsumer; }
    public void setComparator(@Nonnull String comparator) {
        if (clean(comparator, () -> this.comparator = null)) return;
        @SuppressWarnings("rawtypes")
        Class<Comparator> comparatorClass = Comparator.class;
        this.comparator = Optional.of(comparatorClass)
                .map(clazz -> wakeUp(comparator, comparatorClass))
                .orElseGet(() -> beanFactory.getBean(comparator, comparatorClass));
    }
    public void setExtractor(@Nonnull String extractor) {
        if (clean(extractor, () -> this.extractor = null)) return;
        @SuppressWarnings("rawtypes")
        Class<ResultTransformer> extractorClass = ResultTransformer.class;
        this.extractor = Optional.of(extractorClass)
                .map(clazz -> wakeUp(extractor, extractorClass))
                .orElseGet(() -> beanFactory.getBean(extractor, extractorClass));
    }
    public void setFiredConsumer(@Nonnull String firedConsumer) {
        if (clean(firedConsumer, () -> this.firedConsumer = null)) return;
        @SuppressWarnings("rawtypes")
        Class<FiredElementsConsumer> firedConsumerClass = FiredElementsConsumer.class;
        this.firedConsumer = Optional.of(firedConsumerClass)
                .map(clazz -> wakeUp(firedConsumer, firedConsumerClass))
                .orElseGet(() -> beanFactory.getBean(firedConsumer, firedConsumerClass));
    }
    public void setExpectation(@Nonnull String expectation) {
        if (clean(expectation, () -> this.expectation = null)) return;
        @SuppressWarnings("rawtypes")
        Class<Expectation> expectationClass = Expectation.class;
        this.expectation = Optional.of(expectationClass)
                .map(clazz -> wakeUp(expectation, expectationClass))
                .orElseGet(() -> beanFactory.getBean(expectation, expectationClass));
    }
    public void setRegionListener(@Nonnull String regionListener) {
        if (clean(regionListener, () -> this.regionListener = null)) return;
        this.regionListener = Optional.of(TimeRangeWakedUp.class)
                .map(clazz -> wakeUp(regionListener, clazz))
                .orElseGet(() -> beanFactory.getBean(regionListener, TimeRangeWakedUp.class));
    }
    public void setAkkaConfig(@Nonnull String akkaConfig) {
        if (clean(akkaConfig, () -> this.akkaConfig = null)) return;
        Matcher matcher = configPattern.matcher(akkaConfig);
        if (matcher.matches()) {
            this.akkaConfig = ConfigFactory.load(akkaConfig);
        } else {
            this.akkaConfig = Optional.of(Config.class)
                    .map(clazz -> wakeUp(akkaConfig, clazz))
                    .orElseGet(() -> beanFactory.getBean(akkaConfig, Config.class));
        }
    }
    private boolean clean(@Nullable String value, @Nonnull Runnable cleaner) {
        return Optional.ofNullable(value).filter(String::isBlank).map(ignored -> { cleaner.run(); return true; }).orElse(false);
    }
    private final AutowireCapableBeanFactory beanFactory;
    private static final Pattern classPattern = Pattern.compile("(.+^\\.)\\.class");
    private static final Pattern configPattern = Pattern.compile("(.+^\\.)\\.conf");
    @SuppressWarnings("unchecked")
    @SneakyThrows
    private @Nullable <X> X wakeUp(@Nonnull String className, @Nonnull Class<X> ignored) {
        Matcher matcher = classPattern.matcher(Objects.requireNonNull(className, "MethodsHelper::wakeUp - className is null"));
        return matcher.matches() ? beanFactory.createBean((Class<X>)Class.forName(matcher.group(1))) : null;
    }

    @Bean(destroyMethod = "close")
    public @Nullable <M> TimeRangeService<M> timeRangeService() {
        if (isCompleted()) {
            TimeRangeService.ServiceFactory<M,?> serviceFactory = TimeRangeService.serviceFactory(this);
            return serviceFactory.timeRangeService();
        }
        return null;
    }

}