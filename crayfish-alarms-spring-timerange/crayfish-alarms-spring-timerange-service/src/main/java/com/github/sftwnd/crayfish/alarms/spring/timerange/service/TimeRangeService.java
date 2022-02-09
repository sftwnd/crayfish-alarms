package com.github.sftwnd.crayfish.alarms.spring.timerange.service;

import akka.actor.DeadLetter;
import akka.actor.typed.ActorRef;
import akka.actor.typed.ActorSystem;
import akka.actor.typed.Behavior;
import akka.actor.typed.Terminated;
import akka.actor.typed.eventstream.EventStream;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import com.github.sftwnd.crayfish.alarms.akka.timerange.TimeRange;
import com.github.sftwnd.crayfish.alarms.akka.timerange.TimeRange.Command;
import com.github.sftwnd.crayfish.alarms.timerange.TimeRangeConfig;
import com.github.sftwnd.crayfish.alarms.timerange.TimeRangeHolder;
import com.github.sftwnd.crayfish.common.expectation.Expectation;
import com.typesafe.config.Config;
import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.Duration;
import java.time.temporal.TemporalAccessor;
import java.util.Collection;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Predicate;

import static java.util.Optional.ofNullable;

public interface TimeRangeService<M> extends AutoCloseable {

    /**
     * Send new elements to TimeService
     * @param elements elements collection
     * @return CompletionStage with the response (takes a list of unaccepted elements)
     */
    @Nonnull CompletionStage<Collection<M>> addElements(@Nonnull Collection<M> elements);

    /**
     * Getting the CompletionStage to be executed after the TimeRange Service is stopped
     * @return CompletionStage of the stop of Service processing
     */
    @Nonnull CompletionStage<Void> stopStage();

    /**
     * Getting the CompletionStage to be executed after the TimeRange Service and DeadLetter processing are stopped
     * @return CompletionStage of the stop of Service and DeadLetter processing
     */
    @Nonnull CompletionStage<Void> completionStage();

    /**
     * Stop the TimeRange processing
     * @return CompletionStage of the stop of Service processing
     */
    @Nonnull CompletionStage<Void> stop();

    /**
     * Stop the TimeRange Service and DeadLetter processing
     * @return CompletionStage of the stop of Service and DeadLetter processing
     */
    @Nonnull default CompletionStage<Void> complete() {
        stop();
        return completionStage();
    }

    /**
     * Complete TimeRange Service and DeadLetter processing and wait for completion
     * @throws ExecutionException in case of Execution Exception
     */
    @Override default void close() throws ExecutionException {
        try {
            complete().toCompletableFuture().get();
        } catch (InterruptedException interruptedException) {
            throw new ExecutionException("TimeRangeService.close() has been interrupted.", interruptedException);
        }
    }

    class ConfigDescription<M,R> {

        public static final Duration DEFAULT_DURATION = Duration.ofMinutes(1);
        public static final Duration DEFAULT_INTERVAL = Duration.ofSeconds(15);
        public static final Duration DEFAULT_DELAY = Duration.ofMillis(125);
        public static final Duration DEFAULT_COMPLETE_TIMEOUT = Duration.ofSeconds(12);

        private final ApplicationContext applicationContext;

        @Getter @Setter Duration duration;
        @Getter @Setter Duration interval;
        @Getter @Setter Duration delay;
        @Getter @Setter Duration completeTimeout;

        @Getter Expectation<M,? extends TemporalAccessor> expectation;
        @Getter Comparator<M> comparator;
        @Getter TimeRangeHolder.ResultTransformer<M,R> extractor;

        public ConfigDescription(@Autowired ApplicationContext applicationContext) { this.applicationContext = applicationContext; }

        public void setExpectation(@Nonnull Expectation<M,? extends TemporalAccessor> expectation) {
            this.expectation = Objects.requireNonNull(expectation, "ConfigDescription::setExpectation - expectation is null");
        }
        @SuppressWarnings("unchecked")
        public void setExpectation(@Nonnull String expectation) {
            setExpectation(applicationContext.getBean(expectation, Expectation.class));
        }
        public void setComparator(@Nonnull Comparator<M> comparator) {
            this.comparator = Objects.requireNonNull(comparator, "ConfigDescription::setComparator - comparator is null");
        }
        @SuppressWarnings("unchecked")
        public void setComparator(@Nonnull String extractor) {
            setComparator(applicationContext.getBean(extractor, Comparator.class));
        }
        public void setExtractor(@Nonnull TimeRangeHolder.ResultTransformer<M,R> extractor) {
            this.extractor = Objects.requireNonNull(extractor, "ConfigDescription::setComparator - extractor is null");
        }
        @SuppressWarnings("unchecked")
        public void setExtractor(@Nonnull String extractor) {
            setExtractor(applicationContext.getBean(extractor, TimeRangeHolder.ResultTransformer.class));
        }

