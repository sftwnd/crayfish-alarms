[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=sftwnd_crayfish_alarms&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=sftwnd_crayfish_alarms)
[![Coverage](https://sonarcloud.io/api/project_badges/measure?project=sftwnd_crayfish_alarms&metric=coverage)](https://sonarcloud.io/summary/new_code?id=sftwnd_crayfish_alarms)
[![TravisCI-build](https://app.travis-ci.com/sftwnd/crayfish-alarms.svg?branch=master)](https://app.travis-ci.com/github/sftwnd/crayfish-alarms/logscans)
[![License](https://img.shields.io/badge/License-BSD_3--Clause-blue.svg)](https://github.com/sftwnd/crayfish-alarms/blob/master/LICENSE)
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
3) A description of two methods that implement the interfaces [Expectation&lt;M,? extends TemporalAccessor&gt;](https://github.com/sftwnd/crayfish-common-expectation/blob/crayfish-common-expectation-1.0.0/src/main/java/com/github/sftwnd/crayfish/common/expectation/Expectation.java) to get the temporary marker from the object and [ITimeRangeFactory.Transformer&lt;M,R&gt;](./crayfish-alarms-timerange/src/main/java/com/github/sftwnd/crayfish/alarms/timerange/ITimeRange.java#L138-L155) to get the resulting object from the original one.

### Uniqueness Constraint
There is one TimeRange limitation: you cannot describe two objects at the same time that, when cast to the resulting object, will turn out to be equal.
To implement this restriction, the Comparator&lt;&gt; of the resulting object is used, which is used exactly in case of a match in time markers and allows you to filter out duplicates (perform a distinct operation)

### Creation of ITimeRangeFactory&lt;M,R&gt;
The **create**, **packable**, and **expected** factory methods are used to instantiate the [ITimeRangeFactory&lt;M,R&gt;](./crayfish-alarms-timerange/src/main/java/com/github/sftwnd/crayfish/alarms/timerange/ITimeRangeFactory.java) description.
#### method [create](./crayfish-alarms-timerange/src/main/java/com/github/sftwnd/crayfish/alarms/timerange/ITimeRangeFactory.java#L50-L69)
This is the most general method. When calling it, you must specify the **duration**, **interval**, **delay**, **completeTimeout**, **expectation**, **comparator** and **extractor** parameters described above.

```java
    ITimeRangeFactory<SourceObject,FiredObject> timeRangeFactory = ITimeRangeFactory.create(
            Duration.ofSeconds(180),
            Duration.ofMillis(15000),
            Duration.ofMillis(250),
            Duration.ofSeconds(15),
            Source2Package::transform
            obj::getFireTime,
            Package2Alarm::transform
            null
    );
```

#### method [packable](./crayfish-alarms-timerange/src/main/java/com/github/sftwnd/crayfish/alarms/timerange/ITimeRangeFactory.java#L106-L119)
This method allows you to create a ITimeRangeFactory described the TimeRange that takes [ExpectedPackage&lt;M,T extends TemporalAccessor&gt;](https://github.com/sftwnd/crayfish-common-expectation/blob/crayfish-common-expectation-1.0.0/src/main/java/com/github/sftwnd/crayfish/common/expectation/ExpectedPackage.java) as input elements, and the element contained in the specified package as result elements.
In this case, the **expectation** and **extractor** parameters are missing

```java
    ITimeRangeFactory<Expected<Instant>> timeRangeFactory = ITimeRangeFactory.packable(
        Duration.ofSeconds(10),
        Duration.ofMillis(1000),
        Duration.ofMillis(100),
        Duration.ofSeconds(3),
        StringToExpectedPackage::transform,
        String::compareTo
    );
```

#### method [expected](./crayfish-alarms-timerange/src/main/java/com/github/sftwnd/crayfish/alarms/timerange/ITimeRangeFactory.java#L131-L138)
This method also defines the **expectation** and **extractor** parameters itself and creates a [ITimeRangeFactory&lt;M,R&gt;](./crayfish-alarms-timerange/src/main/java/com/github/sftwnd/crayfish/alarms/timerange/ITimeRangeFactory.java) that has the same object at the input and output that implements the [Expected&lt;T extends TemporalAccessor&gt;](https://github.com/sftwnd/crayfish-common-expectation/blob/crayfish-common-expectation-1.0.0/src/main/java/com/github/sftwnd/crayfish/common/expectation/Expected.java) interface.

```java
    ITimeRangeFactory<Expected<Instant>, Expected<Instant>> timeRangeFactory = TimeRangeConfig.expected(
        Duration.ofSeconds(10),
        Duration.ofMillis(1000),
        Duration.ofMillis(100),
        Duration.ofSeconds(3),
        null
    );
```

#### method [temporal](./crayfish-alarms-timerange/src/main/java/com/github/sftwnd/crayfish/alarms/timerange/ITimeRangeFactory.java#L150-L157)
This method creates a [ITimeRangeFactory&lt;M,M&gt;](./crayfish-alarms-timerange/src/main/java/com/github/sftwnd/crayfish/alarms/timerange/ITimeRangeFactory.java) that has the same object at the input and output that reilize the &lt;M extends TemporalAccessor&gt; class.

```java
    ITimeRangeFactory<Instant,Instant> timeRangeFactory = ITimeRangeFactory.temporal (
            Duration.ofSeconds(SECONDS),
            Duration.ofMillis(333),
            Duration.ofSeconds(1),
            Instant::compareTo
    );
```

### ITimeRangeFacroty&lt;M,R&gt;

The creation of a physical region is implemented in the ITimeRangeFactory class.

#### Creation of ITimeRange

The ITimeRange instance is created by the [timeRange(TemporalAccessor)](./crayfish-alarms-timerange/src/main/java/com/github/sftwnd/crayfish/alarms/timerange/ITimeRangeFactory.java#L32) method. Those a time point is taken and, depending on the sign of the Duration parameter, the physical time range is described to the left or right of the time point using the TimeRangeConfig described above

```java
    ITimeRange<MyObject, NewObject> timeRange = timeRangeFactory.timeRange(Instant.now);
```

#### ITimeRegion attributes
ITimeRangeFactory has the following attributes, which can be obtained using the corresponding getter:
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
To add new elements, use the [addElements](./crayfish-alarms-timerange/src/main/java/com/github/sftwnd/crayfish/alarms/timerange/TimeRange.java#176#L185-L204) method:

```java
    List<MyObject> elements=getElements();
    List<MyObject> rejected=timeRange.addElements(elements);
```

If any elements do not fall within the specified region (excluding the right border), then these elements will be returned by the adElements method as not processed (rejected)

#### Getting triggered (fired) elements from a holder
To get fired elements, you need to call the [extractFiredElements](./crayfish-alarms-timerange/src/main/java/com/github/sftwnd/crayfish/alarms/timerange/TimeRange.java#L212-L233) method specifying the point in time at which you want to search for elements. If no point in time is specified, then the current one is used.
All fired elements will be extracted from the holder and returned as a result as a Collection of elements (unique if the timestamp matches).

```java
    Collection<NewObject> firedSet=timeRange.extractFiredElements(Instant.now.plusMillis(250));
```

---
Copyright © 2017-2022 Andrey D. Shindarev. All rights reserved.
