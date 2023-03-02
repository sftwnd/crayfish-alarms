package com.github.sftwnd.crayfish.alarms.service;

import edu.umd.cs.findbugs.annotations.NonNull;

import java.util.Collection;
import java.util.Objects;
import java.util.function.Consumer;

public interface IAlarmProcessor<R> {

    /**
     * The process of igniting alarm clocks at a given moment in time
     * @param consumer Handler for a set of triggered alarms
     */
    void process(@NonNull Consumer<Collection<R>> consumer);

    /**
     * The process of firing alarms at a given moment in time
     * with an individual call to the handler for each triggered alarm
     * @param consumer Handler for a triggered alarm
     */
    default void singleProcess(@NonNull Consumer<R> consumer) {
        Objects.requireNonNull(consumer, "IAlarmProcessor::singleProcess - consumer is null");
        process(collection -> collection.forEach(consumer));
    }

}