        private TimeRangeConfig<M,R> timeRangeConfig() {
            Duration duration = ofNullable(this.getDuration()).orElse(DEFAULT_DURATION);
            Duration interval = ofNullable(this.getInterval()).orElse(DEFAULT_INTERVAL);
            Duration delay = ofNullable(this.getDelay()).orElse(DEFAULT_DELAY);
            Duration completeTimeout = ofNullable(this.getCompleteTimeout()).orElse(DEFAULT_COMPLETE_TIMEOUT);
            Expectation<M,? extends TemporalAccessor> expectation = Objects.requireNonNull(this.getExpectation(), "ConfigDescription::timeRangeConfig - expectation is null");
            Comparator<M> comparator = getComparator();
            TimeRangeHolder.ResultTransformer<M,R> extractor = Objects.requireNonNull(this.getExtractor(),  "ConfigDescription::timeRangeConfig - extractor is null");
            return TimeRangeConfig.create(duration, interval, delay, completeTimeout, expectation, comparator, extractor);
        }

    }

    class ServiceDescription<M,R> {

        public static final String TIME_RANGE_SERVICE_ACTOR_NAME = "time-range-service";
        public static final String DEAD_LETTER_ACTOR_NAME = "dead-letters";

        public static final int DEFAULT_TIME_RANGE_DEPTH = 2;
        public static final int DEFAULT_NR_OF_INSTANCES = 1;
        public static final Duration DEFAULT_WITH_CHECK_DURATION = Duration.ZERO;
        public static final Duration DEFAULT_DEAD_LETTER_TIMEOUT = Duration.ofSeconds(3);
        public static final Duration DEFAULT_DEAD_LETTER_COMPLETE_TIMEOUT = Duration.ofMillis(250);

        private final ApplicationContext applicationContext;

        @Getter @Setter ConfigDescription<M,R> configDescription;
        @Getter @Setter Integer timeRangeDepth;
        @Getter @Setter Integer nrOfInstances;
        @Getter @Setter Duration withCheckDuration;
        @Getter @Setter Duration deadLetterTimeout;
        @Getter @Setter Duration deadLetterCompleteTimeout;

        @Getter TimeRange.TimeRangeWakedUp regionListener;
        @Getter TimeRange.FiredElementsConsumer<R> alarmsConsumer;

        @Getter @Setter
        Config akkaConfig;

        public ServiceDescription(@Autowired ApplicationContext applicationContext) {
            this.applicationContext = applicationContext;
        }

        public void setRegionListener(@Nonnull TimeRange.TimeRangeWakedUp regionListener) {
            this.regionListener = Objects.requireNonNull(regionListener, "ConfigDescription::setRegionListener - regionListener is null");
        }

        public void setRegionListener(@Nonnull String regionListener) {
            setRegionListener(applicationContext.getBean(regionListener, TimeRange.TimeRangeWakedUp.class));
        }
        public void setAlarmsConsumer(@Nonnull TimeRange.FiredElementsConsumer<R> alarmsConsumer) {
            this.alarmsConsumer = Objects.requireNonNull(alarmsConsumer, "ConfigDescription::setAlarmsConsumer - alarmsConsumer is null");
        }
        @SuppressWarnings("unchecked")
        public void setAlarmsConsumer(@Nonnull String alarmsConsumer) {
            setAlarmsConsumer(applicationContext.getBean(alarmsConsumer, TimeRange.FiredElementsConsumer.class));
        }

