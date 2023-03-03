package com.github.sftwnd.crayfish.alarms.service;

import edu.umd.cs.findbugs.annotations.NonNull;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/**
 * The interface implements adding alarms to the TimeRange
 * @param <E> alarm type
 */
public interface IAlarmConsumer<E> {

    /**
     * Add new elements to Service with rejects in CompletionStage result
     * @param elements not null collection of elements to add
     * @return CompletionStage with rejected elements on completion
     */
    CompletionStage<Collection<E>> addElements(@NonNull Collection<E> elements);

    /**
     * Add single element to Service with reject in CompletionStage result
     * @param element not null element to add
     * @return CompletionStage with value in the case of rejection
     */
    default CompletionStage<E> addElement(@NonNull E element) {
        return addElements(List.of(Objects.requireNonNull(element, "IAlarmConsumer::addElement - element is null")))
                .thenApply(reject -> reject.stream().findFirst().orElse(null));
    }


}