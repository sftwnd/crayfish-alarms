[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=sftwnd_crayfish_alarms&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=sftwnd_crayfish_alarms) [![Coverage](https://sonarcloud.io/api/project_badges/measure?project=sftwnd_crayfish_alarms&metric=coverage)](https://sonarcloud.io/summary/new_code?id=sftwnd_crayfish_alarms) ![CircleCI](https://img.shields.io/circleci/build/github/sftwnd/crayfish-alarms) [![License](https://img.shields.io/badge/License-BSD_3--Clause-blue.svg)](https://github.com/sftwnd/crayfish-alarms/blob/master/LICENSE)
# CrayFish Alarms

The library contains primitives that allow you to control the installation of tasks for processing on a schedule

## TimeRange

### Structure of TimeRange
![](img/TimeRange.svg)

#### duration
The main parameter of the time range is its duration. You cannot set the duration to zero. When a time range is built, two parameters are needed to determine its start and end: boundary and duration. If the duration is negative, then the time range is built to the left of the border, and if positive, then to the right.

#### completeTimeout
The completeTimeout interval describes a delay that describes the time interval during which, after the current moment of time has crossed the extreme TimeRegion boundary, the ability to access object data through api is guaranteed to remain.
That is, the object will be present in memory and open to calls.

#### interval
The entire TimeRange is divided into sub-ranges with the duration specified by the interval parameter.
For each subrange, a separate structure for storing elements marked with a time-marker is allocated, which just falls into the specified subrange.

#### delay
When accessing TimeRange, you can get information about the interval until the nearest event, however, if this is not the end of the range lifetime, then this value will be limited from below by the delay parameter. Thus, the _'delay'_ parameter describes the minimum delay between repeated calls to TimeRange.

### Supported items
The implemented library allows you to store elements that have a time marker inside the TimeRange and retrieve at each moment of time those objects that have a time marker earlier than the current moment. A temporary marker can be attached to an object in three ways:
1) An implementation of the [Expected&lt;T extends TemporalAccessor&gt;](https://github.com/sftwnd/crayfish-common-expectation/blob/crayfish-common-expectation-1.0.0/src/main/java/com/github/sftwnd/crayfish/common/expectation/Expected.java) interface, where the token is obtained by calling the getTick() method
2) Packing the element into an object that implements the [ExpectedPackage&lt;M,T extends TemporalAccessor&gt;](https://github.com/sftwnd/crayfish-common-expectation/blob/crayfish-common-expectation-1.0.0/src/main/java/com/github/sftwnd/crayfish/common/expectation/ExpectedPackage.java) interface, which also supports the [Expected&lt;T extends TemporalAccessor&gt;](https://github.com/sftwnd/crayfish-common-expectation/blob/crayfish-common-expectation-1.0.0/src/main/java/com/github/sftwnd/crayfish/common/expectation/Expected.java) interface, and the element itself is obtained by calling the getElemen() method
3) A description of two methods that implement the interfaces [Expectation&lt;M,? extends TemporalAccessor&gt;](https://github.com/sftwnd/crayfish-common-expectation/blob/crayfish-common-expectation-1.0.0/src/main/java/com/github/sftwnd/crayfish/common/expectation/Expectation.java) to get the temporary marker from the object and [TimeRangeHolder.ResultTransformer&lt;M,R&gt;](./crayfish-alarms-timerange/src/main/java/com/github/sftwnd/crayfish/alarms/timerange/TimeRangeHolder.java#L48-L60) to get the resulting object from the original one.

### Uniqueness Constraint
There is one TimeRange limitation: you cannot describe two objects at the same time that, when cast to the resulting object, will turn out to be equal.
To implement this restriction, the Comparator&lt;&gt; of the resulting object is used, which is used exactly in case of a match in time markers and allows you to filter out duplicates (perform a distinct operation)

### Creation of TimeRangeConfig&lt;M,R&gt;
The **create**, **packable**, and **expected** factory methods are used to instantiate the [TimeRangeConfig&lt;M,R&gt;](./crayfish-alarms-timerange/src/main/java/com/github/sftwnd/crayfish/alarms/timerange/TimeRangeConfig.java) description.
#### method [create](./crayfish-alarms-timerange/src/main/java/com/github/sftwnd/crayfish/alarms/timerange/TimeRangeConfig.java#L96-L106)
This is the most general method. When calling it, you must specify the **duration**, **interval**, **delay**, **completeTimeout**, **expectation**, **comparator** and **extractor** parameters described above.

```java
    TimeRangeConfig<MyObject, NewObject> config = TimeRangeConfig.create(
            Duration.ofSeconds(180),
            Duration.ofMillis(15000),
            Duration.ofMillis(250),
            Duration.ofSeconds(15),
            obj::getFireTime,
            null,
            Transformer::transform
    );
```

#### method [packable](./crayfish-alarms-timerange/src/main/java/com/github/sftwnd/crayfish/alarms/timerange/TimeRangeConfig.java#L144-L154)
This method allows you to create a TimeRangeConfig described the TimeRange that takes [ExpectedPackage&lt;M,T extends TemporalAccessor&gt;](https://github.com/sftwnd/crayfish-common-expectation/blob/crayfish-common-expectation-1.0.0/src/main/java/com/github/sftwnd/crayfish/common/expectation/ExpectedPackage.java) as input elements, and the element contained in the specified package as result elements.
In this case, the **expectation** and **extractor** parameters are missing

```java
    TimeRangeConfig<ExpectedPackage<String, Instant>, String> config = TimeRangeConfig.packable(
        Duration.ofSeconds(10),
        Duration.ofMillis(1000),
        Duration.ofMillis(100),
        Duration.ofSeconds(3),
        String::compareTo
    );
```

#### method [expected](./crayfish-alarms-timerange/src/main/java/com/github/sftwnd/crayfish/alarms/timerange/TimeRangeConfig.java#L167-L175)
This method also defines the **expectation** and **extractor** parameters itself and creates a [TimeRangeConfig&lt;M,R&gt;](./crayfish-alarms-timerange/src/main/java/com/github/sftwnd/crayfish/alarms/timerange/TimeRangeConfig.java) that has the same object at the input and output that implements the [Expected&lt;T extends TemporalAccessor&gt;](https://github.com/sftwnd/crayfish-common-expectation/blob/crayfish-common-expectation-1.0.0/src/main/java/com/github/sftwnd/crayfish/common/expectation/Expected.java) interface.

```java
    TimeRangeConfig<Expected<Instant>, Expected<Instant>> config = TimeRangeConfig.expected(
        Duration.ofSeconds(10),
        Duration.ofMillis(1000),
        Duration.ofMillis(100),
        Duration.ofSeconds(3),
        null
    );
```

### TimeRangeHolder&lt;M,R&gt;
The creation of a physical region is implemented by the TimeRangeHolder class.
#### Creation of TimeRegionHolder
The TimeRangeHolder instance is created by the [timeRangeHolder(TemporalAccessor)](./crayfish-alarms-timerange/src/main/java/com/github/sftwnd/crayfish/alarms/timerange/TimeRangeConfig.java#L77-L79) method. Those a time point is taken and, depending on the sign of the Duration parameter, the physical time range is described to the left or right of the time point using the TimeRangeConfig described above

```java
    TimeRangeConfig<MyObject, NewObject> config = ...
    TimeRangeHolder<MyObject, NewObject> timeRange = config.timeRangeHolder(Instant.now);
```

#### TimeRegionHolder attributes
TimeRangeHolder has the following attributes, which can be obtained using the corresponding getter:
* startInstant - Left border of the time range described by the holder
* lastInstant - Right border of the time range described by the holder
* interval - Corresponds to the Time Range **interval** parameter we described earlier
* delay - Corresponds to the Time Range **delay** parameter we described earlier
* completeTimeout - Corresponds to the Time Range **completeTimeout** parameter we described earlier

There are also three additional methods:
* isExpired() - true if the time specified by the parameter or the current time (if the parameter is not set) is greater than lastInstant by at least completeDuration
* isComplete() - true if expired and there is no element inside the holder
* duration() - get time interval until the next event occurs from the moment specified by the parameter, or from the current moment if the parameter is not set

#### Adding new elements to the holder
To add new elements, use the [addElements](./crayfish-alarms-akka/crayfish-alarms-akka-timerange/src/main/java/com/github/sftwnd/crayfish/alarms/akka/timerange/TimeRange.java#L263-L326) method:

```java
    List<MyObject> elements=getElements();
    List<MyObject> rejected=timeRange.addElements(elements);
```

If any elements do not fall within the specified region (excluding the right border), then these elements will be returned by the adElements method as not processed (rejected)

#### Getting triggered (fired) elements from a holder
To get fired elements, you need to call the [extractFiredElements](./crayfish-alarms-timerange/src/main/java/com/github/sftwnd/crayfish/alarms/timerange/TimeRangeHolder.java#L207-L242) method specifying the point in time at which you want to search for elements. If no point in time is specified, then the current one is used.
All fired elements will be extracted from the holder and returned as a result as a Collection of elements (unique if the timestamp matches).

```java
    Collection<NewObject> firedSet=timeRange.extractFiredElements(Instant.now.plusMillis(250));
```

## TimeRegion Processing
Automation of time range processing is implemented by the [TimeRange](./crayfish-alarms-akka/crayfish-alarms-akka-timerange/src/main/java/com/github/sftwnd/crayfish/alarms/akka/timerange/TimeRange.java) interface components.
Implemented the ability to automatically process a specific time range, or a dynamically changing set of ranges moving along the time scale.
To run the handlers, the [AKKA Typed ActorSystem](https://doc.akka.io/docs/akka/current/index.html) from [Lightbend Inc.](https://www.lightbend.com/) is used.

### Single Time Range Processing
AKKA Benavior's RangeProcessor<M,R> is implemented to process one particular time range. 

#### Run a handler for a specific time range
The launch is performed by the TimeRange.processor method with a description of the following parameters:
* time - the border of the temporary region. Set by analogy with TimeRangeHolder.
* timeRangeConfig - see above description of TimeRangeConfig
* firedConsumer - Consumer, which will be called if elements that have fired at the current time are found. Consumer interface: [FiredElementsConsumer&lt;R&gt;](./crayfish-alarms-akka/crayfish-alarms-akka-timerange/src/main/java/com/github/sftwnd/crayfish/alarms/akka/timerange/TimeRange.java#L95-L98)
* withCheckDuration - Time shift, which allows you to search for elements not exactly at the current moment, but with a slight offset.

Behavior definition:

```java
    Behavior<Command<MyObject> behavior = TimeRange.processor(now, timeRangeConfig, firedElementConsumer, null);
```

Creation of processor in the ActorContext:

```java
    ActorRef<Command<MyObject>> timeRangeProcessor = context.spawn(behavior,"timeRangeProcessor"); 
```

Creation of processor as ActorSystem:

```java
    ActorSystem<Command<MyObject>> timeRangeProcessor = ActorSystem.create(behavior, "timeRangeProcessor");
```

#### Add elements to TimeRegion Processor
To add elements to the TimeRange Processor, use the [TimeRange.addElements](./crayfish-alarms-akka/crayfish-alarms-akka-timerange/src/main/java/com/github/sftwnd/crayfish/alarms/akka/timerange/TimeRange.java#L263-L326) factory method.
The method contains three parameters:
* ActorRef&lt;Command&lt;M&gt;&gt; timeRangeActor - Service to which we add elements
* Collection&lt;M&gt; elements - Collection of elements to add
* CompletableFuture<Collection<M>> completableFuture - CompletableFuture, where the result of adding elements will be placed

```java
    CompletableFuture<MyObject> rejectedFuture = new CompletableFuture<>;
    TimeRange.addElements(timeRangeProcessor, elements, rejectedFuture);
```

You can also call [TimeRange.addElements](./crayfish-alarms-akka/crayfish-alarms-akka-timerange/src/main/java/com/github/sftwnd/crayfish/alarms/akka/timerange/TimeRange.java#L263-L326) as a function without the CompletableFuture parameter. Then the function itself will create a CompletionStage and return it as a result.

```java
    CompletionStage<MyObject> rejectedFuture = TimeRange.addElements(timeRangeProcessor, elements);
```

If the elements fall within the range described by the TimeRange of Processor, then they are stored inside for further processing. If they do not, then a list of such elements will be sent to the CompletionStage as the result of the operation.
#### Handling triggered messages
If at the current moment of time, taking into account the shift, triggered elements are found, then the collection of these elements is sent for processing by calling the callback method ([firedElementConsumer](./crayfish-alarms-akka/crayfish-alarms-akka-timerange/src/main/java/com/github/sftwnd/crayfish/alarms/akka/timerange/TimeRange.java#L95-L98)), passing the collection there as a parameter.
#### Stop processing
When the point in time arrives at which the isComplete operation applied to the TimeRegionHolder in the processor yields a positive result, then processing of the region is stopped and the service is terminated.
#### Determining when the processor has stopped
To determine the moment when the processor stops, you can use a subscription to the AKKA Actor with the processing of the signal to stop the processor actor.

```java
    ...
       context.watch(timeRangeProcessor);
    ...
    public Receive<Command<M>> createReceive() {
        return ...
                .onSignal(Terminated.class, this::onTerminate)
               ...;
    }
```

### Time Range Processing Service
The Time Range Processing Service, unlike the Single Time Range Processor, processes incoming elements not for a fixed range, but for a dynamically moving window of TimeRange ranges, each of which is implemented using the TimeRange Processor.
#### Service creation
The service is created using the factory method [TimeRange.service](./crayfish-alarms-akka/crayfish-alarms-akka-timerange/src/main/java/com/github/sftwnd/crayfish/alarms/akka/timerange/TimeRange.java#L409-L419)

```java
    timeRangeProcessor = context.spawn(
        Behaviors.setup(ctx ->
            TimeRange.service(
                ctx,
                config,
                target::firedElementsConsumer,
                Duration.ZERO,
                rangeDepth,
                nrOfInstances,
                (startInstant, endInstant) -> context.getSelf().tell(new RangeStarted(startInstant, endInstant))
            )), "time-range-service"
    );
```

This method has the same parameters as the processor creation method, except for the time parameter, which is missing.
When a service is created, the processor starts, for the calculated moment from the current time, rounded down to the value of duration.

The service build method contains three additional parameters:
* _**rangeDepth**_ - the number of additional TimeRanges that will be created for the future and following the current one sequentially, without time gaps
* _**nrOfInstances**_ - number of message handlers for each TimeRegion created. Recommended at least one
* _**[timeRangeWakedUp](./crayfish-alarms-akka/crayfish-alarms-akka-timerange/src/main/java/com/github/sftwnd/crayfish/alarms/akka/timerange/TimeRange.java#L404-L407)**_ - the method that will be called when creating the TimeRegion handler

#### Add elements to TimeRegion Service
This method corresponds to the method of adding to the processor with the only limitation that when adding an element, a TimeRegion is determined that corresponds to the moment of the element and is added there. If TimeRegion is absent or rejects an element, then such elements are sent as rejected

#### Stop service processing
To stop a service, you must use the AKKA API to stop the actor or AkkaSystem that implements the service.

```java
    ActorSystem<Command<MyObject>> timeRangeService = ActorSystem.create(..., "time-range-service");
    ...
    timeRangeService.terminate();
```

```java
    ActorRef<Command<MyObject>> timeRangeService = context.spawn(...,"time-range-service");
    ...
    context.stop(timeRangeService);
```

#### Usage of [TimeRange.Mailbox](./crayfish-alarms-akka/crayfish-alarms-akka-timerange/src/main/java/com/github/sftwnd/crayfish/alarms/akka/timerange/TimeRange.java#L651-L680)
All request processing in the AKKA Actor is done by sequentially reading messages from the mailbox.
When processing messages in the TimeRange Service, they pass through one Mailbox - both messages for adding elements, and messages for creating a TimeRange Processor.
In order not to delay messages about the creation of a new TimeRange Processor, it is recommended to use a prioritized TimeRange.Mailbox.
More details about using mailbox can be found in the [AKKA documentation](https://doc.akka.io/docs/akka/current/typed/mailboxes.html).

### DeadLetters
In the event that the TimeRange service actor is stopped and messages are sent to it via addElements, the CompletionStage received as a result of the call will hang incomplete, since there will be no one to process the created message for adding elements.
To prevent this from happening, you need to create an actor using behavior, which is built by the TimeRange.timeRangeDeadLetterSubscriber function:

```java
    var behavior = TimeRange.timeRangeDeadLetterSubscriber(timeRangeActor);
    var deadLetterActor = context.spawn(behavior, "dead-letter-actor");
```

Then you need to subscribe the created actor to the DeadLetter messages appearing in the ActorSystem:

```java
    context.system().eventStream().tell(new EventStream.Subscribe<>(DeadLetter.class, deadLetterActor));
```

TimeRange Service for internal actors creates a subscriber to deadLetter, however, those messages that are sent to the TimeRange Service, in turn, will also be lost if the actor implementing the service crashes, and it is also desirable to subscribe to it.

### Stop service and processor

TimeRange.stop method created to stop service or processor.
When the method is called, a CompletionStage<Void> is returned, which is executed as soon as the corresponding actor is stopped:

```java
    TimeRange.stop(timeRangeActor)
        .thenAccept(
                ignore -> logger.info("Service has been stopped...");
        );
```

## Time Range Service interface

TimeRegion handling can be run independently of AKKA by hiding its use inside the api. For this, the TimeRangeService interface is used (implemented in the _**crayfish-alarms-akka-timerange-service**_ module).

```xml
    <parent>
        <groupId>com.github.sftwnd.crayfish.alarms</groupId>
        <artifactId>crayfish-alarms-akka-timerange-service</artifactId>
    </parent>
```

To create a service, you must first describe it by implementing the TimeRangeService. Configuration configuration.

### Service Configuration

```java
    return new TimeRangeService.Configuration() {
        @Nonnull @Override public String getServiceName() { return "test-TimeRangeServiceTest"; }
        @Nonnull @Override public Duration getDuration() { return Duration.ofSeconds(30); }
        @Nonnull @Override public Duration getInterval() { return Duration.ofSeconds(1); }
        @Nonnull @Override public Duration getDelay() { return Duration.ofMillis(125); }
        @Nonnull @Override public Duration getCompleteTimeout() { return Duration.ofSeconds(3); }
        @Override public Duration getWithCheckDuration() { return Duration.ofSeconds(0); }
        @Override public Integer getTimeRangeDepth() { return 3; }
        @Override public Integer getNrOfInstances() { return 1; }
        @Override public Duration getDeadLetterTimeout() { return Duration.ofSeconds(1); }
        @Override public Duration getDeadLetterCompleteTimeout() { return Duration.ofSeconds(1); }
        @Override public Config getAkkaConfig() { return null; }
        @Override public TimeRange.TimeRangeWakedUp getRegionListener() { return regionListener; }
        @Override public Comparator<M> getComparator() { return null; }
        @Override public Expectation<M, T> getExpectation() { return (Expectation<M, T>)expectation; }
        @Override public TimeRangeHolder.ResultTransformer<M, R> getExtractor() { return extractor; }
        @Override public TimeRange.FiredElementsConsumer<R> getFiredConsumer() { return firedElementsConsumer; }
    };
```

### Service Factory

_FYI: There is an default implementation of the configuration interface: [TimeRangeServiceConfiguration](./crayfish-alarms-akka/crayfish-alarms-akka-timerange-service/src/main/java/com/github/sftwnd/crayfish/alarms/akka/timerange/service/TimeRangeServiceConfiguration.java)._

The next step is to create a factory from the Configuration interface.

```java
        this.timeRangeServiceFactory = TimeRangeService.serviceFactory(configuration);
```

If the Configuration contains an incomplete set of attributes, then the factory creation method throws an exception: [TimeRangeService.ConfigurationException](./crayfish-alarms-akka/crayfish-alarms-akka-timerange-service/src/main/java/com/github/sftwnd/crayfish/alarms/akka/timerange/service/TimeRangeService.java#L96-L100).

### Time Range Service creation

The factory contains the main parameters of the service and to create it, just call the method: [TimeRangeService.ServiceFactory::timeRangeService(serviceName)](./crayfish-alarms-akka/crayfish-alarms-akka-timerange-service/src/main/java/com/github/sftwnd/crayfish/alarms/akka/timerange/service/TimeRangeService.java#L163)

```java
    timeRangeServiceFactory.timeRangeService("time-range-service");
```

As a result, you will get an object that implements the TimeRangeService interface:

```java
public interface TimeRangeService<M> extends AutoCloseable {
    // Send new elements to TimeService with rejects in CompletionStage result
    @Nonnull CompletionStage<Collection<M>> addElements(@Nonnull Collection<M> elements);
    // Getting the CompletionStage to be executed after the TimeRange Service is stopped
    @Nonnull CompletionStage<Void> stopStage();
    // Getting the CompletionStage to be executed after the TimeRange Service and DeadLetter processing are stopped
    @Nonnull CompletionStage<Void> completionStage();
    @Nonnull CompletionStage<Void> stop();
    // Stop the TimeRange Service and DeadLetter processing
    @Nonnull CompletionStage<Void> complete();
    // Stop the TimeRange Service and DeadLetter processing and wait for completion
    void close();
    ...
}
```

TimeRangeService allows you to send a set of objects marked with a temporary marker to it, according to the configuration passed to the factory, and also makes it possible to track the stop of processing incoming elements, stop the service as a whole, as well as closing it both separately by the stop method with monitoring of the required closing phase, and by the close waiting for a full stop

For example:
```java
    // With AutoClosable
    try(TimeRange<Event> timeRange = timeRangeServiceFactory.timeRangeService("time-range-service")) {
        timeRange.addElements(elements).thenAccept(this::onRejects);
        ...
    }
```

```java
    TimeRange<Event> timeRange = timeRangeServiceFactory.timeRangeService("time-range-service")) {
    ...
    timeRange.addElements(elements).thenAccept(this::onRejects);
    ...
    CountDownLatch completeLatch = new CountDownLatch(1);
    timeRange.stop().thenAccept(completeLatch.countDown);
    completeLatch.awit(...);
```

### Back to AKKA

When the service starts, an AkkaSystem<> is implicitly created, but its use is hidden behind the api.
If there is a desire to use only one AKKA System in a project, then you can use the [timeRangeService function fo Behavior<>](crayfish-alarms-akka/crayfish-alarms-akka-timerange-service/src/main/java/com/github/sftwnd/crayfish/alarms/akka/timerange/service/TimeRangeService.java#L190-L194) that implements this service, so although we hid AKKA behind api, we still retained access to enable finer integration of the service into applications running over AKKA Platform.

```java
  private Behavior<TimeRangeServiceCommand> timeRangeService(
          @Nonnull final CompletableFuture<Function<Collection<M>,CompletionStage<Collection<M>>>> addElementsFunctionFuture,
          @Nullable final CompletableFuture<Void> stopFuture,
          @Nullable final CompletableFuture<Void> completeFuture
  )
```

When the actor implementing this Behavior<> starts, a function will be placed in the corresponding addElementsFunctionFuture to send elements to the TimeRegionService.
It also accepts CompletableFuture: stopFuture and completeFuture, which will be used to react to the actor's stop phases

The generated Behavior<> can be used to create a service inside the actor or AkkaSystem of your application, [similar to how it is implemented]((./crayfish-alarms-akka/crayfish-alarms-akka-timerange-service/src/main/java/com/github/sftwnd/crayfish/alarms/akka/timerange/service/TimeRangeService.java#L170) in the [timeRangeService function](./crayfish-alarms-akka/crayfish-alarms-akka-timerange-service/src/main/java/com/github/sftwnd/crayfish/alarms/akka/timerange/service/TimeRangeService.java#L163-L187) to create a TimeRangeService.

```java
    CompletableFuture<Void> stopFuture = new CompletableFuture<>();
    CompletableFuture<Void> completeFuture = new CompletableFuture<>();
    CompletableFuture<Function<Collection<M>,CompletionStage<Collection<M>>>> addElementsFunctionFuture = new CompletableFuture<>();
    ActorSystem<TimeRangeServiceCommand> timeRangeService =
        getContext().spawn(
            timeRangeService(
                addElementsFunctionFuture,
                stopFuture,
                completeFuture
            ),
            "time-range-service"
        );
    Function<Collection<M>,CompletionStage<Collection<M>>> addElementsFunction = addElementsFunctionFuture.join();            
```

## Time Range Service Spring Boot starter

### Starter module
TimeRangeService Spring Boot starter is realized in module: _**crayfish-alarms-spring-boot-timerange-service-starter**_.

```xml
    <dependency>
        <groupId>com.github.sftwnd.crayfish.alarms</groupId>
        <module>crayfish-alarms-spring-boot-timerange-service-starter</module>
    </dependency>
```

### Configuration parameters

#### Required parameters

To start the service, just specify the following configuration parameters:
* _crayfish.alarms.time-range-service.fired-consumer_
* _crayfish.alarms.time-range-service.region-listener_
* _crayfish.alarms.time-range-service.expectation_
* _crayfish.alarms.time-range-service.extractor_

_The values are either the name of the bean that implements the corresponding interface, or the class (with the addition of .class)_

#### Available parameters

```properties
    # Here are the default parameter values, except for the required ones mentioned above.
    crayfish.alarms.time-range-service.region-listener=my-regionListener
    crayfish.alarms.time-range-service.expectation=my-expectation
    crayfish.alarms.time-range-service.fired-consumer=my-firedElementsConsumer
    crayfish.alarms.time-range-service.extractor=com.github...MyExtractor.class
    crayfish.alarms.time-range-service.service-name=time-range-service
    crayfish.alarms.time-range-service.duration=1m
    crayfish.alarms.time-range-service.interval=1s
    crayfish.alarms.time-range-service.delay=125
    crayfish.alarms.time-range-service.complete-timeout=12s
    crayfish.alarms.time-range-service.dead-letter-timeout=3s
    crayfish.alarms.time-range-service.dead-letter-complete-timeout=250
    crayfish.alarms.time-range-service.time-range-depth=2
    crayfish.alarms.time-range-service.nr-of-instances=1
    crayfish.alarms.time-range-service.with-check-duration=0
    # crayfish.alarms.time-range-service.comparator=
    crayfish.alarms.time-range-service.akka-config=
```

### Demonstration example 

```java
import com.github.sftwnd.crayfish.alarms.akka.timerange.TimeRange;
import com.github.sftwnd.crayfish.alarms.akka.timerange.TimeRange.TimeRangeWakedUp;
import com.github.sftwnd.crayfish.alarms.akka.timerange.service.TimeRangeService;
import com.github.sftwnd.crayfish.alarms.timerange.TimeRangeHolder;
import com.github.sftwnd.crayfish.common.expectation.Expectation;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@SpringBootApplication
public class Main {

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws InterruptedException {
        System.setProperty("spring.main.banner-mode", "off");
        System.setProperty("crayfish.alarms.time-range-service.fired-consumer", "firedElementsConsumer");
        System.setProperty("crayfish.alarms.time-range-service.region-listener", "timeRangeWakedUp");
        System.setProperty("crayfish.alarms.time-range-service.expectation", "expectation");
        System.setProperty("crayfish.alarms.time-range-service.extractor", "extractor");
        ApplicationContext applicationContext = SpringApplication.run(Main.class);
        try (TimeRangeService<Instant> timeRangeService = applicationContext.getBean(TimeRangeService.class)) {
            timeRangeService.completionStage().thenAccept(ignored -> System.out.println("Completed..."));
            System.out.println("TimeRangeService: "+timeRangeService);
            Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
            timeRangeService.addElements(List.of(
                    now.minusSeconds(1), now, now.plusSeconds(2), now.plusSeconds(5), now.plus(1, ChronoUnit.HOURS)
            )).thenAccept(rejected -> System.out.println("Rejected: "+ rejected));
            Thread.sleep(7000);
        }
    }

    @Bean TimeRangeWakedUp timeRangeWakedUp() { return (start, end) -> System.out.println("Range started: "+start+" - "+ end); }

    @Bean Expectation<Instant, Instant> expectation() { return instant -> instant; }

    @Bean TimeRange.FiredElementsConsumer<Instant> firedElementsConsumer() { return elements -> System.out.println("Fired elements: "+ elements); }

    @Bean TimeRangeHolder.ResultTransformer<Instant,Instant> extractor() { return instant -> instant; }

}
```

---
Copyright © 2017-2022 Andrey D. Shindarev. All rights reserved.