        @Nonnull
        public TimeRangeService<M> timeRangeService(@Nonnull final String serviceName) {
            final Function<Behavior<TimeRangeServiceCommand>, ActorSystem<TimeRangeServiceCommand>> timeRangeServiceSpawn =
                    getAkkaConfig() == null ? behavior -> ActorSystem.create(behavior, serviceName) : behavior -> ActorSystem.create(behavior, serviceName, getAkkaConfig());
            CompletableFuture<Void> stopFuture = new CompletableFuture<>();
            CompletableFuture<Void> completeFuture = new CompletableFuture<>();
            CompletableFuture<Function<Collection<M>,CompletionStage<Collection<M>>>> addElementsFunctionFuture = new CompletableFuture<>();
            ActorSystem<TimeRangeServiceCommand> timeRangeService = timeRangeServiceSpawn.apply(timeRangeService(serviceName, addElementsFunctionFuture, stopFuture, completeFuture));
            return new TimeRangeService<>() {
                private final Function<Collection<M>,CompletionStage<Collection<M>>> addElementsFunction = addElementsFunctionFuture.join();
                @Override @Nonnull public CompletionStage<Collection<M>> addElements(@Nonnull Collection<M> elements) { return addElementsFunction.apply(elements); }
                @Override @Nonnull public CompletionStage<Void> stopStage() { return stopFuture; }
                @Override @Nonnull public CompletionStage<Void> completionStage() { return completeFuture; }
                @Override @Nonnull public CompletionStage<Void> stop() { timeRangeService.tell(TimeRangeServiceCommand.STOP_SERVICE); return stopStage(); }
            };
        }

        @Nonnull
        private Behavior<TimeRangeServiceCommand> timeRangeService(
                @Nonnull final String serviceName,
                @Nonnull final CompletableFuture<Function<Collection<M>,CompletionStage<Collection<M>>>> addElementsFunctionFuture,
                @Nullable final CompletableFuture<Void> stopFuture,
                @Nullable final CompletableFuture<Void> completableFuture
        ) {
            Objects.requireNonNull(serviceName, "ServiceDescription::timeRangeService - serviceName is null");
            Objects.requireNonNull(addElementsFunctionFuture, "ServiceDescription::addElementsFunctionConsumer - serviceName is null");
            final int timeRangeDepth = ofNullable(this.getTimeRangeDepth()).orElse(DEFAULT_TIME_RANGE_DEPTH);
            final int nrOfInstances = ofNullable(this.getNrOfInstances()).orElse(DEFAULT_NR_OF_INSTANCES);
            final Duration withCheckDuration = ofNullable(this.getWithCheckDuration()).orElse(DEFAULT_WITH_CHECK_DURATION);
            final TimeRange.TimeRangeWakedUp regionListener = Objects.requireNonNull(this.getRegionListener(), "ServiceDescription::timeRangeService - regionListener is null");
            final TimeRange.FiredElementsConsumer<R> alarmsConsumer = Objects.requireNonNull(this.getAlarmsConsumer(), "ServiceDescription::timeRangeService - alarmsConsumer is null");
            final TimeRangeConfig<M,R> timeRangeConfig = Objects.requireNonNull(this.configDescription, "ServiceDescription::timeRangeService - timeRangeConfig is null").timeRangeConfig();
            final Duration deadLetterTimeout = ofNullable(this.getDeadLetterTimeout()).filter(Predicate.not(Duration::isNegative)).orElse(DEFAULT_DEAD_LETTER_TIMEOUT);
            final Duration deadLetterCompleteTimeout = ofNullable(this.getDeadLetterCompleteTimeout()).filter(Predicate.not(Duration::isNegative)).orElse(DEFAULT_DEAD_LETTER_COMPLETE_TIMEOUT);
            return Behaviors.setup(context ->
                    new TimeRangeServiceBehavior<>(
                            context,
                            timeRangeConfig,
                            alarmsConsumer,
                            withCheckDuration,
                            timeRangeDepth,
                            nrOfInstances,
                            regionListener,
                            deadLetterTimeout,
                            deadLetterCompleteTimeout,
                            addElementsFunctionFuture,
                            stopFuture,
                            completableFuture
                    ));
        }

        private enum TimeRangeServiceCommand {
            STOP_SERVICE, SERVICE_STOPPED, GRACEFUL_STOP
        }

        private static class TimeRangeServiceBehavior<M,R> extends AbstractBehavior<TimeRangeServiceCommand> {

            private final AtomicBoolean active = new AtomicBoolean(true);
            private final ActorRef<Command<M>> timeRangeServiceActor;
            private final Duration deadLetterTimeout;
            private final Duration deadLetterCompleteTimeout;
            private final CompletableFuture<Void> stopFuture; // Service is stopped
            private final CompletableFuture<Void> completeFuture; // Service is completed

