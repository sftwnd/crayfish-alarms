/*
 * Copyright © 2017-20xx Andrey D. Shindarev. All rights reserved.
 * This program is made available under the terms of the BSD 3-Clause License.
 * Contacts: ashindarev@gmail.com
 */
package com.github.sftwnd.crayfish.alarms.akka.timerange;

import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.TimerScheduler;
import com.github.sftwnd.crayfish.alarms.timerange.TimeRangeItems;
import lombok.NonNull;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import static java.util.Optional.ofNullable;

/**
 * Интерфейс протокола Typed Akka Actor для TimeRange по регистрации будильников и их обработке
 */
public interface TimeRange {

    /**
     * Базовый интерфейс для описания типа входящих сообщений
     * @param <X> generic для описания содержимого команды
     */
    interface Command<X> {}

    /**
     * Процессор, содержащий в себе TimeRangeItems структуру с будильниками. Занимается сохранением входящих будильников и
     * инициализацией реакции на их срабатывание.
     * @param <M> Тип входящего сообщения на регистрацию будильника
     * @param <R> Тип уходящего в обработку объекта по факту срабатывания будильника
     */
    class TimeRangeProcessor<M,R> {

        // Это кратчайшее описание приведения, по этой причине выбрано именно оно
        @SuppressWarnings(value = {"unchecked", "rawtypes"})
        private final Class<Command<M>> COMMAND = (Class<Command<M>>)((Class<? extends Command>) Command.class);
        @SuppressWarnings(value = {"unchecked", "rawtypes"})
        private final Class<AddCommand<M>> ADD_COMMAND = (Class<AddCommand<M>>)((Class<? extends AddCommand>) AddCommand.class);
        private final Command<M> TIMEOUT = new Command<>() {};

        private final TimerScheduler<Command<M>> timers;
        private final TimeRangeItems<M,R> timeRange;
        private final Consumer<Collection<R>> firedConsumer;
        private final Duration checkDelay;

        private TimeRangeProcessor(
                @NonNull  TimerScheduler<Command<M>> timers,
                @NonNull  TimeRangeItems.Config<M,R> timeRangeConfig,
                @NonNull  Consumer<Collection<R>> firedConsumer,
                // В пределах TimeRangeItems::interval можно задать значение насколько раньше считать запись сработавшей...
                @Nullable Duration withCheckDelay
        ) {
            this.timers = timers;
            this.timeRange = timeRangeConfig.timeRange();
            this.firedConsumer = firedConsumer;
            this.checkDelay = ofNullable(withCheckDelay).orElse(Duration.ZERO);
        }

        private Instant nearestInstant() {
            return Instant.now().plus(this.checkDelay);
        }

        // Проверку на expired/complete делаем не ранее, чем Instant.now(), но можем отодвинуть на checkDelay вперёд...
        private Instant checkInstant() {
            Instant now = Instant.now();
            return Optional.of(nearestInstant()).filter(now::isBefore).orElse(now);
        }

        private Behavior<Command<M>> initial() {
            return Behaviors.receive(COMMAND)
                    .onMessage(ADD_COMMAND, this::onAddCommand)
                    .onMessageEquals(TIMEOUT, this::processState)
                    .build();
        }

        private Behavior<Command<M>> processState() {
            Optional.of(timeRange.getFiredElements(nearestInstant()))
                    .filter(Predicate.not(Collection::isEmpty))
                    .ifPresent(firedConsumer);
            return nextBehavior();
        }

        private Behavior<Command<M>> onAddCommand(@Nonnull AddCommand<M> command) {
            try {
                command.getCompletableFuture().complete(
                        timeRange.isExpired(checkInstant()) ? command.getData() : timeRange.addElements(command.getData())
                );
            } catch (Throwable throwable) {
                command.getCompletableFuture().completeExceptionally(throwable);
            }
            return nextBehavior();
        }

        private boolean isComplete() {
            if (timeRange.isComplete(checkInstant())) {
                if (timers.isTimerActive(this.timeRange)) timers.cancel(this.timeRange);
                return true;
            }
            Duration duration = timeRange.duration(nearestInstant());
            timers.startSingleTimer(this.timeRange, TIMEOUT, duration);
            return false;
        }

        private Behavior<Command<M>> nextBehavior() {
            return this.isComplete() ? Behaviors.stopped() : Behaviors.same();
        }

        private static class AddCommand<X> implements Command<X> {
            private final Collection<X> data;
            private final CompletableFuture<Collection<X>> completableFuture;
            public AddCommand(@NonNull Collection<X> data, @NonNull CompletableFuture<Collection<X>> completableFuture) {
                this.data = data;
                this.completableFuture = completableFuture;
            }
            public @Nonnull Collection<X> getData() { return this.data; }
            public @Nonnull CompletableFuture<Collection<X>> getCompletableFuture() { return this.completableFuture; }
        }

    }

    /**
     * Создание поведения актора при котором в случае срабатывания будильника будет вызван триггер-метод: firedConsumer,
     * куда будут переданы сработавшие будильники
     * @param timeRangeConfig - конфигурация структуры, описывающей будильники с расписанием их срабатывания на заданный период
     * @param firedConsumer - триггер-метод обработки сработавших будильников
     * @param withCheckDuration - временной интервал на который от текущего момента изменяется момент опроса timeRangeItems
     *                          (может быть как вперед, так и назад во времени)
     * @param <M> - тип входящих данных, описывающих будильник
     * @param <R> - тип исходящих данных, которые передаются в функцию обработки
     * @return Поведение для актора-процессора временного периода с будильниками
     */
    static <M,R> Behavior<Command<M>> create(
            @NonNull TimeRangeItems.Config<M,R> timeRangeConfig,
            @NonNull Consumer<Collection<R>> firedConsumer,
            @Nullable Duration withCheckDuration) {
        return Behaviors.withTimers(timers -> new TimeRangeProcessor<M,R>(timers, timeRangeConfig, firedConsumer, withCheckDuration).initial());
    }

    /**
     * Создание поведения актора при котором в случае срабатывания будильника будет отправлено сообщения на actor-приёмник по обработке будильников
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
            @NonNull TimeRangeItems.Config<M,R> timeRangeConfig,
            @NonNull ActorRef<X> firedActor,
            @NonNull Function<Collection<R>,X> responseSupplier,
            @Nullable Duration withCheckDuration) {
        return create(
                timeRangeConfig,
                firedElements -> ofNullable(firedElements)
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
        Optional.of(elements)
                .filter(Predicate.not(Collection::isEmpty))
                .map(data -> new TimeRangeProcessor.AddCommand<>(data, completableFuture))
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

}
