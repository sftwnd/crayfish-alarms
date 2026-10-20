#!/bin/bash
# Checks the OSGi bundles of mvn package: their headers, and that Apache Felix resolves them with the bundle of
# crayfish-common-expectation and without the optional SpotBugs annotations, and runs a bundle that imports their
# packages and fires an alarm, with the JDK of JAVA_HOME (9+) and, if the home of a Java 8 JDK is given, with Java 8.
# MVN is the command of Maven, ./mvnw if the project has it, mvn otherwise.
# usage: .github/scripts/check-osgi-bundle.sh [java-8-home]
set -euo pipefail

TIMERANGE=com.github.sftwnd.crayfish.alarms.timerange
SERVICE=com.github.sftwnd.crayfish.alarms.service
EXPECTATION=com.github.sftwnd.crayfish.common.expectation
FELIX=org.apache.felix:org.apache.felix.framework:7.0.5
JAVA8_HOME=${1:-}
if [[ -z "${MVN:-}" ]]; then
    if [[ -x ./mvnw ]]; then
        MVN=./mvnw
    else
        MVN=mvn
    fi
fi
read -r -a MAVEN <<< "$MVN"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

fail() {
    echo "FAILED: $*" >&2
    exit 1
}

# the jar of a module, not its sources or javadoc jar, with the absolute path
jar_of() {
    local module=$1
    ls "$PWD/$module"/target/"$module"-*.jar | grep -vE -- '-(sources|javadoc)\.jar$'
}

# the headers of the manifest of a jar, with their continuation lines joined
headers_of() {
    local jar=$1
    unzip -p "$jar" META-INF/MANIFEST.MF | tr -d '\r' \
        | awk '/^ / { line = line substr($0, 2); next } { if (NR > 1) print line; line = $0 } END { print line }'
}

# the manifest of a jar has the headers
check_headers() {
    local jar=$1 headers header
    shift
    headers=$(headers_of "$jar")
    grep -E '^(Bundle-SymbolicName|Bundle-Version|Export-Package|Import-Package|Require-Capability):' <<< "$headers" || true
    for header in "$@"; do
        grep -qxF -- "$header" <<< "$headers" || fail "no '$header' in the manifest of $jar"
    done
    # the CLASS-retention SpotBugs annotations are not imported
    if grep -q '^Import-Package:.*edu\.umd\.cs\.findbugs' <<< "$headers"; then
        fail "the bundle of $jar imports the SpotBugs annotations"
    fi
}

