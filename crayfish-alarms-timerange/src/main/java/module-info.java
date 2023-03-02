@SuppressWarnings("requires-transitive-automatic")
open module crayfish.alarms.timerange {
    requires transitive com.github.spotbugs.annotations;
    requires transitive crayfish.common.expectation;
    requires lombok;
    exports com.github.sftwnd.crayfish.alarms.timerange;
}