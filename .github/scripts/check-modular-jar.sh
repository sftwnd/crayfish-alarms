#!/bin/bash
# Checks the jars built on JDK 9+ (mvn package): the module descriptors, the sources jars, a modular consumer on the
# module path with the JDK of JAVA_HOME, and, if the home of a Java 8 JDK is given, a consumer on the class path of Java 8.
# The dependencies of the jars come from the local repository of Maven: MVN is the command of Maven, ./mvnw if the
# project has it, mvn otherwise.
# usage: .github/scripts/check-modular-jar.sh [java-8-home]
set -euo pipefail

TIMERANGE=com.github.sftwnd.crayfish_alarms_timerange
SERVICE=com.github.sftwnd.crayfish_alarms_service
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

# the module descriptor, in META-INF/versions/9 of a multi-release jar, has the lines
check_descriptor() {
    local jar=$1 descriptor line
    shift
    descriptor=$("$JAVA_HOME/bin/jar" --describe-module --file "$jar" --release 9)
    echo "$descriptor"
    for line in "$@"; do
        grep -qF -- "$line" <<< "$descriptor" || fail "no '$line' in the module descriptor of $jar"
    done
}

# the sources jar mirrors the jar: the source of every top-level class at the place of the class, the root or
# META-INF/versions/9, and no other source; the sources are those of src/main/java and src/main/java9
check_sources() {
    local module=$1 jar=$2 sources=${2%.jar}-sources.jar classes files
    classes=$("$JAVA_HOME/bin/jar" --list --file "$jar" | grep -E '\.class$' | grep -vF '$' | sed -E 's/\.class$//' | sort)
    files=$("$JAVA_HOME/bin/jar" --list --file "$sources" | grep -E '\.java$' | sed -E 's/\.java$//' | sort)
    [[ "$classes" == "$files" ]] || fail "the sources of $sources are not those of the classes of $jar"
    mkdir -p "$WORK/sources/$module"
    (cd "$WORK/sources/$module" && "$JAVA_HOME/bin/jar" --extract --file "$sources")
    diff -r -x META-INF "$WORK/sources/$module" "$module/src/main/java" > /dev/null \
        || fail "the sources of $sources at the root are not those of $module/src/main/java"
    diff -r "$WORK/sources/$module/META-INF/versions/9" "$module/src/main/java9" > /dev/null \
        || fail "the sources of $sources in META-INF/versions/9 are not those of $module/src/main/java9"
    echo "sources: $(wc -l <<< "$files" | tr -d ' ') sources of $(basename "$sources") mirror the classes of $(basename "$jar")"
}

TIMERANGE_JAR=$(jar_of crayfish-alarms-timerange)
SERVICE_JAR=$(jar_of crayfish-alarms-service)
check_descriptor "$TIMERANGE_JAR" "$TIMERANGE@" \
    "exports com.github.sftwnd.crayfish.alarms.timerange" \
    "requires com.github.sftwnd.crayfish_common_expectation transitive" \
    "requires com.github.spotbugs.annotations static"
check_descriptor "$SERVICE_JAR" "$SERVICE@" \
    "exports com.github.sftwnd.crayfish.alarms.service" \
    "requires $TIMERANGE transitive" \
    "requires java.logging"
check_sources crayfish-alarms-timerange "$TIMERANGE_JAR"
check_sources crayfish-alarms-service "$SERVICE_JAR"

# the dependency of both jars that a consumer needs: crayfish-common-expectation, without the optional spotbugs
# annotations and their jsr305
"${MAVEN[@]}" -B -q -ntp -f crayfish-alarms-timerange/pom.xml \
    org.apache.maven.plugins:maven-dependency-plugin:3.11.0:build-classpath \
    -DincludeScope=runtime -DexcludeGroupIds=com.github.spotbugs,com.google.code.findbugs \
    -Dmdep.outputFile="$WORK/dependencies"
DEPENDENCIES=$(cat "$WORK/dependencies")
[[ "$DEPENDENCIES" != *:* && "$DEPENDENCIES" == */crayfish-common-expectation-*.jar ]] \
    || fail "expected crayfish-common-expectation as the only dependency: $DEPENDENCIES"
LIBRARY="$SERVICE_JAR:$TIMERANGE_JAR:$DEPENDENCIES"

