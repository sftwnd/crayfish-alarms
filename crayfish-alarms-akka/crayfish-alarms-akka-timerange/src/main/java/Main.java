import akka.actor.DeadLetter;
import akka.actor.typed.ActorSystem;
import akka.actor.typed.Behavior;
import akka.actor.typed.eventstream.EventStream;
import akka.actor.typed.javadsl.Behaviors;
import com.github.sftwnd.crayfish.alarms.akka.timerange.TimeRange;
import com.github.sftwnd.crayfish.alarms.timerange.TimeRangeConfig;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;

@Slf4j
public class Main {

    public static void main(String[] args) throws InterruptedException {
        TimeRangeConfig<Instant, Instant> config = TimeRangeConfig.create(
                Duration.ofSeconds(15),
                Duration.ofSeconds(1),
                Duration.ofMillis(125),
                Duration.ofSeconds(2),
                instant -> instant,
                null,
                instant -> instant
        );
        TimeRange.FiredElementsConsumer<Instant> firedConsumer = instant -> logger.info("Fired: {}", instant);
        TimeRange.TimeRangeWakedUp timeRangeWakedUp = (start, end) -> logger.info("Region started: {} - {}", start, end);
        Behavior<TimeRange.Command<Instant>> serviceBehavior = Behaviors.setup(context ->
                TimeRange.service(context, config, firedConsumer, Duration.ZERO, 1, 1, timeRangeWakedUp)
        );
        ActorSystem<TimeRange.Command<Instant>> serviceSystem = ActorSystem.create(serviceBehavior, "time-range-service");
        Behavior<DeadLetter> deadLetterBehavior = TimeRange.timeRangeDeadLetterSubscriber(serviceSystem);
        ActorSystem<DeadLetter> deadLetterSystem = ActorSystem.create(deadLetterBehavior, "dead-letter-processor");
        serviceSystem.eventStream().tell(new EventStream.Subscribe<>(DeadLetter.class, deadLetterSystem));
        Instant now = Instant.now();
        TimeRange.addElements(serviceSystem, List.of(now.minusSeconds(1), now, now.plusSeconds(1), now.plusSeconds(3600))).thenAccept(elements -> logger.info("rejected: {}", elements));
        Thread.sleep(2000);
        CountDownLatch stopLatch = new CountDownLatch(1);
        TimeRange.stop(serviceSystem).thenAccept(ignore -> stopLatch.countDown());
        stopLatch.await();
        logger.info("Terminated...");
        now = Instant.now();
        TimeRange.addElements(serviceSystem, List.of(now.minusSeconds(1), now, now.plusSeconds(1), now.plusSeconds(3600))).thenAccept(elements -> logger.info("rejected: {}", elements));
        deadLetterSystem.terminate();
    }

}
