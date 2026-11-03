# Application module directory: real coverage and integration contract

## Scope and current wiring status

`AppModuleDirectory` is a **read-only catalogue**, not an invocation API, an authorization grant,
UI automation, a promise that a call succeeds, or the complete application/conversation API.
`AppModulesToolBackend` is now delegated from the existing `app_config` tool
(`action=modules|inspect_module`). The tool enum is unchanged, so no extra always-present schema is
added; `ToolExecutor` routes these read-only actions before the AI-admin switch and before any
configuration mutation/approval path. They require a valid bound native session. Model-callable
status is therefore "read-only catalogue available"; it is still not an invocation API.

No new always-present tool definition is required. Suggested requests after integration:

```json
{"action":"modules"}
{"action":"inspect_module","module":"native"}
{"action":"inspect_module","module":"plugin:<registered-plugin-id>"}
{"action":"inspect_module","module":"mcp:<enabled-service-id>"}
```

`modules` returns status/reason and tool IDs; `inspect_module` includes schemas where structurally
verified. Unknown module IDs, unknown actions, mutation arguments, and extra arguments are rejected.
There are no call/start/connect/discover/config-write methods. There is no persistent directory cache.
Each request reads fresh callbacks; these are independent source snapshots, not an atomic transaction.

## Constructor / backend contract for the integrating owner

```kotlin
AppModuleDirectory(
    visibleNativeToolNames: (suspend () -> Set<String>)? = null,
    registeredPluginsSnapshot: (suspend () -> List<AppModuleDirectory.RegisteredPlugin>)? = null,
    hostBackendBound: (suspend () -> Boolean)? = null,
    enabledMcpServicesSnapshot: (suspend () -> List<AppModuleDirectory.EnabledMcpService>)? = null,
    nativeDefinitions: () -> List<ApiToolDefinition> = { ProviderClient.TOOLS },
)
AppModulesToolBackend(directory).handles(action: String): Boolean
AppModulesToolBackend(directory).execute(args: JsonObject): Pair<Boolean, String>
```

- Bind callbacks at the session/request composition boundary. Do not inject `ToolExecutor` or a live
  `ProviderClient` into the directory; this avoids a backend → executor → backend DI cycle.
- `ProviderClient.TOOLS` is a public `val` on the public companion, despite the client's internal
  constructor. Its definitions can be read directly without constructing a ProviderClient.
- `visibleNativeToolNames` must reflect **this session's real visible native tool definitions**, after
  applicable mode, availability and Bot scope filtering. Do not use `HarnessTool.entries`, all enum
  names, a desired feature list, or the unrestricted Provider list as a visibility substitute.
  The directory intersects the callback names with real native definitions, excluding unknown names.
  Omitting visibility is intentionally fail-closed, not “all native tools available”.
- `nativeDefinitions` is for actual Provider definitions, not dynamically discovered MCP tools.
  Default it to `ProviderClient.TOOLS`; the override exists for explicit contracts and isolated tests.
- The plugin adapter is `RegisteredPlugin.fromRegisteredDescriptor(descriptor, verifiedToolSchemas)`.
  Obtain descriptors from `HarnessPluginRegistry.descriptors`: it contains the currently registered,
  validated contributions, including the registry's real external snapshot. TOOL extension IDs are
  selected structurally; PAGE/PROCESS/CONTEXT/PROVIDER/WORKFLOW/CHECKPOINT do not imply TOOL support.
  The capability server is exactly `plugin.${descriptor.id}` (version-pinned IDs are preserved).
- Registry currently exposes `descriptors`, `summaries()` and textual `inspect()`, but **no structured
  public tool/schema snapshot**. Do not parse `inspect()` (it truncates at 24,000 characters) and do not
  invent schemas from IDs. With only descriptors, schema status is `unknown`. If the composition owner
  already possesses the *same registered HarnessPlugin.tools* snapshot, it can supply a verified
  tool-ID → schema map; extra IDs are filtered out. This map must not come from unregistered manifests.
- `hostBackendBound` must reflect a real injected host backend, not a permission assumption. Host's
  deferred entries additionally require the currently visible `use_capability` proxy. Definitions
  come from `HostCapabilityDirectory.DEFERRED_ACTIONS`, `isHidden()` and `schemaFor()`. Direct host
  actions remain represented by the current native `host` Provider definition; deferred entries
  are not silently added to that direct schema. Host permissions/approvals are still checked on call.
- `enabledMcpServicesSnapshot` must read the effective, currently enabled, scope-filtered configured
  service IDs **without transport operations**. Return `EnabledMcpService(id)` only. Do not pass URLs,
  commands, arguments, env, headers, tokens, tool caches or credentials.
  `McpManager.enabledServerSummaries()` is **not appropriate for this strict contract**: although it
  does not start discovery, it invokes `sweepDisabledServers()` and can close transport connections.
  Other manager inspect/discover APIs can connect/start processes. Bind an already available,
  read-only effective enabled-service snapshot; otherwise leave this callback absent. A raw database
  row list alone is not evidence of effective catalogue availability or Bot-scope visibility.
- Callbacks must not perform side effects, discovery, tool execution or secrets retrieval. The
  directory has no discovery/execution dependency, but cannot make an impure injected callback pure.
  Keep this contract in composition tests as well as code review.

### app_config integration steps (outside this four-file implementation)

1. Add `modules` and `inspect_module` to the **existing** app_config action enum and optional `module`
   string property. Do not add a new top-level tool/enum. Do not embed all module/tool schemas in the
   Provider definition: inspect returns schemas only on demand.
