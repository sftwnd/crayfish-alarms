@SuppressWarnings("requires-transitive-automatic")
open module com.github.sftwnd.crayfish_alarms_timerange {
    requires transitive com.github.spotbugs.annotations;
    requires transitive com.github.sftwnd.crayfish_common_expectation;
    requires static lombok;
    exports com.github.sftwnd.crayfish.alarms.timerange;
}