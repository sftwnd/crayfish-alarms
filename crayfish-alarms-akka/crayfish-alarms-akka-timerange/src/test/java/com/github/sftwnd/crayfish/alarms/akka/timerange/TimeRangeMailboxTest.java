package com.github.sftwnd.crayfish.alarms.akka.timerange;

import akka.actor.ActorRef;
import akka.actor.Terminated;
import akka.actor.typed.Signal;
import akka.dispatch.Envelope;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class TimeRangeMailboxTest {

    static TimeRange.Mailbox mailbox;
    static Envelope envelopeObject;
    static Envelope envelopeTerminated;
    static Envelope envelopeGracefulStop;
    static Envelope envelopeSignal;
    static Envelope envelopeTimeout;
    static Envelope envelopeAddCommand;
    static Envelope envelopeCommand;

    @Test
    void typedObjectToTerminatedTest() {
        Assertions.assertEquals(-1, mailbox.cmp().compare(envelopeObject, envelopeTerminated), "Terminated level must be greater than Object level");
    }

    @Test
    void typedTerminatedToGracefulStopTest() {
        Assertions.assertEquals(-1, mailbox.cmp().compare(envelopeTerminated, envelopeGracefulStop), "GracefulStop level must be greater than Terminated level");
    }

    @Test
    void typedGracefulStopToSignalTest() {
        Assertions.assertEquals(-1, mailbox.cmp().compare(envelopeGracefulStop, envelopeSignal), "Signal level must be greater than GracefulStop level");
    }

    @Test
    void typedSignalToTimeoutTest() {
        Assertions.assertEquals(-1, mailbox.cmp().compare(envelopeSignal, envelopeTimeout), "Timeout level must be greater than Signal level");
    }

    @Test
    void typedAddCommandToTimeoutTest() {
        Assertions.assertEquals(-1, mailbox.cmp().compare(envelopeTimeout, envelopeAddCommand), "AddCommands level must be greater than Timeout level");
    }

    @Test
    void typedCommandToAddCommandTest() {
        Assertions.assertEquals(-1, mailbox.cmp().compare(envelopeAddCommand, envelopeCommand), "Commands level must be greater than AddCommands level");
    }

    @BeforeAll
    static void startUp() {
        mailbox = new TimeRange.Mailbox(null, null);
        ActorRef actorRef = Mockito.mock(ActorRef.class);
        envelopeObject = Envelope.apply(Mockito.mock(Object.class), actorRef);
        envelopeTerminated = Envelope.apply(Terminated.apply(actorRef, false, false), actorRef);
        envelopeGracefulStop = Envelope.apply(Mockito.mock(TimeRange.GracefulStop.class), actorRef);
        envelopeSignal = Envelope.apply(Mockito.mock(Signal.class), actorRef);
        envelopeTimeout = Envelope.apply(Mockito.mock(TimeRange.Timeout.class), actorRef);
        envelopeAddCommand = Envelope.apply(Mockito.mock(TimeRange.AddCommand.class), actorRef);
        envelopeCommand = Envelope.apply(Mockito.mock(TimeRange.Command.class), actorRef);
    }

    @AfterAll
    static void tearDown() {
        mailbox = null;
        envelopeObject = null;
        envelopeTerminated = null;
        envelopeGracefulStop = null;
        envelopeSignal = null;
        envelopeTimeout = null;
        envelopeAddCommand = null;
        envelopeCommand = null;
    }

}
