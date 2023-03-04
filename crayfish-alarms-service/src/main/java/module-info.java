open module com.github.sftwnd.crayfish_alarms_service {
    requires transitive com.github.sftwnd.crayfish_alarms_timerange;
    requires java.logging;
    requires lombok;
    exports com.github.sftwnd.crayfish.alarms.service;
}