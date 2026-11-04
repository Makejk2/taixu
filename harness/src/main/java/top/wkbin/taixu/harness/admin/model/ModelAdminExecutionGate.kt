package top.wkbin.taixu.harness.admin.model

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** Register ahead of generic approvals/FULL_ACCESS/grants. `nativeReplay` MUST originate from
 * native approval continuation, not from arguments. Native callbacks retain original actor callId.
 * Returning ApprovalRequired is not execution: caller must render native approval, then replay.
 */
suspend fun executeModelAdmin(
    backend: ModelAdminBackend,
    actor: ModelAdminActor,
    args: JsonObject,
    nativeReplay: Boolean = false,
): ModelAdminResult = try {
    require(ModelAdminBackend.text(args, "module") == "model")
    if (ModelAdminBackend.text(args, "action") == "apply") {
        ModelAdminBackend.keys(args, "module", "action", "proposalId")
        val id = ModelAdminBackend.text(args, "proposalId")
        if (nativeReplay) backend.service.applyNative(actor, id) else backend.service.prepare(actor, id)
    } else {
        require(!nativeReplay)
        backend.execute(actor, args)
    }
} catch (e: CancellationException) { throw e }
catch (_: Exception) { ModelAdminResult.Rejected("MODEL_ADMIN_REJECTED") }
