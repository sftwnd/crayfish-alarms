// The library is compiled for Java 8; this descriptor is read by Java 9+ only, from META-INF/versions/9 of the jar
@SuppressWarnings("requires-transitive-automatic")
open module com.github.sftwnd.crayfish_alarms_timerange {
    requires transitive com.github.spotbugs.annotations;
    requires transitive com.github.sftwnd.crayfish_common_expectation;
    exports com.github.sftwnd.crayfish.alarms.timerange;
}