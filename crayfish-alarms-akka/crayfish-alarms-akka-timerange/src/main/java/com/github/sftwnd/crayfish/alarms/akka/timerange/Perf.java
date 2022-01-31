package com.github.sftwnd.crayfish.alarms.akka.timerange;

import akka.actor.typed.ActorRef;
import akka.actor.typed.ActorSystem;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import com.github.sftwnd.crayfish.alarms.timerange.TimeRangeItems;
import com.github.sftwnd.crayfish.alarms.timerange.TimeRangeItems.Config;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
public class Perf {

    private static class Application extends AbstractBehavior<Object> {

        private static final long REQ_SEC = 1500000; // 4500000
        private static final long ADD_COMMAND_BULK_SIZE = 750; //2500
        private static final int RANGE_DEPTH = 2; // Поднимать на будущее 2 range (!!! 0 не рекомендуется - будут отставания и не будет gap на загрузку)
        private static final int RANGE_NR_OF_INSTANCES = 10; // По 10 обработчиков на диапазон

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
            Config<Instant, Instant>  config = Config.create(
                    Duration.ofSeconds(10), Duration.ofMillis(500), Duration.ofMillis(125), Duration.ofSeconds(3),
                    instant -> instant, null, TimeRangeItems.ResultTransformer.identity()
            );
            TimeRange.FiredElementsConsumer<Instant> firedElementsConsumer = elements -> {
                long tick = Instant.now().toEpochMilli();
                firstTick.compareAndSet(0, Instant.now().toEpochMilli());
                fired.addAndGet(elements.size());
                fires.incrementAndGet();
                long add = elements.stream().map(Instant::toEpochMilli).map(firedTick -> tick - firedTick).reduce(Long::sum).orElse(0L);
                delay.addAndGet(add);
            };
            TimeRange.TimeRangeWakedUp timeRangeWakedUp = (startInstant, endInstant) -> context.getSelf().tell(new Generate(startInstant, endInstant));
            timeRangeProcessor = context.spawn(
                    Behaviors.setup(ctx ->
                            new TimeRange.TimeRangeAutomaticProcessor<>(
                                    ctx,
                                    config,
                                    firedElementsConsumer,
                                    Duration.ZERO,
                                    RANGE_DEPTH,
                                    RANGE_NR_OF_INSTANCES,
                                    timeRangeWakedUp
                            )), "time-ranges"
            );
        }

        @Override
        public Receive<Object> createReceive() {
            return newReceiveBuilder()
                    .onMessage(Generate.class, this::onGenerate)
                    .build();
        }

        public static AtomicLong id = new AtomicLong();

        private Behavior<Object> onGenerate(Generate generate) {
            logger.info("Generate for: {} - {}", generate.startInstant, generate.endInstant);
            long nanosPerRec = Math.round(1000000000.0D / REQ_SEC);
            List<Instant> elements = new ArrayList<>();
            for (Instant instant = generate.startInstant.isBefore(Instant.now().minusSeconds(1)) ? Instant.now().minusSeconds(1) :generate.startInstant;
                 instant.isBefore(generate.endInstant);
                 instant = instant.plusNanos(nanosPerRec)) {
                id.incrementAndGet();
                elements.add(instant);
                if (elements.size() >= ADD_COMMAND_BULK_SIZE) {
                    TimeRange.addElements(timeRangeProcessor,elements).whenComplete(
                            (reject, throwable) -> {
                                if (throwable != null) {
                                    logger.error("Unable to add columns for region {} - {}: {}", generate.startInstant, generate.endInstant, throwable.getCause());
                                } else {
                                    Application.reject.addAndGet(reject.size());
                                }
                            }
                );
                    elements = new ArrayList<>();
                }
            }
            if (elements.size() > 0) {
                TimeRange.addElements(timeRangeProcessor,elements);
            }
            return this;
        }

    }

    public static String lemons(long value) {
        if (value >= 10000) {
            return Math.round(100.0D*value/1000000)/100.0D+"M";
        } else {
            return String.valueOf(value);
        }
    }

    public static void main(String[] args) throws InterruptedException {
        logger.info("Main...");
        /*ActorSystem<?> actorSystem =*/ ActorSystem.create(Behaviors.setup(Application::new), "main");
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

}
