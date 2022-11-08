package com.github.sftwnd.crayfish.alarms.spring.boot.timerange.starter;

import com.github.sftwnd.crayfish.alarms.akka.timerange.TimeRangeProcessor.FiredElementsConsumer;
import com.github.sftwnd.crayfish.alarms.akka.timerange.TimeRangeProcessor.TimeRangeWakedUp;
import com.github.sftwnd.crayfish.alarms.akka.timerange.service.AbstractTimeRangeServiceConfiguration;
import com.github.sftwnd.crayfish.alarms.akka.timerange.service.TimeRangeService;
import com.github.sftwnd.crayfish.alarms.timerange.TimeRangeHolder.ResultTransformer;
import com.github.sftwnd.crayfish.common.expectation.Expectation;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import lombok.Setter;
import lombok.SneakyThrows;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Comparator;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.Optional.of;
import static java.util.Optional.ofNullable;

@Configuration
@ConfigurationProperties(prefix = "crayfish.alarms.time-range-service")
@ConditionalOnMissingBean(value = TimeRangeServiceStarterConfiguration.class)
@ConditionalOnProperty(prefix = "crayfish.alarms.time-range-service", name = {"fired-consumer", "region-listener", "expectation", "extractor"})
public class TimeRangeServiceStarterConfiguration extends AbstractTimeRangeServiceConfiguration {

    public static final String DEFAULT_SERVICE_NAME = "time-range-service";

    public TimeRangeServiceStarterConfiguration(@Autowired ApplicationContext applicationContext) {
        super();
        this.beanFactory = applicationContext.getAutowireCapableBeanFactory();
    }

    @Setter private String serviceName;
    public @Nonnull String getServiceName() { return ofNullable(serviceName).filter(Predicate.not(String::isBlank)).orElse(DEFAULT_SERVICE_NAME); }

    @SuppressWarnings("unchecked")
    public void setComparator(@Nonnull String comparator) {
        if (cleaned(comparator, () -> this.setComparator((Comparator<?>)null))) return;
        @SuppressWarnings("rawtypes")
        Class<Comparator> comparatorClass = Comparator.class;
        setComparator(of(comparatorClass)
                .map(clazz -> wakeUp(comparator, comparatorClass))
                .orElseGet(() -> beanFactory.getBean(comparator, comparatorClass)));
    }

    @SuppressWarnings("unchecked")
    public void setExtractor(@Nonnull String extractor) {
        if (cleaned(extractor, () -> this.setExtractor((ResultTransformer<?,?> )null))) return;
        @SuppressWarnings("rawtypes")
        Class<ResultTransformer> extractorClass = ResultTransformer.class;
        setExtractor(of(extractorClass)
                .map(clazz -> wakeUp(extractor, extractorClass))
                .orElseGet(() -> beanFactory.getBean(extractor, extractorClass)));
    }

    @SuppressWarnings("unchecked")
    public void setFiredConsumer(@Nonnull String firedConsumer) {
        if (cleaned(firedConsumer, () -> this.setFiredConsumer((FiredElementsConsumer<?>)null))) return;
        @SuppressWarnings("rawtypes")
        Class<FiredElementsConsumer> firedConsumerClass = FiredElementsConsumer.class;
        setFiredConsumer(of(firedConsumerClass)
                .map(clazz -> wakeUp(firedConsumer, firedConsumerClass))
                .orElseGet(() -> beanFactory.getBean(firedConsumer, firedConsumerClass)));
    }

    @SuppressWarnings("unchecked")
    public void setExpectation(@Nonnull String expectation) {
        if (cleaned(expectation, () -> this.setExpectation((Expectation<?,?>)null))) return;
        @SuppressWarnings("rawtypes")
        Class<Expectation> expectationClass = Expectation.class;
        setExpectation(of(expectationClass)
                .map(clazz -> wakeUp(expectation, expectationClass))
                .orElseGet(() -> beanFactory.getBean(expectation, expectationClass)));
    }

    public void setRegionListener(@Nonnull String regionListener) {
        if (cleaned(regionListener, () -> setRegionListener((TimeRangeWakedUp)null))) return;
        setRegionListener(of(TimeRangeWakedUp.class)
                .map(clazz -> wakeUp(regionListener, clazz))
                .orElseGet(() -> beanFactory.getBean(regionListener, TimeRangeWakedUp.class)));
    }

    public void setAkkaConfig(@Nonnull String akkaConfig) {
        if (cleaned(akkaConfig, () -> setAkkaConfig((Config)null))) return;
        try {
            setAkkaConfig(beanFactory.getBean(akkaConfig, Config.class));
        } catch (BeansException ignored) {
            setAkkaConfig(ConfigFactory.load(akkaConfig));
        }
    }

    private boolean cleaned(@Nullable String value, @Nonnull Runnable cleaner) {
        return ofNullable(value)
                .filter(Predicate.not(String::isBlank))
                .map(String::isBlank)
                .orElseGet(() -> { cleaner.run(); return true; });
    }

    private final AutowireCapableBeanFactory beanFactory;
    private static final Pattern classPattern = Pattern.compile("(.+[^.])\\.class");
    @SuppressWarnings("unchecked")
    @SneakyThrows
    private @Nullable <X> X wakeUp(@Nonnull String className, @Nonnull Class<X> ignored) {
        Matcher matcher = classPattern.matcher(Objects.requireNonNull(className, "MethodsHelper::wakeUp - className is null"));
        return matcher.matches() ? beanFactory.createBean((Class<X>)Class.forName(matcher.group(1))) : null;
    }

    @Bean(destroyMethod = "close")
    public @Nullable <M> TimeRangeService<M> timeRangeService() {
        try {
            TimeRangeService.ServiceFactory<M, ?> serviceFactory = TimeRangeService.serviceFactory(this);
            return serviceFactory.timeRangeService(getServiceName());
        } catch (TimeRangeService.ConfigurationException ignored) {
            return null;
        }
    }

}