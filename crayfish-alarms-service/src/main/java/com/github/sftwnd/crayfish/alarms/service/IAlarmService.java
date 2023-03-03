package com.github.sftwnd.crayfish.alarms.service;

/**
 * The service allows you to register alarm clocks for operation according to a schedule and sets them on fire at the right time
 * @param <M> type of incoming alarm to register
 * @param <R> type of alarm clock
 */
public interface IAlarmService<M, R> extends IAlarmConsumer<M>, IAlarmProcessor<R> {
}
