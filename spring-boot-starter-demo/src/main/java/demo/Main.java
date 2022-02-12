package demo;

import com.github.sftwnd.crayfish.alarms.akka.timerange.TimeRange;
import com.github.sftwnd.crayfish.alarms.akka.timerange.TimeRange.TimeRangeWakedUp;
import com.github.sftwnd.crayfish.alarms.akka.timerange.service.TimeRangeService;
import com.github.sftwnd.crayfish.alarms.timerange.TimeRangeHolder;
import com.github.sftwnd.crayfish.common.expectation.Expectation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;

import javax.annotation.Nonnull;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@SpringBootApplication
public class Main {

    private static final int LIMIT = 97;
    static CompletableFuture<Void> completionFuture = new CompletableFuture<>();

    private final AtomicInteger fires = new AtomicInteger(0);
    private final ApplicationContext applicationContext;

    public Main(@Autowired ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws InterruptedException {
        try (TimeRangeService<Instant> timeRangeService = SpringApplication.run(Main.class).getBean(TimeRangeService.class)) {
            timeRangeService.stopStage().thenAccept(ignored -> logger.warn("Service stopped..."));
            timeRangeService.completionStage().thenAccept(ignored -> logger.warn("Service completed..."));
            // Check for complete
            CountDownLatch finishCdl = new CountDownLatch(1);
            completionFuture.thenAccept(ignored -> finishCdl.countDown());
            finishCdl.await();
        }
    }

    @SuppressWarnings("unchecked")
    private TimeRangeService<Instant> getTimeRangeService() {
        return applicationContext.getBean(TimeRangeService.class);
    }

    @Bean TimeRangeWakedUp timeRangeWakedUp() {
        return (start, end) -> {
            logger.warn("Range started: "+start+" - "+ end);
            TimeRangeService<Instant> timeRangeService = this.getTimeRangeService();
            List<Instant> elements = new ArrayList<>();
            for (Instant instant = start; instant.isBefore(end); instant = instant.plusMillis(737)) {
                elements.add(instant);
                if (elements.size() >= 13) {
                    timeRangeService.addElements(elements);
                    elements = new ArrayList<>();
                }
            }
            if (!elements.isEmpty()) {
                timeRangeService.addElements(elements);
            }
        };
    }

    @Bean TimeRange.FiredElementsConsumer<Instant> firedElementsConsumer() {
        return elements -> {
            if (!completionFuture.isDone()) {
                logger.info("Fired elements: {}", elements);
                if (fires.addAndGet(elements.size()) >= LIMIT && !completionFuture.isDone()) {
                    logger.warn("Service is full!!!");
                    completionFuture.complete(null);
                }
            } else {
                logger.warn("Unable to process fired elements - target is full: {}", elements);
            }
        };
    }

    @Lazy
    @SuppressWarnings("unchecked")
    TimeRangeService<Instant> timeRangeService() {
        return applicationContext.getBean(TimeRangeService.class);
    }

    public static class ResultExtractor implements TimeRangeHolder.ResultTransformer<Instant,Instant> {
        public ResultExtractor() { logger.warn("TimeRangeHolder.ResultTransformer has been started as {}", this.getClass()); }
        @Nonnull @Override public Instant apply(@Nonnull Instant instant) { return instant; }
    }

    public static class InstantExpectation implements Expectation<Instant, Instant> {
        public InstantExpectation(@Autowired ApplicationContext applicationContext) {
            logger.warn("Expectation has been started as class {} in Application context: {}", this.getClass(), applicationContext);
        }
        @Nonnull @Override public Instant apply(@Nonnull Instant instant) { return instant; }
    }

}
