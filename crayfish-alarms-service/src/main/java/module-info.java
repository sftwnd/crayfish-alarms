open module crayfish.alarms.service {
    requires transitive crayfish.alarms.timerange;
    requires java.logging;
    requires lombok;
    exports com.github.sftwnd.crayfish.alarms.service;
}