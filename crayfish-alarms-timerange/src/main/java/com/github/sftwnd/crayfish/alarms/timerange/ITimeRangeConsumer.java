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
 */
public interface ITimeRangeConsumer<M,R> {

    /**
     * Add the specified set of elements to the range map
     * The attribute will also be set: nearestInstant
     * Out-of-range data is ignored with a message
     * @param elements collection of added elements
     * @return result object constructed from ignored elements
     */
    @NonNull R addElements(@NonNull Collection<M> elements);

}