2. Pass the `AppModulesToolBackend` into the relevant backend/executor composition using callbacks.
   Dispatch only those two actions using `handles(action)` and `execute(args)` **before** ordinary
   configuration mutation validation/proposal/application. Retain session/visibility and policy gates.
   Do not turn `modules` into `AppConfigService.list()` or accept model approval flags.
3. Add integration coverage for the Provider schema, ToolSchemaValidator, app_config routing,
   management gate and read-only approval policy. If policy action lists are explicit, classify these
   actions as catalogue reads; do not accidentally require or create a write grant. Do not loosen
   apply/propose rules or allow the AI to enable its own management permission.
4. Construct per-request visibility/scope callbacks. Verify any callback that accesses plugin package
   or effective MCP catalogue metadata has no lazy start/cleanup behavior. If unavailable, expose the
   documented `unsupported` binding reason rather than a synthetic list.

## Actual coverage matrix

| Directory module | Real source / surface | Limit |
| --- | --- | --- |
| `native` | Actual visible names ∩ Provider definitions | No enum-derived or guessed availability |
| `conversation_context` | history_search, history_read, compress when visible | Not session create/delete/export/model switching |
| `workspace` | read, write, edit, download, build_script when visible | Not the full Workspace UI or project administration API |
| `execution` | base, process when visible | Not terminal tabs or emulator state |
| `memory`, `planning` | memory, plan, scratchpad when visible | No fabricated private storage access |
| `agents` | invoke_subagent, invoke_dual_agent, team when visible | Existing contracts only; not every agent setting |
| `configuration` | app_config when visible | Existing allowlisted config only; no arbitrary preference read/write |
| `capabilities`, `host` | Visible native host/proxy and bound deferred Host directory | Registration does not confer Android privileges |
| `skills` | load_skill, load_rule when visible | Loading is not skill-package administration |
| `interaction` | ask_user, render_surface when visible | Not arbitrary application page manipulation |
| `web_search` | search_web when visible | Not the native browser-management UI |
| `plugins`, `plugin:<id>` | Real registry TOOL IDs; verified schemas only if supplied | Other plugin extension types are not tools; descriptor-only schema unknown |
| `mcp`, `mcp:<id>` | Enabled configured service IDs only | `enabled_uninspected`; no tool IDs, cache guesses, discovery or connection |
| `model_management` | UI-only / unsupported | No dedicated model/API-key CRUD conversation adapter |
| `session_management` | UI-only / unsupported | History tools are not full session lifecycle management |
| `workflow_management` | `app_config(module=workflow)` partial | Native-approved CAS save/delete, static safe run/stop/status; bound local Bash/Process/Build being integrated; Agent/Host/plugin remain pending |
| `bot_management` | `team` partial | Persistent team/member creation, drafts/review rules/publication, independent start/status/stop; not arbitrary persona CRUD |
| `git_management` | UI-only / unsupported | Shell git is possible under its own contract, not a dedicated module API |
| `browser_management` | UI-only / unsupported | Browser UI is not declared supported by search/download |
| `terminal_ui` | UI-only / unsupported | base/process are not terminal-tab management |
| `diagnostics_ui` | UI-only / unsupported | No dedicated diagnostics-page API in this directory |
| `permission_management` | UI-only / unsupported | No self-authorization; native permission UI remains user-controlled |

“UI-only / unsupported” means **no dedicated conversation adapter represented here**, not that a page
cannot be navigated via separately approved generic host GUI actions or a verified plugin. Those
mechanisms retain their own IDs and permissions; they must not be relabeled as dedicated module APIs.
The native aggregate intentionally preserves any real visible Provider tool not yet grouped above.
Static grouping is a coverage aid, not an allowlist of all possible app capabilities.

Statuses distinguish `available` native/Host references, `registered` plugin TOOL contributions,
`catalog_only` roots, `enabled_uninspected` MCP services and `unsupported` missing bindings/APIs.
`registered` does not mean every schema is known; `enabled_uninspected` explicitly does not claim
service tool availability. A successful empty plugins/MCP snapshot differs from a missing callback.
Exceptions are not echoed, failed callbacks degrade only their domain, cancellation propagates.

## Privacy and output boundaries

The result contains only module IDs/kinds/status/reasons, tool IDs, capability server IDs and verified
parameter schemas on inspection. It does not return descriptions from plugin inspect text, config
values, provider/model credentials, endpoint headers/URLs, process commands, environment, conversation
messages, grant records or tool results. Schema callbacks must supply actual parameter **definitions**,
never instance values/defaults populated from credentials. Errors do not echo callback exception text
or the user-supplied unknown ID. This catalogue does not bypass the existing execution pipeline,
schema validation, approvals, checkpoints, scope enforcement or redaction.

## Tests and validation status

`AppModuleDirectoryTest` covers missing binding degradation, real Provider-definition intersection,
compact listing vs inspected schemas, fresh visibility, Host binding/proxy gating, real registry TOOL
IDs without executing a plugin callback, verified schema filtering, enabled MCP IDs with no tool cache,
independent callback failures and error sanitization, unknown module rejection, rejecting mutation /
extra arguments before snapshot access, and cancellation propagation.

The code was inspected and whitespace/static dependency checks were performed. **No Gradle task or
unit-test runner was executed** in this implementation lane, per scope restrictions. Tests are added,
not claimed passing. End-to-end routing tests require the owner integration above. Additional future
work: a structured public registry schema snapshot and a provably side-effect-free effective MCP
service snapshot would reduce callback trust; neither is fabricated or added by modifying other files.
