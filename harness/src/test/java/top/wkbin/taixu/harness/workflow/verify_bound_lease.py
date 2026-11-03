"""Offline focused JVM verification; production scheduler/lease/executors, isolated Android boundaries."""
import glob, os, subprocess, tempfile, shutil, sys
cache='/root/.gradle/caches/modules-2/files-2.1/'
def jars(p): return glob.glob(cache+p)
compiler=sum([jars(p) for p in ['org.jetbrains.kotlin/kotlin-compiler-embeddable/2.4.10/*/*.jar','org.jetbrains.kotlin/kotlin-stdlib/2.4.10/*/*.jar','org.jetbrains.kotlin/kotlin-reflect/2.4.10/*/*.jar','org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.8.0/*/*.jar','org.jetbrains/annotations/13.0/*/*.jar']],[])
cp=sum([jars(p) for p in ['org.jetbrains.kotlin/kotlin-stdlib/2.4.10/*/*.jar','org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.8.0/*/*.jar','org.jetbrains/annotations/13.0/*/*.jar','junit/junit/4.13.2/*/*.jar','org.hamcrest/hamcrest-core/1.3/*/*.jar']],[])+glob.glob('/workspace/.tmp_att/config-test-deps/*.jar')
root=tempfile.mkdtemp(prefix='verify-bound-lease-')
def read(p): return open(p,encoding='utf-8-sig').read()
def put(name,s):
 p=root+'/'+name+'.kt';open(p,'w').write(s);return p
try:
 sources=glob.glob('core/model/src/main/java/**/*.kt',recursive=True)+glob.glob('core/common/src/main/java/top/wkbin/taixu/core/common/result/*.kt')
 env='runtime/src/main/java/top/wkbin/taixu/runtime/environment/'
 sources += [env+n+'.kt' for n in ['ExecutionEnvironment','ExecutionEnvironmentContext','ExecutionArtifacts','ExecutionDownloads','ExecutionBuildBindings','FileFingerprint']]
 shell='runtime/src/main/java/top/wkbin/taixu/runtime/shell/'
 sources += [shell+n+'.kt' for n in ['CommandResult','ShellCommand','LinuxSession','InteractiveLaunchSpec']]
 registry=read(shell+'ProcessRegistry.kt')
 sources += [put('ProcessTypes','package top.wkbin.taixu.runtime.shell\n'+registry[registry.index('enum class ProcessType'):registry.index('interface ProcessRegistry')]), put('Capture','package top.wkbin.taixu.runtime.shell\ninterface CommandOutputCapture')]
 runtime=read('runtime/src/main/java/top/wkbin/taixu/runtime/LinuxRuntime.kt')
 sources += [put('LinuxRuntime',runtime), put('RuntimeTypes','''package top.wkbin.taixu.runtime
class RuntimeInstallRequest(val distribution:String)
class RootfsUpdateInfo
class RuntimeHealth
class DownloadProgress
''')]
 wf='harness/src/main/java/top/wkbin/taixu/harness/workflow/'
 names=['WorkflowExecutionLease','WorkflowExecutionBindings','WorkflowScheduler','WorkflowApprovalBroker','WorkflowDagDispatcher','WorkflowJournalRecorder','WorkflowExpansionApproval','WorkflowInterpolation','BuiltinNodeExecutors','NodeExecutor','WorkflowRunManager','WorkflowHistoryWriter']
 sources += [wf+n+'.kt' for n in names]
 sources += ['harness/core/src/main/java/top/wkbin/taixu/harness/core/'+n+'.kt' for n in ['SessionResourceScope','ResourceDisposal','ResourceCleanupException']]
 sources += ['core/common/src/main/java/top/wkbin/taixu/core/common/shell/ShellQuote.kt','runtime/src/main/java/top/wkbin/taixu/runtime/StorageActivityGate.kt','runtime/src/test/java/top/wkbin/taixu/runtime/StorageActivityGateTest.kt']
 agent=read(wf+'WorkflowAgentExecutionPort.kt'); agent=agent[agent.index('data class WorkflowAgentRequest'):agent.index('/**\n * Runs workflow agents')]
 sources += [put('AgentPort','package top.wkbin.taixu.harness.workflow\n'+agent),put('Virtual','''package top.wkbin.taixu.harness.workflow
import kotlinx.coroutines.Job
import top.wkbin.taixu.runtime.virtualdisplay.PhoneTaskState
class VirtualScreenWorkflowRuns { fun begin(id:String, job:Job) {} ; fun end(id:String,state:PhoneTaskState) {} }
'''),put('Phone','package top.wkbin.taixu.runtime.virtualdisplay\nenum class PhoneTaskState { COMPLETED, CANCELLED, FAILED }'),put('Logger','package top.wkbin.taixu.core.common.logging\nclass AppLogger(context: Any? = null, redactor: (String) -> String = { it }) { fun e(message:String, error:Throwable) {} }')]
 sources += [put('Repository','''package top.wkbin.taixu.core.database
import top.wkbin.taixu.core.model.workflow.*
interface WorkflowRepository {
 suspend fun findExecutionById(id:String): WorkflowExecutionLogEntity?
 suspend fun findUnfinishedExecutions():List<WorkflowExecutionLogEntity>
 suspend fun saveExecution(state:WorkflowRuntimeState,source:String,scheduleId:String?)
}
data class WorkflowExecutionLogEntity(val executionId:String,val finalContextJson:String,val scheduleId:String?,val triggerSource:String)
''')]
 sources += [os.path.dirname(__file__)+'/WorkflowExecutionLeaseTest.kt']
 sources += [put('AndroidContext','package android.content\nclass ContextWrapper(context: Any?)')]
 regression=read(os.path.dirname(__file__)+'/WorkflowRunManagerTest.kt').split('class WorkflowCleanupRunManagerTest {',1)[1]
 imports=read(os.path.dirname(__file__)+'/WorkflowExecutionLeaseTest.kt').split('class WorkflowExecutionLeaseTest',1)[0]
 imports += 'import kotlinx.coroutines.flow.MutableStateFlow\nimport top.wkbin.taixu.core.model.RuntimeState\nimport top.wkbin.taixu.core.database.WorkflowRepository\nimport top.wkbin.taixu.core.common.logging.AppLogger\nimport android.content.ContextWrapper\n'
 sources += [put('WorkflowCleanupRunManagerTest',imports+'class WorkflowCleanupRunManagerTest {'+regression)]

 cmd=['java','-Xmx1200m','-cp',':'.join(compiler),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17','-classpath',':'.join(cp),'-Xplugin=/workspace/.tmp_att/team-local-verification-522d8af0/serialization-plugin.jar','-d',root+'/classes']+sources
 r=subprocess.run(cmd,text=True,capture_output=True);print(r.stdout,r.stderr)
 if r.returncode:sys.exit(r.returncode)
 r=subprocess.run(['java','-cp',root+'/classes:'+':'.join(cp),'org.junit.runner.JUnitCore','top.wkbin.taixu.harness.workflow.WorkflowExecutionLeaseTest','top.wkbin.taixu.harness.workflow.WorkflowCleanupRunManagerTest','top.wkbin.taixu.runtime.StorageActivityGateTest'],text=True,capture_output=True);print(r.stdout,r.stderr);sys.exit(r.returncode)
finally: shutil.rmtree(root)
