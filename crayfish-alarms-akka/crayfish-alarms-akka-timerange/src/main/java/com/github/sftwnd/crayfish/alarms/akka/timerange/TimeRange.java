/*
 * Copyright © 2017-2022 Andrey D. Shindarev. All rights reserved.
 * This program is made available under the terms of the BSD 3-Clause License.
 * Contacts: ashindarev@gmail.com
 */
package com.github.sftwnd.crayfish.alarms.akka.timerange;

import akka.actor.ActorPath;
import akka.actor.ActorSystem;
import akka.actor.DeadLetter;
import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.Signal;
import akka.actor.typed.Terminated;
import akka.actor.typed.eventstream.EventStream;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import akka.actor.typed.javadsl.TimerScheduler;
import akka.dispatch.PriorityGenerator;
import akka.dispatch.UnboundedStablePriorityMailbox;
import com.github.sftwnd.crayfish.alarms.timerange.TimeRangeConfig;
import com.github.sftwnd.crayfish.alarms.timerange.TimeRangeHolder;
import com.typesafe.config.Config;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.TemporalAccessor;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static java.util.Optional.of;
import static java.util.Optional.ofNullable;

/**
 * Typed Akka Actor protocol interface for TimeRange for registering alarms and processing them
 */
public interface TimeRange {

    /*
        Used Sonar warnings:
            java:S116 Field names should comply with a naming convention
            java:S127 "for" loop stop conditions should be invariant
            java:S1170 Public constants and fields initialized at declaration should be "static final" rather than merely "final"
            java:S1172 Unused method parameters should be removed
            java:S1905 Redundant casts should not be used
            java:S2326 Unused type parameters should be removed
     */

    /**
     * Basic interface for describing the type of incoming messages
     * @param <X> generic to describe the content of the command
     */
    @SuppressWarnings("java:S2326")
    interface Command<X> { default void unhandled(){} }

    final class Commands<M> {
        // This is the shortest description of the cast, for this reason it was chosen
        @SuppressWarnings({"unchecked", "rawtypes", "java:S116", "java:S1170"})
        private final Class<Command<M>> COMMAND = (Class<Command<M>>)((Class<? extends Command>) Command.class);
        @SuppressWarnings({"unchecked", "rawtypes", "java:S116", "java:S1170"})
        private final Class<AddCommand<M>> ADD_COMMAND = (Class<AddCommand<M>>)((Class<? extends AddCommand>) AddCommand.class);
        @SuppressWarnings({"unchecked", "rawtypes", "java:S116", "java:S1170"})
        private final Class<GracefulStop<M>> GRACEFUL_STOP = (Class<GracefulStop<M>>)((Class<? extends GracefulStop>) GracefulStop.class);
        @SuppressWarnings("java:S116")
        private final Timeout<M> TIMEOUT = new Timeout<>() {};
        private Commands() {}
        static <X> CompletableFuture<X> completableFuture(Consumer<CompletableFuture<X>> consumer) {
            CompletableFuture<X> completableFuture = new CompletableFuture<>();
            consumer.accept(completableFuture);
            return completableFuture;
        }
    }

    interface FiredElementsConsumer<M> extends Consumer<Collection<M>> {
        @Override void accept(@Nonnull Collection<M> t);
    }

    /**
     * A processor that contains a TimeRangeHolder structure with alarms. Engaged in saving incoming alarms and
     * initializing the reaction to their operation.
     * @param <M> Type of incoming message for alarm registration
     * @param <R> The type of the object to be processed after the alarm goes off
     */
    class RangeProcessor<M,R> {

        private final Commands<M> commands = new Commands<>();
        private final TimerScheduler<Command<M>> timers;
        private final TimeRangeHolder<M,R> timeRange;
        private final FiredElementsConsumer<R> firedConsumer;
        private final Duration checkDelay;

