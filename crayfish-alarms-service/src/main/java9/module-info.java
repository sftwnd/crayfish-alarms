// The library is compiled for Java 8; this descriptor is read by Java 9+ only, from META-INF/versions/9 of the jar
// javac 11 with --release takes the multi-release jar of the time range for an automatic module, later versions do not
@SuppressWarnings("requires-transitive-automatic")
open module com.github.sftwnd.crayfish_alarms_service {
    requires transitive com.github.sftwnd.crayfish_alarms_timerange;
    requires java.logging;
    exports com.github.sftwnd.crayfish.alarms.service;
}