package top.wkbin.taixu.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import org.koin.compose.koinInject
import top.wkbin.taixu.core.database.HarnessSessionRepository
import top.wkbin.taixu.core.database.SessionManagementPersistence
import top.wkbin.taixu.core.database.repository.PersonaBotRepository
import top.wkbin.taixu.core.database.repository.BotAssignmentRepository
import top.wkbin.taixu.core.datastore.AiAdminPreferences
import top.wkbin.taixu.harness.session.InteractiveSessionControl
import top.wkbin.taixu.harness.admin.session.NativeSessionRecovery
import top.wkbin.taixu.harness.admin.nativeui.*
import top.wkbin.taixu.ui.components.RuntimeButton

/** Historical unknown is adopted by explicit native confirmation, never inferred as historical USER. */
@Composable
fun NativeSessionClaimPanel() {
    val sessions=koinInject<HarnessSessionRepository>()
    val persistence=koinInject<SessionManagementPersistence>()
    val control=koinInject<InteractiveSessionControl>()
    val bots=koinInject<PersonaBotRepository>()
    val tasks=koinInject<BotAssignmentRepository>()
    val prefs=koinInject<AiAdminPreferences>()
    val queue=koinInject<NativeManagementQueue>()
    val scope=rememberCoroutineScope()
    var candidates by remember { mutableStateOf<List<Pair<String,Long>>>(emptyList()) }
    var notice by remember { mutableStateOf("") }
    Column {
        Text("旧会话原生授权接管（不伪造历史来源）")
        RuntimeButton(onClick={ scope.launch {
            try {
                check(prefs.snapshot.first().first)
                val groups=bots.observeGroupSessionRefs().first()
                candidates=sessions.listAll().mapNotNull { row ->
                    if(row.id.startsWith("workflow:") || row.id.startsWith("subagent:") || row.id in groups ||
                        bots.getBotBySessionId(row.id)!=null || tasks.assignmentForSession(row.id)!=null ||
                        persistence.explicitlyClaimed(row.id) || !persistence.isOpen(row.id)) null
                    else persistence.snapshot(row.id)?.let { row.id to it.revision }
                }
                notice="选择后还需逐项确认；只接管权限，不运行或删除旧任务。"
            } catch (_: Exception) { notice="无法取得原生会话快照；未接管。" }
        } }) { Text("查询尚未纳管的历史会话") }
        Text(notice)
        candidates.forEach { (id,revision) ->
            RuntimeButton(onClick={ scope.launch {
                try {
                    val frozen=prefs.snapshot.first();check(frozen.first)
                    val source=control.currentSessionId.value
                    val recovery=NativeSessionRecovery(persistence,{ control },authorize={ target ->
                        val live=prefs.snapshot.first()
                        live==frozen && !target.startsWith("workflow:") && !target.startsWith("subagent:") &&
                            bots.getBotBySessionId(target)==null && tasks.assignmentForSession(target)==null &&
                            target !in bots.observeGroupSessionRefs().first()
                    })
                    queue.enqueueSessionFromNative(recovery,source,id,ManagementAction.SESSION_CLAIM,
                        revision,System.currentTimeMillis()+120_000)
                    notice="已创建原生接管待办，尚未授权。"
                } catch (_: Exception) { notice="快照不可用；未创建接管。" }
            } }) { Text("请求接管：$id") }
        }
    }
}
