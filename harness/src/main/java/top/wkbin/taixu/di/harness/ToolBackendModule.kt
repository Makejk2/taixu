package top.wkbin.taixu.di.harness

import org.koin.dsl.module
import kotlinx.coroutines.flow.first
import top.wkbin.taixu.core.database.repository.BotGroupSessionRepository
import top.wkbin.taixu.core.database.repository.PersonaBotRepository
import top.wkbin.taixu.core.security.SecretRedactor
import top.wkbin.taixu.harness.AskUserToolBackend
import top.wkbin.taixu.harness.ContextMemoryToolBackend
import top.wkbin.taixu.harness.DownloadToolBackend
import top.wkbin.taixu.harness.HarnessServiceToolBackend
import top.wkbin.taixu.harness.HostCapabilityToolBackend
import top.wkbin.taixu.harness.HostToolRequest
import top.wkbin.taixu.harness.LinuxCommandToolBackend
import top.wkbin.taixu.harness.PromptAssetToolBackend
import top.wkbin.taixu.harness.SearchWebToolBackend
import top.wkbin.taixu.harness.ToolExecutor
import top.wkbin.taixu.harness.ToolExecutionRequest
import top.wkbin.taixu.harness.ToolResult
import top.wkbin.taixu.harness.WorkspaceFileAccess
import top.wkbin.taixu.harness.WorkspaceToolBackend
import top.wkbin.taixu.harness.WorkspaceMutationSnapshots
import top.wkbin.taixu.harness.core.ToolCheckpoints
import top.wkbin.taixu.harness.directory.CapabilityToolGateway