        private RangeProcessor(
                @Nonnull  TimerScheduler<Command<M>> timers,
                @Nonnull  TemporalAccessor time,
                @Nonnull  TimeRangeConfig<M,R> timeRangeConfig,
                @Nonnull  FiredElementsConsumer<R> firedConsumer,
                // Within the TimeRangeHolder::interval, you can set the value of how soon to consider the entry triggered...
                @Nullable Duration withCheckDelay
        ) {
            this.timers = Objects.requireNonNull(timers, "RangeProcessor::new - timers is null");
            this.timeRange = Objects.requireNonNull(timeRangeConfig, "RangeProcessor::new - timeRangeConfig is null")
                    .timeRangeHolder(Objects.requireNonNull(time, "RangeProcessor::new - time is null"));
            this.firedConsumer = Objects.requireNonNull(firedConsumer, "RangeProcessor::new - firedConsumer is null");
            this.checkDelay = ofNullable(withCheckDelay).orElse(Duration.ZERO);
            schedule();
        }

        private Instant nearestInstant() {
            return Instant.now().plus(this.checkDelay);
        }

        // We check for expired/complete no earlier than Instant.now(), but we can move forward by checkDelay...
        private Instant checkInstant() {
            Instant now = Instant.now();
            return of(nearestInstant()).filter(now::isBefore).orElse(now);
        }

        private Behavior<Command<M>> initial() {
            return Behaviors.receive(commands.COMMAND)
                    .onMessage(commands.ADD_COMMAND, this::onAddCommand)
                    .onMessage(commands.GRACEFUL_STOP, this::onGracefulStop)
                    .onMessageEquals(commands.TIMEOUT, this::processState)
                    .build();
        }

        private Behavior<Command<M>> onGracefulStop(GracefulStop<M> gracefulStop) {
            return Behaviors.stopped(gracefulStop::complete);
        }

        private Behavior<Command<M>> processState() {
            of(timeRange.extractFiredElements(nearestInstant()))
                    .filter(Predicate.not(Collection::isEmpty))
                    .ifPresent(firedConsumer);
            return nextBehavior();
        }

        private Behavior<Command<M>> onAddCommand(@Nonnull AddCommand<M> command) {
            try {
                command.getCompletableFuture().complete(
                        timeRange.isExpired(checkInstant()) ? command.getData() : timeRange.addElements(command.getData())
                );
            } catch (Exception throwable) {
                command.getCompletableFuture().completeExceptionally(throwable);
            }
            return nextBehavior();
        }

        private boolean isComplete() {
            if (timeRange.isComplete(checkInstant())) {
                if (timers.isTimerActive(this.timeRange)) timers.cancel(this.timeRange);
                return true;
            }
            schedule();
            return false;
        }

        private void schedule() {
            timers.startSingleTimer(this.timeRange, commands.TIMEOUT, timeRange.duration(nearestInstant()));
        }

        private Behavior<Command<M>> nextBehavior() {
            return this.isComplete() ? Behaviors.stopped() : Behaviors.same();
        }

    }

    class Timeout<X> implements Command<X> {}
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    class GracefulStop<X> implements Command<X> {
        @Getter private final CompletableFuture<Void> completableFuture;
        public void complete() { of(this.completableFuture).filter(Predicate.not(CompletableFuture::isDone)).ifPresent(future -> future.complete(null)); }
        @Override public void unhandled() { complete(); }
    }
    class AddCommand<X> implements Command<X> {
        @Getter private final Collection<X> data;
        @Getter private final CompletableFuture<Collection<X>> completableFuture;
        public AddCommand(@Nonnull Collection<X> data, @Nonnull CompletableFuture<Collection<X>> completableFuture) {
            Objects.requireNonNull(data, "AddCommand::new - data s null");
            Objects.requireNonNull(completableFuture, "AddCommand::new - completableFuture s null");
            this.data = List.copyOf(data);
            this.completableFuture = completableFuture;
        }
        @Override public void unhandled() { of(this.completableFuture).filter(Predicate.not(CompletableFuture::isDone)).ifPresent(future -> future.complete(this.data)); }
    }

