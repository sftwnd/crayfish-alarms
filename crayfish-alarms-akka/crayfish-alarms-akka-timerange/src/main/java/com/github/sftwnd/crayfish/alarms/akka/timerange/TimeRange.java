/*
 * Copyright © 2017-20xx Andrey D. Shindarev. All rights reserved.
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
import com.github.sftwnd.crayfish.alarms.timerange.TimeRangeItems;
import com.typesafe.config.Config;
import lombok.NonNull;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static java.util.Optional.of;
import static java.util.Optional.ofNullable;

/**
 * Интерфейс протокола Typed Akka Actor для TimeRange по регистрации будильников и их обработке
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
     * Базовый интерфейс для описания типа входящих сообщений
     * @param <X> generic для описания содержимого команды
     */
    @SuppressWarnings("java:S2326")
    interface Command<X> {}

    final class TimeRangeCommands<M> {
        // Это кратчайшее описание приведения, по этой причине выбрано именно оно
        @SuppressWarnings({"unchecked", "rawtypes", "java:S116", "java:S1170"})
        private final Class<Command<M>> COMMAND = (Class<Command<M>>)((Class<? extends Command>) Command.class);
        @SuppressWarnings({"unchecked", "rawtypes", "java:S116", "java:S1170"})
        private final Class<AddCommand<M>> ADD_COMMAND = (Class<AddCommand<M>>)((Class<? extends AddCommand>) AddCommand.class);
        @SuppressWarnings("java:S116")
        private final Timeout<M> TIMEOUT = new Timeout<>() {};
        private TimeRangeCommands() {}
    }

    interface FiredElementsConsumer<M> extends Consumer<Collection<M>> {
        @Override void accept(@Nonnull Collection<M> t);
    }

    /**
     * Процессор, содержащий в себе TimeRangeItems структуру с будильниками. Занимается сохранением входящих будильников и
     * инициализацией реакции на их срабатывание.
     * @param <M> Тип входящего сообщения на регистрацию будильника
     * @param <R> Тип уходящего в обработку объекта по факту срабатывания будильника
     */
    class TimeRangeProcessor<M,R> {

        private final TimeRangeCommands<M> commands = new TimeRangeCommands<>();
        private final TimerScheduler<Command<M>> timers;
        private final TimeRangeItems<M,R> timeRange;
        private final FiredElementsConsumer<R> firedConsumer;
        private final Duration checkDelay;

        private TimeRangeProcessor(
                @NonNull  TimerScheduler<Command<M>> timers,
                @NonNull  Instant instant,
                @NonNull  TimeRangeItems.Config<M,R> timeRangeConfig,
                @NonNull  FiredElementsConsumer<R> firedConsumer,
                // В пределах TimeRangeItems::interval можно задать значение насколько раньше считать запись сработавшей...
                @Nullable Duration withCheckDelay
        ) {
            this.timers = timers;
            this.timeRange = timeRangeConfig.timeRange(instant);
            this.firedConsumer = firedConsumer;
            this.checkDelay = ofNullable(withCheckDelay).orElse(Duration.ZERO);
            schedule();
        }

        private Instant nearestInstant() {
            return Instant.now().plus(this.checkDelay);
        }

        // Проверку на expired/complete делаем не ранее, чем Instant.now(), но можем отодвинуть на checkDelay вперёд...
        private Instant checkInstant() {
            Instant now = Instant.now();
            return of(nearestInstant()).filter(now::isBefore).orElse(now);
        }

        private Behavior<Command<M>> initial() {
            return Behaviors.receive(commands.COMMAND)
                    .onMessage(commands.ADD_COMMAND, this::onAddCommand)
                    .onMessageEquals(commands.TIMEOUT, this::processState)
                    .build();
        }

        private Behavior<Command<M>> processState() {
            of(timeRange.getFiredElements(nearestInstant()))
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

    class AddCommand<X> implements Command<X> {
        private final Collection<X> data;
        private final CompletableFuture<Collection<X>> completableFuture;
        public AddCommand(@NonNull Collection<X> data, @NonNull CompletableFuture<Collection<X>> completableFuture) {
            this.data = List.copyOf(data);
            this.completableFuture = completableFuture;
        }
        public @Nonnull Collection<X> getData() { return this.data; }
        public @Nonnull CompletableFuture<Collection<X>> getCompletableFuture() { return this.completableFuture; }
        public void unhandled() { of(this.completableFuture).filter(Predicate.not(CompletableFuture::isDone)).ifPresent(future -> future.complete(this.data)); }
    }

    /**
     * Создание поведения актора при котором в случае срабатывания будильника будет вызван триггер-метод: firedConsumer,
     * куда будут переданы сработавшие будильники
     * @param instant фактическая граница для построения итогового TimeRangeItems
     * @param timeRangeConfig - конфигурация структуры, описывающей будильники с расписанием их срабатывания на заданный период
     * @param firedConsumer - триггер-метод обработки сработавших будильников
     * @param withCheckDuration - временной интервал на который от текущего момента изменяется момент опроса timeRangeItems
     *                          (может быть как вперед, так и назад во времени)
     * @param <M> - тип входящих данных, описывающих будильник
     * @param <R> - тип исходящих данных, которые передаются в функцию обработки
     * @return Поведение для актора-процессора временного периода с будильниками
     */
    static <M,R> Behavior<Command<M>> create(
            @NonNull Instant instant,
            @NonNull TimeRangeItems.Config<M,R> timeRangeConfig,
            @NonNull FiredElementsConsumer<R> firedConsumer,
            @Nullable Duration withCheckDuration) {
        return Behaviors.withTimers(timers -> new TimeRangeProcessor<M,R>(timers, instant, timeRangeConfig, firedConsumer, withCheckDuration).initial());
    }

    /**
     * Создание поведения актора при котором в случае срабатывания будильника будет отправлено сообщения на actor-приёмник по обработке будильников
     * @param instant фактическая граница для построения итогового TimeRangeItems
     * @param timeRangeConfig - конфигурация структуры, описывающей будильники с расписанием их срабатывания на заданный период
     * @param firedActor - актор-приёмник сообщения со сработавшими будильниками
     * @param responseSupplier - функция построения из списка сработавших будильников сообщение актору - подписчику
     * @param withCheckDuration - временной интервал на который от текущего момента изменяется момент опроса timeRangeItems
     *                          (может быть как вперед, так и назад во времени)
     * @param <M> - тип входящих данных, описывающих будильник
     * @param <R> - тип исходящих данных, которые передаются в функцию обработки
     * @param <X> - тип принимаемого актором-подписчиком сообщения
     * @return Поведение для актора-процессора временного периода с будильниками
     */
    static <M,R,X> Behavior<Command<M>> create(
            @NonNull Instant instant,
            @NonNull TimeRangeItems.Config<M,R> timeRangeConfig,
            @NonNull ActorRef<X> firedActor,
            @NonNull Function<Collection<R>,X> responseSupplier,
            @Nullable Duration withCheckDuration) {
        return create(
                instant,
                timeRangeConfig,
                firedElements -> of(firedElements)
                        .filter(Predicate.not(Collection::isEmpty))
                        .map(responseSupplier)
                        .ifPresent(firedActor::tell),
                withCheckDuration);
    }

    /**
     * Отправка актору - обработчику временного периода сообщения на добавление набора элементов
     * @param timeRangeActor актор - обработчик временного периода
     * @param elements список добавляемых будильников
     * @param completableFuture Completable Future ответа (принимает список непринятых элементов) 
     * @param <M> тип отправляемого сообщения
     */
    static <M> void addElements(
            @NonNull ActorRef<Command<M>> timeRangeActor, @NonNull Collection<M> elements, @NonNull CompletableFuture<Collection<M>> completableFuture
    ) {
        of(elements)
                .filter(Predicate.not(Collection::isEmpty))
                .map(data -> new AddCommand<>(data, completableFuture))
                .ifPresentOrElse(
                        timeRangeActor::tell,
                        () -> completableFuture.complete(Collections.emptySet())
                );
    }

    /**
     * Отправка актору - обработчику временного периода сообщения на добавление набора элементов
     * @param timeRangeActor актор - обработчик временного периода
     * @param elements список добавляемых будильников
     * @param <M> тип отправляемого сообщения
     * @return Completable Future ответа (принимает список непринятых элементов) 
     */
    @Nonnull
    static <M> CompletableFuture<Collection<M>> addElements(
            @NonNull ActorRef<Command<M>> timeRangeActor, @NonNull Collection<M> elements
    ) {
        CompletableFuture<Collection<M>> completableFuture = new CompletableFuture<>();
        addElements(timeRangeActor, elements, completableFuture);
        return completableFuture;
    }

    /**
     * Отправка актору - обработчику временного периода сообщения на добавление набора элементов с игнорированием исключения
     * @param timeRangeActor актор - обработчик временного периода
     * @param elements список добавляемых будильников
     * @param onCompleteWithReject метод-триггер, срабатывающая по факту добавления элементов в период обработки (в качестве параметра получит непринятые элементы)
     * @param <M> тип отправляемого сообщения
     */
    static <M> void addElements(
            @NonNull ActorRef<Command<M>> timeRangeActor, @NonNull Collection<M> elements, @NonNull Consumer<Collection<M>> onCompleteWithReject
    ) {
        addElements(timeRangeActor, elements).thenAccept(onCompleteWithReject);
    }

    /**
     * Отправка актору - обработчику временного периода сообщения на добавление набора элементов с обработкой исключения
     * @param timeRangeActor актор - обработчик временного периода
     * @param elements список добавляемых будильников
     * @param onCompleteWithReject метод-триггер, срабатывающая по факту добавления элементов в период обработки (в качестве параметра получит непринятые элементы)
     * @param onThrow метод-триггер, срабатывающая по факту появления исключения при регистрации элементов в TimeRangeImages
     * @param <M> тип отправляемого сообщения
     */
    static <M> void addElements(
            @NonNull ActorRef<Command<M>> timeRangeActor, @NonNull Collection<M> elements, @NonNull Consumer<Collection<M>> onCompleteWithReject, @NonNull Consumer<Throwable> onThrow
    ) {
        addElements(timeRangeActor, elements).whenComplete((rejected, throwable) -> ofNullable(throwable).ifPresentOrElse(onThrow, () -> onCompleteWithReject.accept(rejected)));
    }

    /**
     * Данный метод создаёт anonymous актор, который обрабатывает потерянные AddCommand сообщения и отмечает их как исполненные с reject по всем элементам
     * @param context контекст в рамках которого создаётся актор
     * @param watchForActor фильтр по актору за deadLetters которого следим (если не указан - за всеми в рамках actorSystem контекста)
     * @param actorName имя актора, который будет reject-ить dead-letter для AddCommand
     */
    static void registerDeadCommandSubscriber(@NonNull ActorContext<?> context, @NonNull ActorRef<? extends Command<?>> watchForActor, @NonNull String actorName) {
        context.getSystem()
                .eventStream()
                .tell(new EventStream.Subscribe<>(
                        DeadLetter.class,
                        context.spawn(new AddCommandDeadSubscriber(watchForActor).construct(), actorName)));
    }

    /**
     * Подписка на DeadLetter сообщения типа Command
     */
    class AddCommandDeadSubscriber {

        private final ActorPath deadActorPath;

        private AddCommandDeadSubscriber(@NonNull ActorRef<? extends Command<?>> watchForActor) {
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
                    .filter(AddCommand.class::isInstance)
                    .map(AddCommand.class::cast)
                    .ifPresent(AddCommand::unhandled);
            return Behaviors.same();
        }

    }

    interface TimeRangeWakedUp extends BiConsumer<Instant, Instant> {
        @Override void accept(@Nonnull Instant startInstant, @Nonnull Instant endInstant);
    }

    class TimeRangeAutomaticProcessor<M,R> extends AbstractBehavior<Command<M>> {

        private static final String DEAD_ADD_COMMAND_ACTOR = "dead-command-processor";
        private static final String TIME_RANGE_NAME_PREFIX = "time-range-0x";
        private static final String FIRED_RANGE_ID = "fires";

        private final TimeRangeCommands<M> commands = new TimeRangeCommands<>();
        private final ActorContext<Command<M>> context;
        private final int rangeDepth;
        private final Integer nrOfInstances;
        private final TimeRangeWakedUp timeRangeWakedUp;
        private final TimeRangeItems.Config<M, R> timeRangeConfig;
        private final Duration withCheckDuration;
        private final long timeRangeTicks;
        private final FiredElementsConsumer<R> firedConsumer;
        private final Map<String, ActorRef<Command<M>>> timeRangeActors = new HashMap<>();

        /**
         * Процессор будильников с автоподъёмом TimeRange, распараллеливанием и информированием о создании TimeRange
         *
         * @param context AKKA ActorContext
         * @param timeRangeConfig config для описания параметров региона
         * @param firedConsumer consumer реализующий реакцию на подъём элементов TimeRange
         * @param withCheckDuration интервал, позволяющий реагировать на события заранее или с задержкой
         * @param rangeDepth глубина на которую поднимаются TimeRange на моменты в будущем
         * @param nrOfInstances количество TimeRegion акторов на один интервал времени
         * @param timeRangeWakedUp consumer информирующий о подъёме обработки интервала
         */
        @SuppressWarnings("java:S116")
        public TimeRangeAutomaticProcessor(
                @NonNull ActorContext<Command<M>> context,
                @NonNull TimeRangeItems.Config<M, R> timeRangeConfig,
                @NonNull FiredElementsConsumer<R> firedConsumer,
                @Nullable Duration withCheckDuration,
                @Nullable Integer rangeDepth,
                @Nullable Integer nrOfInstances,
                @NonNull TimeRangeWakedUp timeRangeWakedUp
        ) {
            super(context);
            this.context = context;
            this.withCheckDuration = withCheckDuration;
            this.rangeDepth = rangeDepth == null ? 1 : Math.max(0, rangeDepth);
            this.nrOfInstances = nrOfInstances == null ? 1 : Math.max(1, nrOfInstances);
            this.timeRangeWakedUp = timeRangeWakedUp;
            this.timeRangeConfig = timeRangeConfig;
            this.timeRangeTicks = this.timeRangeConfig.getDuration().abs().toMillis();
            this.firedConsumer = firedConsumer;
            startDeadLetterWatching();
            startRegions();
        }

        private void startDeadLetterWatching() {
            TimeRange.registerDeadCommandSubscriber(context, context.getSelf(), DEAD_ADD_COMMAND_ACTOR);
        }

        @Override
        public Receive<Command<M>> createReceive() {
            return newReceiveBuilder()
                    .onMessage(commands.ADD_COMMAND, this::onAddCommand)
                    .onSignal(Terminated.class, this::onTerminate)
                    .build();
        }

        @SuppressWarnings({"unchecked", "rawtypes", "java:S1170", "java:S1905"})
        private final CompletableFuture<Collection<M>>[] completableFutureArray = (CompletableFuture<Collection<M>>[]) new CompletableFuture[]{};

        private Behavior<Command<M>> onAddCommand(AddCommand<M> addCommand) {
            // Делим все добавляемые элементы на группы по timeRangeId. Далее строим вызовы, которые завершают свои CompletableFutures
            // Если все завершились без ошибки, то аккумулируем результат в одну коллекцию и отправляем в CompletableFuture запроса
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
            // Для fired элементов вызываем по ним функцию-триггер для сработавших записей
            return FIRED_RANGE_ID.equals(regionId) ? fireData(data)
                    // Если для набора есть дочерний актор - отправляем в него элементы и возвращаем CompletableFuture по ним
                    : ofNullable(timeRangeActors.get(regionId))
                    .map(timeRangeActor -> TimeRange.addElements(timeRangeActor, data))
                    .orElseGet(() -> {
                        // Если актора нет, то отклоняем добавление записей
                        CompletableFuture<Collection<M>> completableFuture = new CompletableFuture<>();
                        completableFuture.complete(data);
                        return completableFuture;
                    });
        }

        private CompletableFuture<Collection<M>> fireData(Collection<M> data) {
            CompletableFuture<Collection<M>> completableFuture = new CompletableFuture<>();
            try {
                this.firedConsumer.accept(data.stream().map(this.timeRangeConfig.getExtractor()).collect(Collectors.toSet()));
                completableFuture.complete(Collections.emptyList());
            } catch (Exception ex) {
                completableFuture.completeExceptionally(ex);
            }
            return completableFuture;
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

        // Начало первого имеющегося в timeRangeActors момента региона - от него начинаем отсчёт для подъёма
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
                    // Если на timeRegion существует хоть один actor - считаем его работающим.
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

        // Вызывается, когда нет actor-ов на region, но обходит создание при наличии
        private void startRegion(long regionTick) {
            IntStream.rangeClosed(1, this.nrOfInstances)
                    .mapToObj(id -> this.regionTickName(regionTick, id))
                    .filter(Predicate.not(this.timeRangeActors::containsKey))
                    .forEach(regionTickName -> startRegionActor(regionTickName, Instant.ofEpochMilli(regionTick)));
            this.timeRangeWakedUp.accept(Instant.ofEpochMilli(regionTick), Instant.ofEpochMilli(regionTick+this.timeRangeTicks));
        }

        private void startRegionActor(String regionTickName, Instant instant) {
            ActorRef<Command<M>> timeRegionActor = context.spawn(
                    create(this.regionInstant(instant), this.timeRangeConfig, this.firedConsumer, this.withCheckDuration),
                    regionActorName(regionTickName)
            );
            this.timeRangeActors.put(regionTickName, timeRegionActor);
            context.watch(timeRegionActor);
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

        // Если у нас duration отрицательный, то регион строим от правой границы
        private Instant regionInstant(Instant instant) {
            return of(this.timeRangeConfig.getDuration())
                    .filter(Duration::isNegative)
                    .map(instant::minus)
                    .orElse(instant);
        }

    }

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
