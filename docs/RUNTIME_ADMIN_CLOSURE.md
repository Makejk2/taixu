# Runtime / terminal owner transaction closure

## Implemented in this pass (source review only)

- `LinuxRuntimeImpl`: native switch/install/import/uninstall/reset/update/initialize/restore/shutdown/storage cleanup advance `managementGeneration` while holding the existing `initializeMutex`, before potentially partial mutation. Native install/import now use the same maintenance admission as approved install/import. Approved mutations still call locked helpers (no recursive mutex or adapter CAS); fixed manifest and verified archive handles are retained.
- Runtime snapshot mismatch now throws `AdminSnapshotChanged`, matching the adapter's `snapshot_changed` receipt instead of incorrectly becoming `unknown_partial`.
- `TerminalSessionManager`: restoration/retry/removal and approved recovery reserve the existing owner state; a private explicitly passed recovery token replaces Job parent/child authorization. No ThreadLocal or suspend inside a JVM monitor is introduced.
- Native close reserves resources and records an in-flight native disposal count before suspension. Approved mutation/restoration refuses active creation/disposal, and generation advances on registration/publication/failure/removal. Completed failed disposal remains retryable.
- Manager-routed native input checks and dispatches in one synchronous owner critical section, consuming generation before dispatch (also on partial failure). Approved input likewise consumes before dispatch. Native switching rejects closing resources.
- No test, compiler, CI, install, commit, SDK edit or sub-delegation was performed. Only source/CodeGraph inspection and `git diff --check` were used; these do not prove runtime correctness.

## NOT complete: raw native UI writers

`feature/terminal/.../TaiXuTerminalBridge.kt:67` and `TaiXuTerminalHost.kt:142` call raw `TerminalSession.write`; `TerminalScreen.kt:211` attaches raw sessions to Termux TerminalView. These bypass `TerminalSessionManager.write` and therefore its reservation/generation. `termux-terminal-emulator/.../TerminalSession.java` is final and has no input interception hook in the inspected source. Owner-only edits cannot intercept those calls. UI call sites are outside this lane's write scope, and SDK modification is prohibited. Route *all* IME/paste/key/TerminalView input through the owner, or provide an explicitly approved interceptable input boundary, before claiming full native-UI CAS protection. Merely changing ViewModel input does not close this gap.

## Actual production DI and import boundaries

- `runtime/src/main/java/top/wkbin/taixu/di/runtime/KoinModule.kt:362`: existing `single<TerminalSessionManager>` owns the same runtime/environment/local+remote factories; no new local adapter mutex or second owner was introduced.
- `harness/src/main/java/top/wkbin/taixu/di/harness/ToolBackendModule.kt:211`: existing `createRuntimeAdmin(get(), get(), ...)` wires runtime and terminal singletons, app-lived scope, native authority, session workspace resolver, and the persistent operation journal. No DI changes are needed for the owner method changes here.
- `harness/.../admin/runtime/RuntimeAdminFactory.kt:24` exposes `proposeImport(actor, request, NativeImportHandle)`; the inspected app/harness sources have no caller of that entry and no concrete NativeImportPicker integration. A native picker must stage and verify an immutable private archive handle, then create a proposal; selected/queued is not imported/completed. Do not replace this with model paths/URIs.
- Existing adapter operation lifecycle and journal recovery are untouched: queued/running recovery remains `unknown_partial`, never replayed. Import handle verification and final cleanup remain in place.

## Remaining verification (not run)

Exercise native A→B→A against stale distro approval; native close/create/publication against approved terminal close/input; approved recovery across coroutine context changes; write dispatch failure and cancellation; failed-disposal retry; and raw UI input integration after its out-of-scope wiring. Workspace identity/environment changes are not represented by a real generation in current snapshots (`workspaceGeneration=0`, terminal environment generation aliases owner generation); these still require a separate owner-source audit before claiming cross-owner atomicity.