TIMERANGE_JAR=$(jar_of crayfish-alarms-timerange)
SERVICE_JAR=$(jar_of crayfish-alarms-service)
VERSION=${SERVICE_JAR##*/crayfish-alarms-service-}
VERSION=${VERSION%.jar}
VERSION=${VERSION%-SNAPSHOT}
# the version range of an import of the packages of the release: [major.minor,major+1)
RANGE="[${VERSION%.*},$((${VERSION%%.*} + 1)))"
# the version of the bundles, with the qualifier SNAPSHOT of a snapshot
BUNDLE_VERSION=$(headers_of "$SERVICE_JAR" | sed -n 's/^Bundle-Version: //p')

# crayfish-common-expectation and Apache Felix, from Maven Central
"${MAVEN[@]}" -B -q -ntp -f crayfish-alarms-timerange/pom.xml dependency:build-classpath \
    -DincludeScope=runtime -DincludeGroupIds=com.github.sftwnd.crayfish.common -Dmdep.outputFile="$WORK/expectation"
EXPECTATION_JAR=$(cat "$WORK/expectation")
[[ "$EXPECTATION_JAR" != *:* && "$EXPECTATION_JAR" == */crayfish-common-expectation-*.jar ]] \
    || fail "expected the jar of crayfish-common-expectation: $EXPECTATION_JAR"
EXPECTATION_VERSION=$(headers_of "$EXPECTATION_JAR" | sed -n 's/^Bundle-Version: //p')
"${MAVEN[@]}" -B -q -ntp dependency:copy -Dartifact="$FELIX" -DoutputDirectory="$WORK"
FELIX_JAR=$(ls "$WORK"/org.apache.felix.framework-*.jar)

check_headers "$TIMERANGE_JAR" "Bundle-ManifestVersion: 2" \
    "Bundle-SymbolicName: $TIMERANGE" \
    "Export-Package: $TIMERANGE;version=\"$VERSION\";uses:=\"$EXPECTATION\"" \
    "Import-Package: $EXPECTATION;version=\"[${EXPECTATION_VERSION%.*},$((${EXPECTATION_VERSION%%.*} + 1)))\"" \
    'Require-Capability: osgi.ee;filter:="(&(osgi.ee=JavaSE)(version=1.8))"' \
    "Automatic-Module-Name: com.github.sftwnd.crayfish_alarms_timerange" \
    "Multi-Release: true"
check_headers "$SERVICE_JAR" "Bundle-ManifestVersion: 2" \
    "Bundle-SymbolicName: $SERVICE" \
    "Export-Package: $SERVICE;version=\"$VERSION\";uses:=\"$TIMERANGE\"" \
    "Import-Package: $TIMERANGE;version=\"$RANGE\"" \
    'Require-Capability: osgi.ee;filter:="(&(osgi.ee=JavaSE)(version=1.8))"' \
    "Automatic-Module-Name: com.github.sftwnd.crayfish_alarms_service" \
    "Multi-Release: true"

# a bundle that imports the packages of the release and fires an alarm, and a launcher that installs the bundles and
# starts them
mkdir -p "$WORK/consumer/alarms/check" "$WORK/consumer-classes" "$WORK/launcher/alarms/launch" "$WORK/launcher-classes"
cat > "$WORK/consumer/alarms/check/Activator.java" <<'JAVA'
package alarms.check;

import com.github.sftwnd.crayfish.alarms.service.AlarmTimeRangeService;
import com.github.sftwnd.crayfish.alarms.timerange.ITimeRange;
import com.github.sftwnd.crayfish.alarms.timerange.ITimeRangeFactory;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class Activator implements BundleActivator {
    @Override
    public void start(BundleContext context) throws Exception {
        // an alarm at the start of the range, added before the service runs, fires at once
        ITimeRange<Instant, Instant> range = ITimeRangeFactory.<Instant>temporal(
                Duration.ofMinutes(1), Duration.ofSeconds(1), Duration.ZERO, null).timeRange(Instant.now());
        AlarmTimeRangeService<Instant, Instant> service = new AlarmTimeRangeService<>(range, Duration.ZERO);
        CompletableFuture<Instant> fired = new CompletableFuture<>();
        service.addElement(range.getStartInstant());
        Thread thread = new Thread(() -> service.singleProcess(fired::complete));
        thread.setDaemon(true);
        thread.start();
        boolean ok = range.getStartInstant().equals(fired.get(10, TimeUnit.SECONDS));
        // the bundles that the classes of the packages come from
        System.out.println(FrameworkUtil.getBundle(AlarmTimeRangeService.class).getSymbolicName() + " "
                + FrameworkUtil.getBundle(ITimeRange.class).getSymbolicName() + (ok ? " fired" : " not fired"));
    }

    @Override
    public void stop(BundleContext context) {
        // nothing to stop: the thread of the service is a daemon
    }
}
JAVA
cat > "$WORK/consumer.mf" <<MANIFEST
Bundle-ManifestVersion: 2
Bundle-SymbolicName: alarms.check
Bundle-Version: 1.0.0
Bundle-Activator: alarms.check.Activator
Import-Package: $SERVICE;version="$RANGE",$TIMERANGE;version="$RANGE",org.osgi.framework
MANIFEST
cat > "$WORK/launcher/alarms/launch/Launcher.java" <<'JAVA'
package alarms.launch;

import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.launch.FrameworkFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

// usage: Launcher <storage> <bundle>... : installs the bundles, starts them in their order and prints their states
public class Launcher {
    public static void main(String[] args) throws Exception {
        Map<String, String> configuration = new HashMap<>();
        configuration.put(Constants.FRAMEWORK_STORAGE, args[0]);
        configuration.put(Constants.FRAMEWORK_STORAGE_CLEAN, Constants.FRAMEWORK_STORAGE_CLEAN_ONFIRSTINIT);
        Framework framework = ServiceLoader.load(FrameworkFactory.class).iterator().next().newFramework(configuration);
        framework.start();
        try {
            BundleContext context = framework.getBundleContext();
            List<Bundle> bundles = new ArrayList<>();
            for (int i = 1; i < args.length; i++) {
                bundles.add(context.installBundle(new File(args[i]).toURI().toString()));
            }
            for (Bundle bundle : bundles) {
                bundle.start();
            }
            for (Bundle bundle : bundles) {
                System.out.println(bundle.getSymbolicName() + " " + bundle.getVersion() + " "
                        + (bundle.getState() == Bundle.ACTIVE ? "ACTIVE" : "state " + bundle.getState()));
            }
        } finally {
            framework.stop();
            framework.waitForStop(10_000);
        }
    }
}
JAVA
LIBRARY="$SERVICE_JAR:$TIMERANGE_JAR:$EXPECTATION_JAR"
"$JAVA_HOME/bin/javac" --release 8 -Xlint:-options -d "$WORK/consumer-classes" -cp "$FELIX_JAR:$LIBRARY" \
    "$WORK/consumer/alarms/check/Activator.java"
"$JAVA_HOME/bin/jar" --create --file "$WORK/consumer.jar" --manifest "$WORK/consumer.mf" -C "$WORK/consumer-classes" .
"$JAVA_HOME/bin/javac" --release 8 -Xlint:-options -d "$WORK/launcher-classes" -cp "$FELIX_JAR" \
    "$WORK/launcher/alarms/launch/Launcher.java"

check() {
    local java_home=$1 storage=$2 out line
    out=$("$java_home/bin/java" -cp "$FELIX_JAR:$WORK/launcher-classes" alarms.launch.Launcher "$storage" \
        "$EXPECTATION_JAR" "$TIMERANGE_JAR" "$SERVICE_JAR" "$WORK/consumer.jar")
    echo "$("$java_home/bin/java" -version 2>&1 | head -1): $(tr '\n' ';' <<< "$out")"
    grep -qxF "$SERVICE $TIMERANGE fired" <<< "$out" || fail "the bundle that imports the packages did not fire the alarm"
    for line in "$EXPECTATION $EXPECTATION_VERSION ACTIVE" "$TIMERANGE $BUNDLE_VERSION ACTIVE" \
                "$SERVICE $BUNDLE_VERSION ACTIVE" "alarms.check 1.0.0 ACTIVE"; do
        grep -qxF -- "$line" <<< "$out" || fail "no '$line' among the bundles"
    done
}
check "$JAVA_HOME" "$WORK/felix"
if [[ -n "$JAVA8_HOME" ]]; then
    check "$JAVA8_HOME" "$WORK/felix-8"
fi
echo "OK"