internal val toolBackendModule = module {
    single<top.wkbin.taixu.runtime.environment.ExecutionEnvironmentFactory>(org.koin.core.qualifier.named("local-execution")) {
        top.wkbin.taixu.harness.environment.LocalExecutionEnvironmentFactory(get(), get(), get(), get(), get(), get())
    }
    single {
        val selection = get<top.wkbin.taixu.runtime.environment.LocalDistributionSelection>()
        top.wkbin.taixu.harness.environment.SessionExecutionTargets(get(), get(), selection::capture)
    }
    single<top.wkbin.taixu.runtime.environment.ExecutionEnvironmentFactory> {
        top.wkbin.taixu.harness.environment.SelectedExecutionEnvironmentFactory(
            get(org.koin.core.qualifier.named("local-execution")), get(), get(),
            get(),
        )
    }
    single { top.wkbin.taixu.harness.environment.SessionExecutionEnvironments(get()) }
    single { get<top.wkbin.taixu.harness.plugins.HarnessPluginRegistry>().checkpoints() }
    single { WorkspaceMutationSnapshots(store = get(), events = get()) }
    single {
        val files = get<WorkspaceFileAccess>()
        WorkspaceToolBackend(
            operationsFor = { workspace -> if (workspace.isNotBlank()) files.withBase(workspace) else files },
            snapshots = get(),
            environments = get(),
        )
    }
    // 领域能力后端：各域独立持有自己的运行时依赖，ToolExecutor 只做管道编排与路由。
    single {
        HostCapabilityToolBackend(
            privilegeManager = get(),
            androidAppManager = get(),
            androidAppRepository = get(),
            shizukuApis = get(),
            hostGuiController = get(),
            virtualDisplayCoordinator = get(),
            virtualScreenToolkit = get(),
            providerClient = get(),
            settingsDataStore = get(),
            phoneAgentServices = get(),
            embeddedAdbManager = get(),
            secretRedactor = get(),
            eventBus = get(),
        )
    }
    single {
        LinuxCommandToolBackend(
            linuxRuntime = get(),
            pathResolver = get(),
            settingsDataStore = get(),
            workflowSignals = get(),
            environments = get(),
        )
    }
    single {
        DownloadToolBackend(
            fileDownloader = get(),
            fileAccess = get(),
            mutationSnapshots = get(),
            environments = get(),
        )
    }
    single {
        top.wkbin.taixu.harness.websearch.WebSearchProviders(
            listOf(top.wkbin.taixu.harness.websearch.BingSearchProvider(get<okhttp3.OkHttpClient>())),
        )
    }
    single {
        SearchWebToolBackend(providers = get())
    }
    single {
        ContextMemoryToolBackend(
            messageStore = get(),
            compactionManager = get(),
            sessionDao = get(),
            providerClient = get(),
        )
    }
    single {
        AskUserToolBackend(
            approvalPolicyEngine = get(),
            approvalRepository = get(),
        )
    }
    single {
        PromptAssetToolBackend(
            skillRepository = get(),
            promptRouter = get(),
        )
    }
    single {
        HarnessServiceToolBackend(
            contextExecutor = get(),
            subagentOrchestrator = get(),
            dualAgentCoordinator = get(),
            buildScriptToolExecutor = get(),
            blockSubagentInGroupMain = { sessionId, rawToolName ->
                val bot = get<PersonaBotRepository>().getBotBySessionId(sessionId)
                val groupId = get<BotGroupSessionRepository>().groupIdForSession(sessionId)
                if (bot?.isMainBot == true && groupId != null) {
                    "群聊主控不能用 ${rawToolName ?: "invoke_subagent"} 替代成员派单；" +
                        "请使用 bot-management 的 assign-task，将任务分别派给不同群成员（同一轮可多次调用，wait=false）。"
                } else {
                    null
                }
            },
        )
    }
    // 能力统一网关：use_capability 分发 + codemode 脚本装配 + legacy mcp__ 直连回退。
    single<top.wkbin.taixu.harness.bot.BotCapabilityScope> {
        // Bot 级 MCP 能力白名单（DS Bot 能力库）：Bot 未配置白名单时返回 null = 不限制。
        // 条目按服务 id 与名称双向展开：挂载侧（@ 提及）id/name 都认，执行侧（use_capability/
        // legacy mcp__）此前只认 id；统一后避免"list 按名称命中、call 用 id 被拒"的可见不可调。
        top.wkbin.taixu.harness.bot.BotCapabilityScope { sessionId ->
            val entries = get<PersonaBotRepository>().getBotBySessionId(sessionId)?.capabilityServerNames
            if (entries == null) {
                null
            } else {
                val servers = runCatching {
                    get<top.wkbin.taixu.core.database.McpServerRepository>().servers.first()
                }.getOrDefault(emptyList())
                val allowed = mutableSetOf<String>()
                entries.forEach { entry ->
                    val lower = entry.lowercase()
                    val matched = servers.firstOrNull {
                        it.id.lowercase() == lower || it.name.lowercase() == lower
                    }
                    if (matched == null) {
                        allowed += lower
                    } else {
                        allowed += matched.id.lowercase()
                        allowed += matched.name.lowercase()
                    }
                }
                allowed
            }
        }
    }
    single {
        CapabilityToolGateway(
            mcpManager = get(),
            plugins = get(),
            argRedactor = { value: String -> get<SecretRedactor>().redact(value) },
            toolPolicy = get(),
            capabilityScope = get(),
            hostExecutor = { args, operationId, sessionId, metadata ->
                get<HostCapabilityToolBackend>().execute(HostToolRequest(args, operationId, sessionId, metadata))
            },
        )
    }
    // 自动会话标题：模型解析与补全走 ProviderClient 的窄端口（测试用 lambda 打桩）。
    single<top.wkbin.taixu.harness.session.SessionTitleModelPort> {
        val providerClient = get<top.wkbin.taixu.harness.ProviderClient>()
        top.wkbin.taixu.harness.session.SessionTitleModelPort { modelId, variant, messages ->
            providerClient.chat(providerClient.resolveConfigured(modelId, variant), messages).content
        }
    }
    single<top.wkbin.taixu.harness.session.SessionTitleLog> {
        val appLogger = get<top.wkbin.taixu.core.common.logging.AppLogger>()
        object : top.wkbin.taixu.harness.session.SessionTitleLog {
            override fun info(message: String) = appLogger.i(message)
            override fun error(message: String, failure: Throwable) = appLogger.e(message, failure)
        }
    }
    single {
        top.wkbin.taixu.harness.session.SessionTitleGenerator(
            sessions = get(),
            modelPort = get(),
            log = get(),
        )
    }
    single { top.wkbin.taixu.harness.config.createAppConfigBackend(top.wkbin.taixu.core.datastore.AgentConfigPreferences(get()), get()) }
    single { top.wkbin.taixu.harness.team.createTeamBackend(get(), get(), get(), get(), { get() }, { get() }, { get() }, get(), sessions = get(),
        suggestions = get(), managementEnabled = { top.wkbin.taixu.core.datastore.AiAdminPreferences(get()).enabled.first() }) }
    single { top.wkbin.taixu.harness.admin.createBoundAppAdminBackend(
        get(), get(), get(), get(), get(), { get() }, { get() }, get(), runtimeRepository = get(), models = get(), remoteServers = get(), plugins = { get() }) }
    single { top.wkbin.taixu.harness.admin.model.createModelManagementBackend(get(),
        top.wkbin.taixu.harness.admin.model.NativeModelAdminAuthority(get(), get(), get(), get()), get()) }
    single { top.wkbin.taixu.harness.admin.createSessionManagementBackend(get(), get(), get(), get(), { get() },
        nativeOwnership = { id -> get<top.wkbin.taixu.harness.admin.NativeSessionOwnership>().owns(id) }, persistence = get()) }
    single { top.wkbin.taixu.harness.admin.rules.UserRulesBackend(get(), object : top.wkbin.taixu.harness.admin.rules.RuleManagementAuthority {
        private val native = top.wkbin.taixu.harness.admin.model.NativeModelAdminAuthority(get(), get(), get(), get())
        override suspend fun requireNativeMainActor(actor: top.wkbin.taixu.harness.admin.rules.RuleManagementActor) {
            check(native.isControlledUserSession(top.wkbin.taixu.harness.admin.model.ModelAdminActor(actor.sessionId, actor.operationId, "rules")))
        }
    }) }
    single { top.wkbin.taixu.harness.rules.UserRuleToolGuard(get(), get(), get()) }
    single {
        val sessions = get<top.wkbin.taixu.core.database.HarnessSessionRepository>()
        top.wkbin.taixu.harness.admin.runtime.createRuntimeAdmin(get(), get(),
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO),
            top.wkbin.taixu.harness.admin.model.NativeModelAdminAuthority(get(), get(), get(), get()),
            workspace = { id -> requireNotNull(sessions.findById(id)).workspace.also { require(it.isNotBlank()) } },
            journal = top.wkbin.taixu.harness.admin.runtime.RuntimeOperationJournal(java.io.File(get<android.content.Context>().filesDir, "management-runtime-operations")))
    }
    single {
        top.wkbin.taixu.harness.admin.runtime.NativeRuntimeImportController(
            get<android.content.Context>().contentResolver,
            top.wkbin.taixu.runtime.admin.PrivateNativeImportStore(
                java.io.File(get<android.content.Context>().filesDir, "management-runtime-imports")),
            get(),
            get(), get(), get(), get(), get(),
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO),
        )
    }
    single {
        val native = top.wkbin.taixu.harness.admin.model.NativeModelAdminAuthority(get(),get(),get(),get())
        top.wkbin.taixu.harness.admin.packages.createPackageManagement(get(),get(),get(),get(),get(),get(),get(),
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO),
            object : top.wkbin.taixu.harness.admin.packages.PackageAdminAuthority {
                override suspend fun isNativeMainBuild(actor: top.wkbin.taixu.harness.admin.packages.PackageAdminActor) =
                    native.isControlledUserSession(top.wkbin.taixu.harness.admin.model.ModelAdminActor(actor.sessionId,actor.operationId,"package")) &&
                        !native.isPlan(top.wkbin.taixu.harness.admin.model.ModelAdminActor(actor.sessionId,actor.operationId,"package"))
            }, skillRoot = java.io.File(get<top.wkbin.taixu.runtime.RuntimePathManager>().attachmentsDir,"skills"),
            registryPreferences = top.wkbin.taixu.core.datastore.RegistryPreferences(get()),
            nativeSkillRoots = {
                val paths=get<top.wkbin.taixu.runtime.RuntimePathManager>()
                top.wkbin.taixu.core.database.AgentSkillRepository.standardScanRoots(paths.attachmentsDir,paths.workspaceDir)
            })
    }
    single { top.wkbin.taixu.core.tools.admin.NativePackageCandidateStore(java.io.File(get<android.content.Context>().filesDir,"management-package-candidates")) }
    single { top.wkbin.taixu.core.tools.admin.NativeReviewedPluginService(get(),get(),
        top.wkbin.taixu.core.tools.admin.PackageOperationJournal(java.io.File(get<android.content.Context>().filesDir,"management-packages/native-plugin"))) }
    single { top.wkbin.taixu.core.tools.admin.NativeReviewedSkillService(get(),get(),get(),
        java.io.File(get<top.wkbin.taixu.runtime.RuntimePathManager>().attachmentsDir,"skills"),
        top.wkbin.taixu.core.tools.admin.PackageOperationJournal(java.io.File(get<android.content.Context>().filesDir,"management-packages/native-skill"))) }
    single { top.wkbin.taixu.core.datastore.AiAdminPreferences(get()) }
    single { top.wkbin.taixu.harness.admin.nativeui.NativeManagementQueue() }
    single { top.wkbin.taixu.harness.admin.model.NativeModelEditorRequests() }
    single<top.wkbin.taixu.harness.admin.model.ModelNativeFormBridge> {
        val prefs = top.wkbin.taixu.core.datastore.AiAdminPreferences(get())
        val sessions = get<top.wkbin.taixu.core.database.HarnessSessionRepository>()
        val ownership = get<top.wkbin.taixu.harness.admin.NativeSessionOwnership>()
        val bots = get<top.wkbin.taixu.core.database.repository.PersonaBotRepository>()
        val tasks = get<top.wkbin.taixu.core.database.repository.BotAssignmentRepository>()
        val editors = get<top.wkbin.taixu.harness.admin.model.NativeModelEditorRequests>()
        top.wkbin.taixu.harness.admin.model.ModelNativeFormBridge(
            launcher = top.wkbin.taixu.harness.admin.nativeui.QueuedModelNativeFormLauncher(get(), { get() },
                { request -> editors.openFromNative(request) }),
            authorize = { id ->
                val row = sessions.findById(id)
                row != null && row.runMode == "build" && ownership.owns(id) &&
                    tasks.assignmentForSession(id) == null && bots.getBotBySessionId(id) == null &&
                    id !in bots.observeGroupSessionRefs().first()
            },
            managementSnapshot = { prefs.snapshot.first() },
            withNativeWriteBoundary = { block -> prefs.withManagementSnapshot { live ->
                check(live.first); block()
            } })
    }

    single<ToolExecutor> {
        ToolExecutor(
            fileAccess = get(),
            pathResolver = get(),
            approvalPolicyEngine = get(),
            secretRedactor = get(),
            hostToolBackend = get(),
            linuxCommandToolBackend = get(),
            downloadToolBackend = get(),
            searchWebToolBackend = get(),
            contextMemoryToolBackend = get(),
            askUserToolBackend = get(),
            promptAssetToolBackend = get(),
            harnessServiceToolBackend = get(),
            capabilityToolGateway = get(),
            linuxEnvironmentManager = get(),
            approvalRepository = get(),
            sessionDao = get(),
            mcpManager = get(),
            mcpToolPolicy = get(),
            eventBus = get(),
            checkpointStore = get(),
            sessionApprovalGrants = get(),
            approvalAuditor = get(),
            settingsDataStore = get(),
            toolCheckpoints = get(),
            workspaceToolBackend = get(), governanceGuard = get(),
            environments = get(),
            appConfigBackend = get(), teamBackend = get(), workflowAdminBackend = get(), modelAdminBackend = get(), nativeModelForms = get(), sessionManagementBackend = get(), userRulesBackend = get(), userRuleGuard = get(), runtimeAdmin = get(), packageAdmin = get(),
            managementActorGuard = top.wkbin.taixu.harness.admin.ManagementActorGuard(get()),
            appModulesBackend = { id ->
                val session = requireNotNull(get<top.wkbin.taixu.core.database.HarnessSessionRepository>().findById(id))
                val model = get<top.wkbin.taixu.harness.ProviderClient>().resolveConfigured(session.modelId, session.modelVariant)
                top.wkbin.taixu.harness.directory.AppModulesToolBackend(top.wkbin.taixu.harness.directory.AppModuleDirectory(
                    visibleNativeToolNames = { if (model.pureChatMode) emptySet() else top.wkbin.taixu.harness.ProviderClient.buildDynamicTools(model).map { it.function.name }.toSet() },
                    registeredPluginsSnapshot = {
                        val registry = get<top.wkbin.taixu.harness.plugins.HarnessPluginRegistry>()
                        registry.descriptors.map { descriptor ->
                            top.wkbin.taixu.harness.directory.AppModuleDirectory.RegisteredPlugin.fromRegisteredDescriptor(
                                descriptor,
                                registry.registeredToolSchemas(descriptor.id),
                            )
                        }
                    },
                    hostBackendBound = { true },
                    // MCP effective availability may run native probes; leave unbound rather than start anything here.
                ))
            },
            aiAdminBoundary = { block -> top.wkbin.taixu.core.datastore.AiAdminPreferences(get()).withManagementSnapshot(block) },
        )
    }

}