            private TimeRangeServiceBehavior(@Nonnull ActorContext<TimeRangeServiceCommand> actorContext,
                                             @Nonnull TimeRangeConfig<M, R> timeRangeConfig,
                                             @Nonnull TimeRange.FiredElementsConsumer<R> firedConsumer,
                                             @Nonnull Duration withCheckDuration,
                                             int rangeDepth,
                                             int nrOfInstances,
                                             @Nonnull TimeRange.TimeRangeWakedUp regionListener,
                                             @Nonnull Duration deadLetterTimeout,
                                             @Nonnull Duration deadLetterCompleteTimeout,
                                             @Nonnull CompletableFuture<Function<Collection<M>,CompletionStage<Collection<M>>>> addElementsFunctionFuture,
                                             @Nullable CompletableFuture<Void> stopFuture,
                                             @Nullable CompletableFuture<Void> completeFuture) {
                super(actorContext);
                this.deadLetterTimeout = deadLetterTimeout;
                this.deadLetterCompleteTimeout = deadLetterCompleteTimeout;
                // take TimeRange Service behavior
                Behavior<Command<M>> timeRangeServiceBehavior = Behaviors.setup(context -> TimeRange.service(
                        context,
                        timeRangeConfig,
                        firedConsumer,
                        withCheckDuration,
                        rangeDepth,
                        nrOfInstances,
                        regionListener));
                this.timeRangeServiceActor = actorContext.spawn(timeRangeServiceBehavior, TIME_RANGE_SERVICE_ACTOR_NAME);
                actorContext.watch(timeRangeServiceActor);
                this.stopFuture = ofNullable(stopFuture).orElseGet(CompletableFuture::new);
                this.completeFuture = ofNullable(completeFuture).orElseGet(CompletableFuture::new);
                addElementsFunctionFuture.complete(this::addElements);
            }

            private CompletionStage<Collection<M>> addElements(@Nonnull Collection<M> elements) {
                if (active.get()) {
                    return TimeRange.addElements(this.timeRangeServiceActor, elements);
                } else {
                    CompletableFuture<Collection<M>> rejectFuture = new CompletableFuture<>();
                    rejectFuture.complete(elements);
                    return rejectFuture;
                }
            }

            @Override
            public Receive<TimeRangeServiceCommand> createReceive() {
                return newReceiveBuilder()
                        .onSignal(Terminated.class, this::onTerminate)
                        .onMessageEquals(TimeRangeServiceCommand.STOP_SERVICE, this::onStopService)
                        .onMessageEquals(TimeRangeServiceCommand.SERVICE_STOPPED, this::onStopServiceActor)
                        .onMessageEquals(TimeRangeServiceCommand.GRACEFUL_STOP, this::onGracefulStop)
                        .build();
            }

            private Behavior<TimeRangeServiceCommand> onTerminate(Terminated terminated) {
                if (this.timeRangeServiceActor.equals(terminated.ref())) { // If timeRangeServiceActor has been broken
                    return onStopServiceActor();
                } else if (DEAD_LETTER_ACTOR_NAME.equals(terminated.ref().path().name())) { // If deadLetterActor has been stopped
                    if (!this.active.get()) {
                        getContext().getSelf().tell(TimeRangeServiceCommand.GRACEFUL_STOP);
                    }
                }
                return Behaviors.same();
            }

            private Behavior<TimeRangeServiceCommand> onStopService() {
                final ActorRef<TimeRangeServiceCommand> self = getContext().getSelf();
                self.tell(TimeRangeServiceCommand.SERVICE_STOPPED);
                TimeRange.stop(this.timeRangeServiceActor).thenAccept(ignore -> this.stopFuture.complete(null));
                return Behaviors.same();
            }

            private Behavior<TimeRangeServiceCommand> onStopServiceActor() {
                if (this.active.get()) {
                    Optional.of(stopFuture).filter(Predicate.not(CompletableFuture::isDone)).ifPresent(stopFuture -> stopFuture.complete(null));
                    // create dead-letter actor for timeRangeServiceActor
                    ActorRef<DeadLetter> deadLetterActor = getContext().spawn(
                            TimeRange.deadLetterSubscriber(this.timeRangeServiceActor, this.deadLetterTimeout, this.deadLetterCompleteTimeout),
                            DEAD_LETTER_ACTOR_NAME
                    );
                    getContext().watch(deadLetterActor); // GracefulStop on deadLetter death
                    getContext().getSystem().eventStream().tell(new EventStream.Subscribe<>(DeadLetter.class, deadLetterActor));
                    this.active.set(false);
                }
                return Behaviors.same();
            }

            private Behavior<TimeRangeServiceCommand> onGracefulStop() {
                getContext().stop(this.timeRangeServiceActor);
                return Behaviors.stopped(() -> this.completeFuture.complete(null));
            }

        }

    }

}
