package top.wkbin.taixu.runtime.admin

import top.wkbin.taixu.runtime.RuntimeInstallRequest

/** Native-frozen requests, not arbitrary callbacks executed under the runtime owner mutex. */
sealed interface ApprovedRuntimeMutation {
    val target: String
    data class Install(val request: RuntimeInstallRequest) : ApprovedRuntimeMutation {
        override val target get() = request.distributionId
    }
    data class Import(val request: RuntimeInstallRequest, val archive: NativeImportHandle) : ApprovedRuntimeMutation {
        override val target get() = request.distributionId
    }
    data class Switch(override val target: String) : ApprovedRuntimeMutation
    data class Update(override val target: String, val manifestDigest: String) : ApprovedRuntimeMutation
    data class Uninstall(override val target: String) : ApprovedRuntimeMutation
    data class Reset(override val target: String) : ApprovedRuntimeMutation
}
