#!/bin/sh
# Offline, deterministic real java.net regression. No Gradle, install, downloads, socket mocks,
# or production replacement. Run: sh tools/tests/verify_http_lifecycle.sh [repo-root]
# External cache: GRADLE_CACHE (default ~/.gradle/caches/modules-2/files-2.1).
# JAVA_BIN may select another existing JDK. Output: OUT or /workspace/.tmp_att/...
# Stub boundary: none. WebChatHttp.kt's file-response+CORS helpers are extracted
# verbatim because unrelated JSON helpers require an unavailable serialization jar.
# All AndroidHttpServer types, original protocol tests and lifecycle tests are compiled.
set -eu
ROOT=${1:-$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)}
CACHE=${GRADLE_CACHE:-$HOME/.gradle/caches/modules-2/files-2.1}
JAVA=${JAVA_BIN:-java}
OUT=${OUT:-/workspace/.tmp_att/http-lifecycle-$(date +%Y%m%d-%H%M%S)-$$}
mkdir -p "$OUT/classes"
jar() { find -L "$CACHE/$1/$2/$3" -name '*.jar' -type f | head -n 1; }
required() { value=$(jar "$1" "$2" "$3"); [ -n "$value" ] || { echo "Missing cached $1:$2:$3" >&2; exit 2; }; printf '%s' "$value"; }
KVER=${KOTLIN_VERSION:-2.0.21}
STDLIB=$(required org.jetbrains.kotlin kotlin-stdlib "$KVER")
COMPILER=$(required org.jetbrains.kotlin kotlin-compiler-embeddable "$KVER")
SCRIPT=$(required org.jetbrains.kotlin kotlin-script-runtime "$KVER")
DAEMON=$(required org.jetbrains.kotlin kotlin-daemon-embeddable "$KVER")
REFLECT=$(required org.jetbrains.kotlin kotlin-reflect 1.6.10)
TROVE=$(required org.jetbrains.intellij.deps trove4j 1.0.20200330)
COROUTINES=$(required org.jetbrains.kotlinx kotlinx-coroutines-core-jvm 1.6.4)
ANNOTATIONS=$(required org.jetbrains annotations 13.0)
JUNIT=$(required junit junit 4.13.2)
HAMCREST=$(required org.hamcrest hamcrest-core 1.3)
PKG=top/wkbin/taixu/runtime/webchat
SOURCE="$ROOT/runtime/src/main/java/$PKG/AndroidHttpServer.kt"
TEST="$ROOT/runtime/src/test/java/$PKG/AndroidHttpServerTest.kt"
LIFECYCLE="$ROOT/runtime/src/test/java/$PKG/AndroidHttpServerLifecycleTest.kt"
HELPERS="$ROOT/runtime/src/main/java/$PKG/WebChatHttp.kt"
{
 printf 'package top.wkbin.taixu.runtime.webchat\nimport java.io.File\n'
 sed -n '/^internal fun sendFileResponse(/,/^internal fun requestJson(/p' "$HELPERS" | sed '$d'
} > "$OUT/FileResponseExtract.kt"
cat > "$OUT/LifecycleProbe.kt" <<'KT'
package top.wkbin.taixu.runtime.webchat

import org.junit.runner.JUnitCore

fun main(args: Array<String>) {
    println("ENV java=${System.getProperty("java.runtime.version")} vendor=${System.getProperty("java.vendor")} os=${System.getProperty("os.name")} ${System.getProperty("os.version")}")
    val rounds = args.firstOrNull()?.toInt() ?: 1
    var failures = 0
    repeat(rounds) { round ->
        for (suite in listOf(AndroidHttpServerTest::class.java, AndroidHttpServerLifecycleTest::class.java)) {
            val result = JUnitCore.runClasses(suite)
            println("JUNIT round=$round suite=${suite.simpleName} tests=${result.runCount} failures=${result.failureCount}")
            result.failures.forEach { println(it.trace) }
            failures += result.failureCount
        }
    }
    println("JUNIT_SUMMARY rounds=$rounds failures=$failures")
    if (failures > 0) kotlin.system.exitProcess(1)
}
KT
# Compiler-only dependencies never replace java.net; no test source modifications.
CP="$STDLIB:$ANNOTATIONS:$JUNIT:$HAMCREST"
CCP="$COMPILER:$STDLIB:$SCRIPT:$DAEMON:$REFLECT:$TROVE:$COROUTINES:$ANNOTATIONS"
{
 "$JAVA" -version
 printf 'ROOT=%s\nOUT=%s\n' "$ROOT" "$OUT"
 sha256sum "$SOURCE" "$TEST" "$LIFECYCLE" "$HELPERS" "$0"
 printf 'CACHE_CLASSPATH=%s\n' "$CCP:$JUNIT:$HAMCREST"
} > "$OUT/environment.log" 2>&1
"$JAVA" -cp "$CCP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 -classpath "$CP" -d "$OUT/classes" "$SOURCE" "$(dirname "$SOURCE")/HttpAcceptBarrier.kt" "$TEST" "$LIFECYCLE" "$OUT/FileResponseExtract.kt" "$OUT/LifecycleProbe.kt" > "$OUT/compile.log" 2>&1 || { cat "$OUT/compile.log"; exit 2; }
RUNCP="$OUT/classes:$CP"
status=0
"$JAVA" -cp "$RUNCP" top.wkbin.taixu.runtime.webchat.LifecycleProbeKt "${JUNIT_ROUNDS:-3}" > "$OUT/junit.log" 2>&1 || status=$?
cat "$OUT/junit.log"
printf 'Artifacts: %s\n' "$OUT"
exit "$status"
