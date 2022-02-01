package com.github.sftwnd.crayfish.alarms.akka.timerange;

import akka.actor.typed.ActorRef;
import akka.actor.typed.ActorSystem;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import com.github.sftwnd.crayfish.alarms.timerange.TimeRangeConfig;
import com.github.sftwnd.crayfish.alarms.timerange.TimeRangeHolder;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static java.util.Optional.ofNullable;

@Slf4j
public class Perf {

    private static class Application extends AbstractBehavior<Object> {

        private static final long REQ_SEC = 1750; // 4500000
        private static final long ADD_COMMAND_BULK_SIZE = 125; //2500
        private static final int RANGE_DEPTH = 4; // Regions depth in the future
        private static final int RANGE_NR_OF_INSTANCES = 2; // Number of processors per range

        private static final AtomicLong firstTick = new AtomicLong();
        private static final AtomicInteger fired = new AtomicInteger();
        private static final AtomicInteger fires = new AtomicInteger();
        private static final AtomicLong delay = new AtomicLong();
        private static final AtomicLong reject = new AtomicLong();

        @AllArgsConstructor
        static class Generate {
            @Getter private final Instant startInstant;
            @Getter private final Instant endInstant;
        }

        final ActorRef<TimeRange.Command<Instant>> timeRangeProcessor;

        public Application(ActorContext<Object> context) {
            super(context);
            // Diapason descriptions
            TimeRangeConfig<Instant, Instant> config = TimeRangeConfig.create(
                    Duration.ofSeconds(180), // Length of range
                    Duration.ofMillis(15000), // Internal chunk size
                    Duration.ofMillis(250), // Refresh interval
                    Duration.ofSeconds(15),  // Wait interval for delayed messages
                    instant -> instant, // Take time marker from the message
                    null, // Use default comparator
                    TimeRangeHolder.ResultTransformer.identity() // Result is the same message
            );
            // Create and start service
            timeRangeProcessor = context.spawn(
                    Behaviors.setup(ctx ->
                            TimeRange.service(
                                    ctx,
                                    config,
                                    Application::firedElementsConsumer,
                                    Duration.ZERO,
                                    RANGE_DEPTH,
                                    RANGE_NR_OF_INSTANCES,
                                    // send request to generate region messages
                                    (startInstant, endInstant) -> context.getSelf().tell(new Generate(startInstant, endInstant))
                            )), "time-ranges"
            );
        }

        // Fired elements reaction (counters calculation)
        private static void firedElementsConsumer(@Nonnull Collection<Instant> elements) {
            long tick = Instant.now().toEpochMilli();
            firstTick.compareAndSet(0, Instant.now().toEpochMilli());
            fired.addAndGet(elements.size());
            fires.incrementAndGet();
            long add = elements.stream().map(Instant::toEpochMilli).map(firedTick -> tick - firedTick).reduce(Long::sum).orElse(0L);
            delay.addAndGet(add);
        }

        @Override
        public Receive<Object> createReceive() {
            return newReceiveBuilder()
                    .onMessage(Generate.class, this::onGenerate)
                    .build();
        }

        public static AtomicLong id = new AtomicLong();

        private boolean addElements(List<Instant> elements, long size, Generate generate) {
            if (elements.size() >= size) {
                Optional.of(elements).ifPresent(elm ->
                        // Send elements to the service
                        TimeRange.addElements(timeRangeProcessor,elm)
                                // Return rejects back
                                .whenComplete((rejects, throwable) -> informReject(elm, generate.startInstant, generate.endInstant, rejects, throwable))
                );
                return true;
            }
            return false;
        }

        private Behavior<Object> onGenerate(Generate generate) {
            logger.info("Generate for: {} - {}", generate.startInstant, generate.endInstant);
            long nanosPerRec = Math.round(1000000000.0D / REQ_SEC);
            List<Instant> elements = new ArrayList<>();
            for (Instant instant = generate.startInstant.isBefore(Instant.now().minusSeconds(1)) ? Instant.now().minusSeconds(1) :generate.startInstant;
                 instant.isBefore(generate.endInstant);
                 instant = instant.plusNanos(nanosPerRec)) {
                id.incrementAndGet();
                // Store generated elements
                elements.add(instant);
                // Try to send elements to the service (if more than ADD_COMMAND_BULK_SIZE)
                if (addElements(elements, ADD_COMMAND_BULK_SIZE, generate)) {
                    elements = new ArrayList<>();
                }
            }
            // Send existed elements to the service
            addElements(elements, 1, generate);
            return this;
        }

    }

    private static void informReject(List<Instant> elements, Instant startInstant, Instant endInstant, Collection<Instant> rejects, Throwable throwable) {
        ofNullable(throwable).ifPresentOrElse(
                 expt -> logger.error("Unable to add {} elements for region {} - {}. Cause: {}",
                         elements.size(), startInstant, endInstant, expt.getCause())
                ,() -> Application.reject.addAndGet(rejects.size())
        );
    }

    public static void main(String[] args) throws InterruptedException {
        logger.info("Main...");
        ActorSystem.create(Behaviors.setup(Application::new), "main");
        logMe();
    }

    private static void logMe() throws InterruptedException {
        long lastFired=0;
        long lastFires=0;
        long lastTick=0;
        long lastDelay=0;
        while(true) {
            long tick = Application.firstTick.get();
            if (tick != 0) {
                long generated = Application.id.get();
                long fired = Application.fired.get();
                long fires = Application.fires.get();
                long delay = Application.delay.get();
                long reject = Application.reject.get();
                Instant now = Instant.now();
                Instant startInstant = Instant.ofEpochMilli(tick);
                long nanos = Duration.between(startInstant, now).toNanos();
                double reqSeq = Math.round(1000000000.0D*fired/nanos*100.0D)/100.0D;
                long newFired = fired - lastFired;
                long newTicks = now.toEpochMilli() - lastTick;
                lastTick = now.toEpochMilli();
                double newReqSeq = lastTick == 0 || newTicks == 0 || newFired == 0 ? reqSeq : Math.round(100000.0D*newFired/newTicks)/100.0D;
                logger.info("Gen: {}, Reject: {}, Active: {}, fired: {}[{}], fires:{}[{}], req/sec: {}[{}], delay: {} sec",
                        lemons(generated),
                        lemons(reject),
                        lemons(generated-fired-reject),
                        lemons(fired-lastFired), lemons(fired),
                        fires-lastFires, fires,
                        newReqSeq,
                        reqSeq,
                        Math.round(100.0D * (delay-lastDelay)/(fired-lastFired)/1000.0D)/100.0D);
                lastFired = fired;
                lastFires = fires;
                lastDelay = delay;
            }
            Thread.sleep(1000);
        }
    }

    public static String lemons(long value) {
        if (value >= 10000) {
            return Math.round(100.0D*value/1000000)/100.0D+"M";
        } else {
            return String.valueOf(value);
        }
    }

}