    /**
     * Creating an actor behavior in which, in the event of an alarm, a trigger method will be called: firedConsumer,
     * where the triggered alarms will be transferred
     * @param time actual border for plotting the final TimeRangeHolder
     * @param timeRangeConfig configuration of a structure that describes alarm clocks with a schedule for their operation for a given period
     * @param firedConsumer listener for handling triggered alarms
     * @param withCheckDuration - the time interval by which the moment of polling timeRangeItems changes from the current moment (it can be either forward or backward in time)
     * @param <M> type of input describing the alarm
     * @param <R> the type of outgoing data that is passed to the processing function
     * @return Behavior for Time Period Actor Processor with Alarms
     */
    static <M,R> Behavior<Command<M>> processor(
            @Nonnull TemporalAccessor time,
            @Nonnull TimeRangeConfig<M,R> timeRangeConfig,
            @Nonnull FiredElementsConsumer<R> firedConsumer,
            @Nullable Duration withCheckDuration) {
        return Behaviors.withTimers(timers -> new RangeProcessor<M,R>(timers, time, timeRangeConfig, firedConsumer, withCheckDuration).initial());
    }

    /**
     * Creating an actor behavior in which, in the event of an alarm, messages will be sent to the actor-receiver for processing alarms
     * @param time actual border for plotting the final TimeRangeHolder
     * @param timeRangeConfig configuration of a structure that describes alarm clocks with a schedule for their operation for a given period
     * @param firedActor actor-receiver of a message with triggered alarms
     * @param responseSupplier the function of constructing a message to the subscriber actor from the list of triggered alarms
     * @param withCheckDuration - the time interval by which the moment of polling timeRangeItems changes from the current moment (it can be either forward or backward in time)
     * @param <M> type of input describing the alarm
     * @param <R> the type of outgoing data that is passed to the processing function
     * @param <X> the type of message received by the subscriber actor
     * @return Behavior for Time Period Actor Processor with Alarms
     */
    static <M,R,X> Behavior<Command<M>> processor(
            @Nonnull TemporalAccessor time,
            @Nonnull TimeRangeConfig<M,R> timeRangeConfig,
            @Nonnull ActorRef<X> firedActor,
            @Nonnull Function<Collection<R>,X> responseSupplier,
            @Nullable Duration withCheckDuration) {
        return processor(
                time,
                timeRangeConfig,
                firedElements -> of(firedElements)
                        .filter(Predicate.not(Collection::isEmpty))
                        .map(responseSupplier)
                        .ifPresent(firedActor::tell),
                withCheckDuration);
    }

    /**
     * Sending a message to the actor - time period handler to add a set of elements
     * @param timeRangeActor actor - time period handler
     * @param elements list of added alarms
     * @param completableFuture Completable Future for the response (takes a list of unaccepted elements) if present
     * @param <M> the type of message being sent
     */
    static <M> void addElements(
            @Nonnull ActorRef<Command<M>> timeRangeActor, @Nonnull Collection<M> elements, @Nullable CompletableFuture<Collection<M>> completableFuture
    ) {
        Objects.requireNonNull(timeRangeActor, "TimeRange::addElements - timeRangeActor is null");
        CompletableFuture<Collection<M>> future = Optional.ofNullable(completableFuture).orElseGet(CompletableFuture::new);
        of(Objects.requireNonNull(elements, "TimeRange::addElements - elements is null"))
                .filter(Predicate.not(Collection::isEmpty))
                .filter(ignore -> !future.isDone())
                .map(data -> new AddCommand<>(data, future))
                .ifPresentOrElse(
                        timeRangeActor::tell,
                        () -> future.complete(Collections.emptySet())
                );
    }

    /**
     * Sending a message to the actor - time period handler to add a set of elements
     * @param timeRangeActor actor - time period handler
     * @param elements list of added alarms
     * @param <M> the type of message being sent
     * @return Completion Stage of the response (takes a list of unaccepted elements)
     */
    @Nonnull
    static <M> CompletionStage<Collection<M>> addElements(
            @Nonnull ActorRef<Command<M>> timeRangeActor, @Nonnull Collection<M> elements
    ) {
        return Commands.completableFuture(completableFuture -> addElements(timeRangeActor, elements, completableFuture));
    }

