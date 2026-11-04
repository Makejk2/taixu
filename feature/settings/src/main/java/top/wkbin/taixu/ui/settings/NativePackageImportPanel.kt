package top.wkbin.taixu.ui.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import org.koin.compose.koinInject
import top.wkbin.taixu.core.datastore.AiAdminPreferences
import top.wkbin.taixu.core.database.HarnessSessionRepository
import top.wkbin.taixu.core.database.repository.PersonaBotRepository
import top.wkbin.taixu.core.database.repository.BotAssignmentRepository
import top.wkbin.taixu.harness.session.SessionControl
import top.wkbin.taixu.harness.admin.NativeSessionOwnership
import top.wkbin.taixu.harness.admin.nativeui.NativeManagementQueue
import top.wkbin.taixu.core.tools.admin.*
import top.wkbin.taixu.ui.components.RuntimeButton

/** A document result is native input, not a tool argument. Source is captured BEFORE opening picker. */
@Composable
fun NativePackageImportPanel() {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    val control=koinInject<SessionControl>()
    val sessions=koinInject<HarnessSessionRepository>()
    val ownership=koinInject<NativeSessionOwnership>()
    val bots=koinInject<PersonaBotRepository>()
    val tasks=koinInject<BotAssignmentRepository>()
    val prefs=koinInject<AiAdminPreferences>()
    val candidates=koinInject<NativePackageCandidateStore>()
    val plugins=koinInject<NativeReviewedPluginService>()
    val skills=koinInject<NativeReviewedSkillService>()
    val queue=koinInject<NativeManagementQueue>()
    var selection by remember { mutableStateOf<Triple<String,String,Long>?>(null) }
    var importing by remember { mutableStateOf(false) }
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val frozen=selection;selection=null
        if(uri!=null && frozen!=null) scope.launch {
            importing=true
            try {
                val controller=NativePackageDocumentController(context.contentResolver,candidates,plugins,skills,queue,
                    authorizeOwnerEpoch={ owner ->
                        val row=sessions.findById(owner)
                        val state=prefs.snapshot.first()
                        state.first && state.second==frozen.third && row?.runMode=="build" && ownership.owns(owner) &&
                            tasks.assignmentForSession(owner)==null && bots.getBotBySessionId(owner)==null &&
                            owner !in bots.observeGroupSessionRefs().first()
                    },snapshot={ prefs.snapshot.first() })
                controller.selectedDocumentFromNative(frozen.first,frozen.second,uri)
            } catch(cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch(_: Exception) { Toast.makeText(context,"文档未获准或审查未完成；未自动重试安装。",Toast.LENGTH_LONG).show() }
            finally { importing=false }
        }
    }
    fun choose(module: String) {
        if(importing || selection!=null) return
        scope.launch {
            val state=prefs.snapshot.first()
            if(!state.first) { Toast.makeText(context,"请先开启AI超管并选择受管用户会话。",Toast.LENGTH_LONG).show();return@launch }
            selection=Triple(control.currentSessionId.value,module,state.second)
            picker.launch(arrayOf("application/zip","application/octet-stream"))
        }
    }
    Column {
        Text("原生包文档审阅：选择文件后仍需确认摘要与权限。")
        RuntimeButton(onClick={ choose("process_plugin") }) { Text("选择进程插件包") }
        RuntimeButton(onClick={ choose("web_plugin") }) { Text("选择网页插件包") }
        RuntimeButton(onClick={ choose("skill") }) { Text("选择技能包") }
    }
}
