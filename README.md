[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=sftwnd_crayfish_alarms&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=sftwnd_crayfish_alarms)
[![Coverage](https://sonarcloud.io/api/project_badges/measure?project=sftwnd_crayfish_alarms&metric=coverage)](https://sonarcloud.io/summary/new_code?id=sftwnd_crayfish_alarms)
[![Build](https://github.com/sftwnd/crayfish-alarms/actions/workflows/build.yml/badge.svg)](https://github.com/sftwnd/crayfish-alarms/actions/workflows/build.yml)
[![License](https://img.shields.io/badge/License-BSD_3--Clause-blue.svg)](https://github.com/sftwnd/crayfish-alarms/blob/master/LICENSE)

# CrayFish Alarms

The library contains primitives that allow you to control the installation of tasks for processing on a schedule

## TimeRange

### Structure of TimeRange

![](img/TimeRange.svg)

#### duration

The main parameter of the time range is its duration. A duration of zero gives an empty time range: its start is its end, so it rejects every element, and it is complete after its completeTimeout. When a time range is built, two parameters are needed to determine its start and end: boundary and duration. If the duration is negative, then the time range is built to the left of the border, and if positive, then to the right.

#### completeTimeout

The completeTimeout interval describes a delay that describes the time interval during which, after the current moment of time has crossed the extreme TimeRange boundary, the ability to access object data through api is guaranteed to remain.
That is, the object will be present in memory and open to calls.

#### interval

The entire TimeRange is divided into sub-ranges with the duration specified by the interval parameter.
For each subrange, a separate structure for storing elements marked with a time-marker is allocated, which just falls into the specified subrange.
An interval that is not positive or longer than the duration is taken equal to the absolute duration, and an interval is at least a millisecond.

### Supported items

The implemented library allows you to store elements that have a time marker inside the TimeRange and retrieve at each moment of time those objects that have a time marker earlier than the current moment. A temporary marker can be attached to an object in three ways:
1) An implementation of the [Expected&lt;T extends TemporalAccessor&gt;](https://github.com/sftwnd/crayfish-common-expectation/blob/crayfish-common-expectation-1.4.0/src/main/java/com/github/sftwnd/crayfish/common/expectation/Expected.java) interface, where the token is obtained by calling the getTick() method
2) Packing the element into an object that implements the [ExpectedPackage&lt;M,T extends TemporalAccessor&gt;](https://github.com/sftwnd/crayfish-common-expectation/blob/crayfish-common-expectation-1.4.0/src/main/java/com/github/sftwnd/crayfish/common/expectation/ExpectedPackage.java) interface, which also supports the [Expected&lt;T extends TemporalAccessor&gt;](https://github.com/sftwnd/crayfish-common-expectation/blob/crayfish-common-expectation-1.4.0/src/main/java/com/github/sftwnd/crayfish/common/expectation/Expected.java) interface, and the element itself is obtained by calling the getElement() method
3) A description of two methods that implement the interfaces [TemporalExtractor&lt;M,? extends TemporalAccessor&gt;](https://github.com/sftwnd/crayfish-common-expectation/blob/crayfish-common-expectation-1.4.0/src/main/java/com/github/sftwnd/crayfish/common/expectation/TemporalExtractor.java) to get the temporary marker from the object and [ITimeRange.Transformer&lt;M,R&gt;](./crayfish-alarms-timerange/src/main/java/com/github/sftwnd/crayfish/alarms/timerange/ITimeRange.java#L138-L161) to get the resulting object from the original one.

### Uniqueness Constraint

There is one TimeRange limitation: you cannot describe two objects at the same time that, when cast to the resulting object, will turn out to be equal.
To implement this restriction, the Comparator&lt;&gt; of the resulting object is used, which is used exactly in case of a match in time markers and allows you to filter out duplicates (perform a distinct operation)
Without a comparator, two objects with the same time marker are duplicates if they are equal (`equals`).

### Creation of ITimeRangeFactory&lt;M,R&gt;

The **create**, **packable**, **expected** and **temporal** factory methods are used to instantiate the [ITimeRangeFactory&lt;M,R&gt;](./crayfish-alarms-timerange/src/main/java/com/github/sftwnd/crayfish/alarms/timerange/ITimeRangeFactory.java) description.

#### method [create](./crayfish-alarms-timerange/src/main/java/com/github/sftwnd/crayfish/alarms/timerange/ITimeRangeFactory.java#L50-L69)

This is the most general method. When calling it, you must specify the **duration**, **interval** and **completeTimeout** parameters described above, and:
* **preserver** - transforms an added element into the element that the range stores
* **expectation** - gets the time marker of a stored element
* **reducer** - transforms a fired stored element into the resulting one
* **comparator** - compares the stored elements of the same time marker, or null (see the uniqueness constraint above)

```java
    ITimeRangeFactory<SourceObject,FiredObject> timeRangeFactory = ITimeRangeFactory.create(
            Duration.ofSeconds(180),
            Duration.ofMillis(15000),
            Duration.ofSeconds(15),
            Source2Package::transform,
            Package::getFireTime,
            Package2Alarm::transform,
            null
    );
```

#### method [packable](./crayfish-alarms-timerange/src/main/java/com/github/sftwnd/crayfish/alarms/timerange/ITimeRangeFactory.java#L106-L119)

This method creates a ITimeRangeFactory&lt;E,E&gt; whose TimeRange packs every added element with its time marker into an [ExpectedPackage&lt;E,T extends TemporalAccessor&gt;](https://github.com/sftwnd/crayfish-common-expectation/blob/crayfish-common-expectation-1.4.0/src/main/java/com/github/sftwnd/crayfish/common/expectation/ExpectedPackage.java) and returns the element of the package when it fires.
Instead of the **preserver**, **expectation** and **reducer**, it takes a [TemporalExtractor&lt;E,T&gt;](https://github.com/sftwnd/crayfish-common-expectation/blob/crayfish-common-expectation-1.4.0/src/main/java/com/github/sftwnd/crayfish/common/expectation/TemporalExtractor.java) that gets the time marker of an element, once, when the element is added; the comparator compares the elements.

```java
    ITimeRangeFactory<String,String> timeRangeFactory = ITimeRangeFactory.packable(
        Duration.ofSeconds(10),
        Duration.ofMillis(1000),
        Duration.ofSeconds(3),
        Instant::parse,
        String::compareTo
    );
```

#### method [expected](./crayfish-alarms-timerange/src/main/java/com/github/sftwnd/crayfish/alarms/timerange/ITimeRangeFactory.java#L131-L142)

This method defines the **preserver**, **expectation** and **reducer** itself and creates a [ITimeRangeFactory&lt;M,M&gt;](./crayfish-alarms-timerange/src/main/java/com/github/sftwnd/crayfish/alarms/timerange/ITimeRangeFactory.java) that has the same object at the input and output that implements the [Expected&lt;T extends TemporalAccessor&gt;](https://github.com/sftwnd/crayfish-common-expectation/blob/crayfish-common-expectation-1.4.0/src/main/java/com/github/sftwnd/crayfish/common/expectation/Expected.java) interface: its getTick() is the time marker.

```java
    ITimeRangeFactory<Expected<Instant>, Expected<Instant>> timeRangeFactory = ITimeRangeFactory.expected(
        Duration.ofSeconds(10),
        Duration.ofMillis(1000),
        Duration.ofSeconds(3),
        null
    );
```

#### method [temporal](./crayfish-alarms-timerange/src/main/java/com/github/sftwnd/crayfish/alarms/timerange/ITimeRangeFactory.java#L154-L161)

This method creates a [ITimeRangeFactory&lt;M,M&gt;](./crayfish-alarms-timerange/src/main/java/com/github/sftwnd/crayfish/alarms/timerange/ITimeRangeFactory.java) that has the same object at the input and output, an &lt;M extends TemporalAccessor&gt; that is its own time marker.

```java
    ITimeRangeFactory<Instant,Instant> timeRangeFactory = ITimeRangeFactory.temporal(
            Duration.ofSeconds(SECONDS),
            Duration.ofMillis(333),
            Duration.ofSeconds(1),
            Instant::compareTo
    );
```

### ITimeRangeFactory&lt;M,R&gt;

The creation of a physical region is implemented in the ITimeRangeFactory class.

#### Creation of ITimeRange

The ITimeRange instance is created by the [timeRange(TemporalAccessor)](./crayfish-alarms-timerange/src/main/java/com/github/sftwnd/crayfish/alarms/timerange/ITimeRangeFactory.java#L32) method. It takes a time point and, depending on the sign of the duration of the factory, describes the physical time range to the left or to the right of it, with the parameters of the factory described above.

```java
    ITimeRange<MyObject, NewObject> timeRange = timeRangeFactory.timeRange(Instant.now());
```

#### ITimeRange attributes

ITimeRange has the following attributes, which can be obtained using the corresponding getter:
* startInstant - Left border of the time range described by the holder
* lastInstant - Right border of the time range described by the holder (excluded)

There are also three additional methods:
* isExpired() - true if the time specified by the parameter or the current time (if the parameter is not set) is greater than lastInstant by at least completeTimeout
* isComplete() - true if expired and there is no element inside the holder
* duration() - get time interval until the next event occurs from the moment specified by the parameter, or from the current moment if the parameter is not set

#### Adding new elements to the holder

To add new elements, use the [addElements](./crayfish-alarms-timerange/src/main/java/com/github/sftwnd/crayfish/alarms/timerange/TimeRange.java#L165-L185) method:

```java
    List<MyObject> elements = getElements();
    Collection<MyObject> rejected = timeRange.addElements(elements);
```

If any elements do not fall within the specified region (excluding the right border), then these elements will be returned by the addElements method as not processed (rejected)

#### Getting triggered (fired) elements from a holder

To get fired elements, you need to call the [extractFiredElements](./crayfish-alarms-timerange/src/main/java/com/github/sftwnd/crayfish/alarms/timerange/TimeRange.java#L220-L246) method specifying the point in time at which you want to search for elements. If no point in time is specified, then the current one is used.
All fired elements will be extracted from the holder and returned as a result as a Collection of elements in the order of their time markers (unique if the timestamp matches).

```java
    Collection<NewObject> firedSet = timeRange.extractFiredElements(Instant.now().plusMillis(250));
```

## Java modules

The library runs on Java 8+: the classes are Java 8 bytecode. On Java 9+ its jars are named modules:
`com.github.sftwnd.crayfish_alarms_timerange` exports the package `com.github.sftwnd.crayfish.alarms.timerange`, and
`com.github.sftwnd.crayfish_alarms_service` exports the package `com.github.sftwnd.crayfish.alarms.service` and
requires the module of the time range transitively:

```java
module my.application {
    requires com.github.sftwnd.crayfish_alarms_service;
}
```

The module descriptors are in `META-INF/versions/9` of the multi-release jars, so Java 8 ignores them. The sources jars
have their sources at the same place. The SpotBugs annotations of the API (`NonNull`, `Nullable`) have CLASS retention:
their dependency is optional and `requires static`, a consumer needs them neither to compile nor to run.

## OSGi

The jars are OSGi bundles. `com.github.sftwnd.crayfish.alarms.timerange` and `com.github.sftwnd.crayfish.alarms.service`
export their packages with the version of the release and import those of `crayfish-common-expectation` and of the time
range. They do not import the SpotBugs annotations, which are not needed at run time, and require Java 8 (`osgi.ee`
JavaSE 1.8):

```text
Bundle-SymbolicName: com.github.sftwnd.crayfish.alarms.timerange
Export-Package: com.github.sftwnd.crayfish.alarms.timerange;version="1.0.5";uses:="com.github.sftwnd.crayfish.common.expectation"
Import-Package: com.github.sftwnd.crayfish.common.expectation;version="[1.4,2)"

Bundle-SymbolicName: com.github.sftwnd.crayfish.alarms.service
Export-Package: com.github.sftwnd.crayfish.alarms.service;version="1.0.5";uses:="com.github.sftwnd.crayfish.alarms.timerange"
Import-Package: com.github.sftwnd.crayfish.alarms.timerange;version="[1.0,2)"
```

The build checks the bundles in Apache Felix on Java 8 and Java 21: the framework resolves them with the bundle of
`crayfish-common-expectation`, and a bundle that imports their packages fires an alarm.

## Building

The project builds with the Maven Wrapper (Maven 3.10.0) on JDK 8 to 25:

```shell
./mvnw verify
```

The classes are compiled for Java 8 on every JDK; the module descriptors are built on JDK 9+ only, so a release has to be
built on JDK 9+. The dependencies and the plugins come from Maven Central: `.mvn/maven.config` gives Maven the settings
of `.mvn/settings.xml` instead of `~/.m2/settings.xml`.

Two builds of the same sources on the same JDK give the same jars byte for byte: `project.build.outputTimestamp` gives
their entries a fixed time, which `release:prepare` sets to the time of the release.

---
Copyright © 2017-2022 Andrey D. Shindarev. All rights reserved.