    /**
     * Sending a message to the actor - time period handler to add a set of elements, ignoring the exception
     * @param timeRangeActor actor - time period handler
     * @param elements list of added alarms
     * @param onCompleteWithReject listener that fires when elements are added during the processing period (it will receive unaccepted elements as a parameter)
     * @param <M> the type of message being sent
     */
    static <M> void addElements(
            @Nonnull ActorRef<Command<M>> timeRangeActor, @Nonnull Collection<M> elements, @Nonnull Consumer<Collection<M>> onCompleteWithReject
    ) {
        addElements(timeRangeActor, elements).thenAccept(Objects.requireNonNull(onCompleteWithReject, "TimeRange::addElements - onCompleteWithReject is null"));
    }

    /**
     * Sending an actor - time period handler a message to add a set of elements with exception handling
     * @param timeRangeActor actor - time period handler
     * @param elements list of added alarms
     * @param onCompleteWithReject listener that fires when elements are added during the processing period (it will receive unaccepted elements as a parameter)
     * @param onThrow trigger method that fires when an exception occurs when registering elements in TimeRangeImages
     * @param <M> the type of message being sent
     */
    static <M> void addElements(
            @Nonnull ActorRef<Command<M>> timeRangeActor, @Nonnull Collection<M> elements, @Nonnull Consumer<Collection<M>> onCompleteWithReject, @Nonnull Consumer<Throwable> onThrow
    ) {
        Objects.requireNonNull(onCompleteWithReject, "TimeRange::addElements - onCompleteWithReject is null");
        Objects.requireNonNull(onThrow, "TimeRange::addElements - onThrow is null");
        addElements(timeRangeActor, elements).whenComplete((rejected, throwable) -> ofNullable(throwable).ifPresentOrElse(onThrow, () -> onCompleteWithReject.accept(rejected)));
    }

    /**
     * Send TimeRange actor Graceful Stop event
     * @param timeRangeActor actor - time period handler
     * @param <M> the type of message being sent
     * @return Completion Stage of the response (wait for stop)
     */
    static <M> CompletionStage<Void> stop(@Nonnull ActorRef<Command<M>> timeRangeActor) {
        return Commands.completableFuture(completableFuture -> timeRangeActor.tell(new GracefulStop<>(completableFuture)));
    }

    /**
     * This method creates an actor that handles lost AddCommand messages and marks them as rejected on all elements.
     * @param context the context within which the actor is created
     * @param watchForActor filter by actor whose deadLetters we are monitoring (if not specified, all within the actorSystem context)
     * @param actorName the name of the actor that will reject the dead-letter for AddCommand
     */
    static void subscribeToDeadCommands(@Nonnull ActorContext<?> context, @Nonnull ActorRef<? extends Command<?>> watchForActor, @Nonnull String actorName) {
        Objects.requireNonNull(watchForActor, "TimeRange::subscribeToDeadCommands - watchForActor is null");
        Objects.requireNonNull(actorName, "TimeRange::subscribeToDeadCommands - actorName is null");
        Objects.requireNonNull(context, "TimeRange::subscribeToDeadCommands - context is null")
                .getSystem()
                .eventStream()
                .tell(new EventStream.Subscribe<>(
                        DeadLetter.class,
                        context.spawn(new AddCommandDeadSubscriber(watchForActor).construct(), actorName)));
    }

    /**
     * Subscribing to DeadLetter messages of type Command
     */
    class AddCommandDeadSubscriber {

        private final ActorPath deadActorPath;

        private AddCommandDeadSubscriber(@Nonnull ActorRef<? extends Command<?>> watchForActor) {
            this.deadActorPath = of(watchForActor).map(ActorRef::path).orElse(null);
        }

        private Behavior<DeadLetter> construct() {
            return Behaviors.receive(DeadLetter.class)
                    .onMessage(DeadLetter.class, this::onDeadLetter)
                    .build();
        }

        private boolean checkPath(@Nonnull ActorPath checkPath) {
            return deadActorPath.equals(checkPath) || (!checkPath.equals(checkPath.root()) && checkPath(checkPath.parent()));
        }

        @SuppressWarnings("rawtypes")
        private Behavior<DeadLetter> onDeadLetter(DeadLetter deadLetter) {
            of(deadLetter)
                    .filter(letter -> checkPath(letter.recipient().path()))
                    .map(DeadLetter::message)
                    .filter(Command.class::isInstance)
                    .map(Command.class::cast)
                    .ifPresent(Command::unhandled);
            return Behaviors.same();
        }

    }

