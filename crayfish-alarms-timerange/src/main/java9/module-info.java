// The library is compiled for Java 8; this descriptor is read by Java 9+ only, from META-INF/versions/9 of the jar
// javac 11 with --release takes the multi-release jar of crayfish-common-expectation for an automatic module, later
// versions do not
@SuppressWarnings("requires-transitive-automatic")
open module com.github.sftwnd.crayfish_alarms_timerange {
    // CLASS-retention marker annotations, needed neither to compile against the module nor to run it
    requires static com.github.spotbugs.annotations;
    requires transitive com.github.sftwnd.crayfish_common_expectation;
    exports com.github.sftwnd.crayfish.alarms.timerange;
}