# an alarm at the start of the range, added before the service runs, fires at once
mkdir -p "$WORK/modular/alarms.check/alarms/check" "$WORK/plain/alarms/check" "$WORK/plain/out"
cat > "$WORK/modular/alarms.check/module-info.java" <<JAVA
module alarms.check {
    requires $SERVICE;
}
JAVA
cat > "$WORK/modular/alarms.check/alarms/check/Main.java" <<'JAVA'
package alarms.check;

import com.github.sftwnd.crayfish.alarms.service.AlarmTimeRangeService;
import com.github.sftwnd.crayfish.alarms.timerange.ITimeRange;
import com.github.sftwnd.crayfish.alarms.timerange.ITimeRangeFactory;
import com.github.sftwnd.crayfish.common.expectation.Expected;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class Main {
    public static void main(String[] args) throws Exception {
        ITimeRange<Instant, Instant> range = ITimeRangeFactory.<Instant>temporal(
                Duration.ofMinutes(1), Duration.ofSeconds(1), Duration.ZERO, null).timeRange(Instant.now());
        AlarmTimeRangeService<Instant, Instant> service = new AlarmTimeRangeService<>(range, Duration.ZERO);
        CompletableFuture<Instant> fired = new CompletableFuture<>();
        service.addElement(range.getStartInstant());
        Thread thread = new Thread(() -> service.singleProcess(fired::complete));
        thread.setDaemon(true);
        thread.start();
        boolean ok = range.getStartInstant().equals(fired.get(10, TimeUnit.SECONDS));
        System.out.println(module(AlarmTimeRangeService.class) + " " + module(ITimeRange.class) + " "
                + module(Expected.class) + (ok ? " fired" : " not fired"));
    }

    private static String module(Class<?> type) {
        Module module = type.getModule();
        return module.getName() + (module.getDescriptor().isAutomatic() ? " automatic" : " named");
    }
}
JAVA
cat > "$WORK/plain/alarms/check/Main.java" <<'JAVA'
package alarms.check;

import com.github.sftwnd.crayfish.alarms.service.AlarmTimeRangeService;
import com.github.sftwnd.crayfish.alarms.timerange.ITimeRange;
import com.github.sftwnd.crayfish.alarms.timerange.ITimeRangeFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class Main {
    public static void main(String[] args) throws Exception {
        ITimeRange<Instant, Instant> range = ITimeRangeFactory.<Instant>temporal(
                Duration.ofMinutes(1), Duration.ofSeconds(1), Duration.ZERO, null).timeRange(Instant.now());
        AlarmTimeRangeService<Instant, Instant> service = new AlarmTimeRangeService<>(range, Duration.ZERO);
        CompletableFuture<Instant> fired = new CompletableFuture<>();
        service.addElement(range.getStartInstant());
        Thread thread = new Thread(() -> service.singleProcess(fired::complete));
        thread.setDaemon(true);
        thread.start();
        boolean ok = range.getStartInstant().equals(fired.get(10, TimeUnit.SECONDS));
        System.out.println(System.getProperty("java.version") + (ok ? " fired" : " not fired"));
    }
}
JAVA

# named modules on the module path, without the optional spotbugs annotations
EXPECTED="$SERVICE named $TIMERANGE named com.github.sftwnd.crayfish_common_expectation named fired"
"$JAVA_HOME/bin/javac" -d "$WORK/modular/out" --module-path "$LIBRARY" \
    --module-source-path "$WORK/modular" --module alarms.check
OUT=$("$JAVA_HOME/bin/java" --module-path "$LIBRARY:$WORK/modular/out/alarms.check" \
    --module alarms.check/alarms.check.Main)
echo "module path: $OUT"
[[ "$OUT" = "$EXPECTED" ]] || fail "expected '$EXPECTED' on the module path"

# Java 8 ignores the descriptors; the class path has no spotbugs annotations either
if [[ -n "$JAVA8_HOME" ]]; then
    "$JAVA8_HOME/bin/javac" -d "$WORK/plain/out" -cp "$LIBRARY" "$WORK/plain/alarms/check/Main.java"
    OUT=$("$JAVA8_HOME/bin/java" -cp "$LIBRARY:$WORK/plain/out" alarms.check.Main)
    echo "class path: $OUT"
    [[ "$OUT" == 1.8.* && "$OUT" == *" fired" ]] || fail "expected the alarm fired on the class path of Java 8"
fi
echo "OK"