    interface TimeRangeWakedUp extends BiConsumer<Instant, Instant> {
        @Override void accept(@Nonnull Instant startInstant, @Nonnull Instant endInstant);
    }

    static <M,R> Behavior<Command<M>> service(
            @Nonnull ActorContext<Command<M>> context,
            @Nonnull TimeRangeConfig<M, R> timeRangeConfig,
            @Nonnull FiredElementsConsumer<R> firedConsumer,
            @Nullable Duration withCheckDuration,
            @Nullable Integer rangeDepth,
            @Nullable Integer nrOfInstances,
            @Nonnull TimeRangeWakedUp timeRangeWakedUp
    ) {
        return new Service<>(context, timeRangeConfig, firedConsumer, withCheckDuration, rangeDepth, nrOfInstances, timeRangeWakedUp);
    }

    class Service<M,R> extends AbstractBehavior<Command<M>> {

        private static final String DEAD_ADD_COMMAND_ACTOR = "dead-command-processor";
        private static final String TIME_RANGE_NAME_PREFIX = "time-range-0x";
        private static final String FIRED_RANGE_ID = "fires";

        private final Commands<M> commands = new Commands<>();
        private final int rangeDepth;
        private final Integer nrOfInstances;
        private final TimeRangeWakedUp timeRangeWakedUp;
        private final TimeRangeConfig<M, R> timeRangeConfig;
        private final Duration withCheckDuration;
        private final long timeRangeTicks;
        private final FiredElementsConsumer<R> firedConsumer;
        private final Map<String, ActorRef<Command<M>>> timeRangeActors = new HashMap<>();

        /**
         * Alarm processor with TimeRange auto-raise, parallelization and informing about TimeRange creation
         *
         * @param context AKKA ActorContext
         * @param timeRangeConfig config to describe region settings
         * @param firedConsumer consumer that implements a reaction to the rise of TimeRange elements
         * @param withCheckDuration an interval that allows you to react to events in advance or with a delay
         * @param rangeDepth the depth to which TimeRange rises at moments in the future
         * @param nrOfInstances number of TimeRegion actors per time interval
         * @param timeRangeWakedUp consumer informing about the rise of interval processing
         */
        @SuppressWarnings("java:S116")
        private Service(
                @Nonnull ActorContext<Command<M>> context,
                @Nonnull TimeRangeConfig<M, R> timeRangeConfig,
                @Nonnull FiredElementsConsumer<R> firedConsumer,
                @Nullable Duration withCheckDuration,
                @Nullable Integer rangeDepth,
                @Nullable Integer nrOfInstances,
                @Nonnull TimeRangeWakedUp timeRangeWakedUp
        ) {
            super(Objects.requireNonNull(context, "Service::new - context is null"));
            this.withCheckDuration = withCheckDuration;
            this.rangeDepth = rangeDepth == null ? 1 : Math.max(0, rangeDepth);
            this.nrOfInstances = nrOfInstances == null ? 1 : Math.max(1, nrOfInstances);
            this.timeRangeWakedUp = Objects.requireNonNull(timeRangeWakedUp, "Service::new - timeRangeWakedUp is null");
            this.timeRangeConfig = Objects.requireNonNull(timeRangeConfig, "Service::new - timeRangeConfig is null");
            this.timeRangeTicks = this.timeRangeConfig.getDuration().abs().toMillis();
            this.firedConsumer = Objects.requireNonNull(firedConsumer, "Service::new - firedConsumer is null");
            startDeadLetterWatching();
            startRegions();
        }

        private void startDeadLetterWatching() {
            TimeRange.subscribeToDeadCommands(getContext(), getContext().getSelf(), DEAD_ADD_COMMAND_ACTOR);
        }

        @Override
        public Receive<Command<M>> createReceive() {
            return newReceiveBuilder()
                    .onMessage(commands.ADD_COMMAND, this::onAddCommand)
                    .onMessage(commands.GRACEFUL_STOP, this::onGracefulStop)
                    .onSignal(Terminated.class, this::onTerminate)
                    .build();
        }

