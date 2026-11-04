# Native management UI integration (implementation, not verified)

No build, test, install, external operation or CI was run for this change.

## Implemented

- `NativeManagementQueue`: bounded, process-memory StateFlow of metadata and typed actions. Private one-shot handlers, duplicate click suppression, expiry checked on click, failure -> UNKNOWN_PARTIAL without raw exception text. No credential fields, tool registration or journal serialization.
- `QueuedModelNativeFormLauncher`: queues a real ModelNativeFormRequest, opens native editor only on user click, marks OPENING, never APPLIED merely because navigation returned.
- `enqueueSessionFromNative`: invokes real NativeSessionRecovery typed claim/load/cleanup APIs.
- `ManagementRequestPanel(queue: NativeManagementQueue, modifier: Modifier = Modifier)`: RuntimeCard/buttons and source/reason/risk/state display. Put in the native host's scrolling surface.
- `NativePackageDocumentController.selectedDocumentFromNative(owner: String, module: String, uri: Uri)`: copies native picker content stream to NativePackageCandidateStore (32 MiB conservative limit), inspects real plugin/skill review, queues exact digest/permissions, arms/commits typed native services on confirmation, discards staged bytes on cancellation or inspection failure.

## Required host changes — NOT completed here

These files belong to other owners; this change deliberately does not invent authorization or a writer callback.

1. Application DI/runtime composition: create one lifecycle-owned `NativeManagementQueue`; inject the SAME instance into launcher and settings host. Do not register queue/controller/confirm methods as harness tools. Process recreation discards this queue; do not replay requests.
2. Runtime supplies `authorizeOwnerEpoch: suspend (String) -> Boolean` bound to trusted native session ownership, non-PLAN policy and captured epoch, revalidated at commit. Never derive owner/epoch from model document metadata. Session recovery's own `authorize` callback must use the same trusted boundary.
3. Model bridge circular composition signature:
   ```kotlin
   lateinit var bridge: ModelNativeFormBridge
   val launcher = QueuedModelNativeFormLauncher(queue, { bridge }) { request ->
       // Navigate to existing ModelEditor, retaining request in native controller only.
       // CREATE / CHANGE_CREDENTIALS use its credential fields; DELETE needs native delete UI.
   }
   bridge = ModelNativeFormBridge(launcher, authorizeSourceSession)
   ```
   The navigation lambda above is an INTEGRATION POINT, not an implemented editor. Do not mark the page operational until the next step is wired.
4. Existing ModelEditor save/delete path must call `bridge.submitFromNative(request.requestId) { request -> ... }` around an actual awaited AiProfileWriter operation inside runtime lifecycle + snapshot-CAS boundary. Then map returned bridge state to `queue.finishModelFromNative(request.requestId, ManagementState.APPLIED)`; cancellation/expiry/failure maps to corresponding terminal state only after consulting actual bridge status. Existing SettingsViewModel.saveModel/saveModels launch jobs and return before completion; they MUST NOT be passed as the awaited write or treated as APPLIED. Add an awaited native writer path at the owner boundary, not a fake navigation-success callback. Keys/URLs/headers remain in native editor; never put them in queue/journal.
5. Current ModelNativeFormBridge has no per-request cancellation API: launcher cancellation calls existing `invalidateSession(sourceSessionId)`, cancelling ALL pending forms for that source. Other queue rows may remain visually pending but bridge refuses their submit. Bridge owner should add typed `cancelFromNative(requestId)` and synchronize corresponding queue rows, or explicitly enforce one pending form per source. No misleading per-row cancellation guarantee is claimed.
6. Host Compose placement:
   ```kotlin
   ManagementRequestPanel(queue = nativeManagementQueue)
   ```
   Add to SettingsScreen / shared native management surface, not tool chat messages. Panel itself does not navigate, instantiate DI, or mint trust.
7. Host `rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument())` callback launches lifecycle coroutine and calls `documentController.selectedDocumentFromNative(trustedOwner, nativeSelectedModule, uri)`. Use native choices `process_plugin`, `web_plugin`, `skill`, never model paths/URIs. Instantiate store under `File(context.filesDir, "native-package-candidates")`. Handle picker/inspection failures with a local sanitized snackbar. No persistence permission needed because copying completes immediately. Cancelled package reviews retain an inaccessible service review entry until service lifecycle ends; staged bytes are discarded, so it cannot commit.
8. Session recovery enqueue calls are native-only; runtime must preserve source protection and exclusivity. UNKNOWN_PARTIAL requires manual reconciliation, not retry. OPENING rows require editor-close/save integration; they intentionally cannot be dismissed as completed.

## Dependency boundary

Read actual `feature/settings/build.gradle.kts`: it already depends on tools, harness, runtime and activity-compose. Queue lives in harness with no Compose or tools imports. No Gradle/Room dependency was added. SettingsViewModel was not modified because its current asynchronous save API does not establish awaited CAS completion.

## Limitations

Host placement, DI, runtime ownership/epoch callback, native editor submission/cancellation, background expiry sweeper/session invalidation fanout and service review cancellation remain integration work. Expiry is checked when a row is acted on (no timer). Result exceptions are conservatively UNKNOWN_PARTIAL; use underlying service journal for manual reconciliation. No claim of end-to-end working management page or tested compilation is made.
