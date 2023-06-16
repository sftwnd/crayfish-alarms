open module com.github.sftwnd.crayfish_alarms_service {
    requires transitive com.github.sftwnd.crayfish_alarms_timerange;
    requires static lombok;
    requires java.logging;
    exports com.github.sftwnd.crayfish.alarms.service;
}