        private Behavior<Command<M>> onGracefulStop(GracefulStop<M> gracefulStop) {
            return Behaviors.stopped(gracefulStop::complete);
        }

        @SuppressWarnings({"unchecked", "rawtypes", "java:S1170", "java:S1905"})
        private final CompletableFuture<Collection<M>>[] completableFutureArray = (CompletableFuture<Collection<M>>[]) new CompletableFuture[]{};

        private Behavior<Command<M>> onAddCommand(AddCommand<M> addCommand) {
            // Divide all added elements into groups by timeRangeId. Next, we build calls that complete their CompletableFutures
            // If all completed without an error, then we accumulate the result into one collection and send it to the CompletableFuture of the request
            CompletableFuture<Collection<M>>[] futures = addCommand.getData()
                    .stream()
                    .collect(Collectors.groupingBy(this::timeRangeId))
                    .entrySet().stream()
                    .map(entry -> sendCommand(entry.getKey(), entry.getValue()))
                    .collect(Collectors.toList())
                    .toArray(completableFutureArray);
            CompletableFuture.allOf(futures).whenComplete((ignore, throwable) ->
                    ofNullable(throwable)
                            .ifPresentOrElse(
                                    addCommand.getCompletableFuture()::completeExceptionally,
                                    () -> addCommand.getCompletableFuture().complete(
                                            Arrays.stream(futures)
                                                    .map(CompletableFuture::join)
                                                    .flatMap(Collection::stream)
                                                    .collect(Collectors.toSet())
                                    )
                            )
            );
            return this;
        }

        private String timeRangeId(M data) {
            Instant instant = Instant.from(this.timeRangeConfig.getExpectation().apply(data));
            if (!Instant.now().plus(this.withCheckDuration).isBefore(instant)) {
                return FIRED_RANGE_ID;
            } else {
                return this.regionTickName(this.timeRangeTick(instant), Math.abs(data.hashCode() % nrOfInstances)+1);
            }
        }

        private CompletableFuture<Collection<M>> sendCommand(String regionId, Collection<M> data) {
            // For fired elements, we call the trigger function for triggered entries on them
            return FIRED_RANGE_ID.equals(regionId) ? fireData(data)
                    // If the set has a child actor, we send elements to it and return a CompletableFuture for them
                    : ofNullable(timeRangeActors.get(regionId))
                    .map(timeRangeActor -> Commands.<Collection<M>>completableFuture(future -> TimeRange.addElements(timeRangeActor, data, future)))
                    .orElseGet(() -> Commands.completableFuture(completableFuture -> completableFuture.complete(data)));
        }

        private CompletableFuture<Collection<M>> fireData(Collection<M> data) {
            return Commands.completableFuture(completableFuture -> {
                try {
                    this.firedConsumer.accept(data.stream().map(this.timeRangeConfig.getExtractor()).collect(Collectors.toSet()));
                    completableFuture.complete(Collections.emptyList());
                } catch (Exception ex) {
                    completableFuture.completeExceptionally(ex);
                }
            });
        }

        private long timeRangeKeyTick(@Nonnull String key) {
            return Long.valueOf(Optional.of(key.indexOf('-')).filter(idx -> idx > -1).map(idx -> key.substring(0, idx)).orElse(key), 16);
        }

        private int startedRegionsCnt() {
            return (int) this.timeRangeActors.keySet().stream()
                    .map(this::timeRangeKeyTick)
                    .distinct().count();
        }

        private Behavior<Command<M>> onTerminate(Terminated terminated) {
            ofNullable(this.regionTickName(terminated.getRef().path().name()))
                    .filter(this.timeRangeActors::containsKey)
                    .ifPresent(this.timeRangeActors::remove);
            startRegions();
            return this;
        }

        private long timeRangeTick(Instant instant) {
            long tick = instant.toEpochMilli();
            return tick - tick % this.timeRangeTicks;
        }

        // The beginning of the first moment of the region available in timeRangeActors - from it, we start the countdown for the rise
        private long initialTick(Instant now) {
            return this.timeRangeActors.keySet().stream()
                    .map(key -> key.indexOf('-') == -1 ? key : key.substring(0, key.indexOf('-')))
                    .distinct()
                    .map(hex -> Long.valueOf(hex, 16))
                    .reduce(Long::min)
                    .orElseGet(() -> timeRangeTick(ofNullable(now).orElseGet(Instant::now)));
        }

