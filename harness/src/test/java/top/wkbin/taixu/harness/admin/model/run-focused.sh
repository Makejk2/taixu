#!/bin/sh
# Cached compiler only. No Gradle/installation/network. Temporary stubs are NOT Room validation.
set -eu
cd "$(git -C "$(dirname "$0")" rev-parse --show-toplevel)"
ROOT=$(pwd)
CACHE=/root/.gradle/caches/modules-2/files-2.1
jar() { find "$CACHE/$1" -name '*.jar' | head -1; }
KOTLIN=$(jar org.jetbrains.kotlin/kotlin-compiler-embeddable/2.4.10)
STD=$(jar org.jetbrains.kotlin/kotlin-stdlib/2.4.10)
REF=$(jar org.jetbrains.kotlin/kotlin-reflect/2.4.10)
DAEMON=$(jar org.jetbrains.kotlin/kotlin-daemon-embeddable/2.4.10)
ANN=$(jar org.jetbrains/annotations/23.0.0)
CORO=$(jar org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.9.0)
JUNIT=$(jar junit/junit/4.13.2)
HAM=$(jar org.hamcrest/hamcrest-core/1.3)
SER=/root/.cache/taixu-app-config-tests
CP="$STD:$ANN:$CORO:$JUNIT:$HAM:$SER/kotlinx-serialization-core-jvm-1.7.3.jar:$SER/kotlinx-serialization-json-jvm-1.7.3.jar"
TMP=$(mktemp -d "$ROOT/harness/src/test/java/top/wkbin/taixu/harness/admin/model/.focused.XXXXXX")
trap 'rm -rf "$TMP"' EXIT
cat > "$TMP/Room.kt" <<'STUB'
package androidx.room
annotation class Dao
annotation class Query(val value: String)
annotation class Transaction
annotation class Entity(val tableName: String, val indices: Array<Index> = [])
annotation class PrimaryKey
annotation class Insert(val onConflict: Int = 0)
annotation class Index(val value: Array<String>)
annotation class ColumnInfo(val defaultValue: String)
object OnConflictStrategy { const val REPLACE = 1 }
STUB
cat > "$TMP/Repositories.kt" <<'STUB'
package top.wkbin.taixu.core.database
interface HarnessSessionRepository { suspend fun findById(id: String): HarnessSessionEntity? }
data class HarnessOperationEntity(val id: String, val sessionId: String, val laneName: String)
data class HarnessLaneEntity(val currentOperationId: String?)
interface HarnessRuntimeRepository {
 suspend fun findOperation(id: String): HarnessOperationEntity?
 suspend fun findLane(session: String, lane: String): HarnessLaneEntity?
}
STUB
cat > "$TMP/Bots.kt" <<'STUB'
package top.wkbin.taixu.core.database.repository
import kotlinx.coroutines.flow.Flow
interface BotAssignmentRepository { suspend fun assignmentForSession(id: String): Any? }
interface PersonaBotRepository {
 suspend fun getBotBySessionId(id: String): Any?
 fun observeGroupSessionRefs(): Flow<Set<String>>
}
STUB
cat > "$TMP/Tool.kt" <<'STUB'
package top.wkbin.taixu.harness
import kotlinx.serialization.json.JsonObject
data class ToolCall(val id: String, val args: JsonObject)
data class ToolResult(val id: String, val createdAt: Long, val toolCallId: String, val success: Boolean, val output: String)
data class ApprovalDecision(val required: Boolean, val riskLevel: String = "low", val reason: String = "", val summary: String = "")
STUB
# Compile the real session entity only. Its unrelated session DAO/fence is outside this focused suite.
sed '/^@Dao/,$d' core/database/src/main/java/top/wkbin/taixu/core/database/HarnessSessionEntity.kt > "$TMP/SessionEntity.kt"
# Existing fake algorithm tests only; new Robolectric/Room tests run under CI, never these stubs.
sed '/^\/\/ ROOM_CI_TESTS/,$d' core/database/src/test/java/top/wkbin/taixu/core/database/ModelManagementCasTest.kt > "$TMP/ModelManagementCasTest.kt"
DB=core/database/src/main/java/top/wkbin/taixu/core/database
MODEL=core/model/src/main/java/top/wkbin/taixu/core/model
SRC=harness/src/main/java/top/wkbin/taixu/harness/admin/model
TEST=harness/src/test/java/top/wkbin/taixu/harness/admin/model
java -cp "$KOTLIN:$STD:$REF:$DAEMON:$ANN:$CORO" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
 -no-stdlib -no-reflect -classpath "$CP" -d "$TMP/tests.jar" \
 "$TMP"/*.kt "$DB/AiModelEntity.kt" "$DB/AgentApprovalAuditEntity.kt" "$DB/ModelManagementCas.kt" \
 "$MODEL/ApprovalMode.kt" "$MODEL/RunMode.kt" "$SRC"/*.kt "$TEST/ModelAdminServiceTest.kt"
java -cp "$TMP/tests.jar:$CP" org.junit.runner.JUnitCore \
 top.wkbin.taixu.harness.admin.model.ModelAdminServiceTest \
 top.wkbin.taixu.core.database.ModelManagementCasTest
