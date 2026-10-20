/*
 * Copyright © 2017-2023 Andrey D. Shindarev. All rights reserved.
 * This program is made available under the terms of the BSD 3-Clause License.
 * Contacts: ashindarev@gmail.com
 */
package com.github.sftwnd.crayfish.alarms.timerange;

import edu.umd.cs.findbugs.annotations.NonNull;

import java.util.Collection;

/**
 *  Alarm consumer
 * @param <M> type of the added elements
 * @param <R> type of the result object constructed from the ignored elements
 */
public interface ITimeRangeConsumer<M,R> {

    /**
     * Add the elements to the range; the elements out of the range are not added
     * @param elements collection of added elements
     * @return result object constructed from the elements that are not added
     */
    @NonNull R addElements(@NonNull Collection<M> elements);

}