        // instantId values from 1 to nrOfInstances
        private String regionTickName(long regionTick, int instantId) {
            return Long.toHexString(regionTick)+(nrOfInstances == 1 ? "" : "-"+instantId);
        }

        private boolean isTimeRegionStarted(long regionTick) {
            return IntStream.rangeClosed(1, nrOfInstances)
                    .mapToObj(id -> regionTickName(regionTick, id))
                    .anyMatch(this.timeRangeActors::containsKey);
        }

        @SuppressWarnings("java:S127")
        private Behavior<Command<M>> startRegions() {
            int started = startedRegionsCnt();
            if (started <= this.rangeDepth ) {
                Instant now = Instant.now();
                long regionTick = initialTick(now.plus(withCheckDuration));
                for (int range = 0; range <= this.rangeDepth - started; regionTick += this.timeRangeTicks) {
                    // If there is at least one actor on timeRegion, we consider it to be working.
                    if ( !isTimeRegionStarted(regionTick) &&
                         (!Instant.ofEpochMilli(regionTick).isBefore(now.plus(withCheckDuration)) ||
                          Instant.ofEpochMilli(regionTick + this.timeRangeTicks).isAfter(now.plus(withCheckDuration)))
                    ) {
                        startRegion(regionTick);
                        range += 1;
                    }
                }
            }
            return this;
        }

        // Called when there are no actors in the region, but bypasses creation if there are
        private void startRegion(long regionTick) {
            IntStream.rangeClosed(1, this.nrOfInstances)
                    .mapToObj(id -> this.regionTickName(regionTick, id))
                    .filter(Predicate.not(this.timeRangeActors::containsKey))
                    .forEach(regionTickName -> startRegionActor(regionTickName, Instant.ofEpochMilli(regionTick)));
            this.timeRangeWakedUp.accept(Instant.ofEpochMilli(regionTick), Instant.ofEpochMilli(regionTick+this.timeRangeTicks));
        }

        private void startRegionActor(String regionTickName, Instant instant) {
            ActorRef<Command<M>> timeRegionActor = getContext().spawn(
                    processor(this.regionInstant(instant), this.timeRangeConfig, this.firedConsumer, this.withCheckDuration),
                    regionActorName(regionTickName)
            );
            this.timeRangeActors.put(regionTickName, timeRegionActor);
            getContext().watch(timeRegionActor);
        }

        private String regionActorName(String regionTick) {
            return TIME_RANGE_NAME_PREFIX + regionTick.toUpperCase();
        }

        private String regionTickName(String regionActorName) {
            return of(regionActorName)
                    .filter(name -> name.startsWith(TIME_RANGE_NAME_PREFIX))
                    .map(name -> name.substring(TIME_RANGE_NAME_PREFIX.length()))
                    .map(String::toLowerCase)
                    .orElse(null);
        }

        // If we have a negative duration, then we build the region from the right border
        private Instant regionInstant(Instant instant) {
            return of(this.timeRangeConfig.getDuration())
                    .filter(Duration::isNegative)
                    .map(instant::minus)
                    .orElse(instant);
        }

    }

    /**
     * When processing messages to the Service actor, it is desirable to process messages about the completion
     * and start of the RangeProcessor out of turn
     */
    class Mailbox extends UnboundedStablePriorityMailbox {

        @SuppressWarnings("java:S1172")
        public Mailbox(ActorSystem.Settings settings, Config config) {
            super(new PriorityGenerator() {
                @Override
                public int gen(Object msg) {
                    if (msg instanceof akka.actor.Terminated || msg instanceof akka.actor.typed.Terminated) {
                        return 1;
                    } else if (msg instanceof TimeRange.AddCommand) {
                        return 4;
                    } else if (msg instanceof TimeRange.Timeout) {
                        return 3;
                    } else if (msg instanceof TimeRange.Command) {
                        return 5;
                    } else if (msg instanceof Signal) {
                        return 2;
                    }
                    return 0;
                }
            });
        }

    }

}