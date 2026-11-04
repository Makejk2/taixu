package top.wkbin.taixu.core.tools

/** Captured installed metadata, including absence. Checked under the lifecycle owner lock. */
data class ApprovedPackageState(val installedVersion: String?, val state: String?)
