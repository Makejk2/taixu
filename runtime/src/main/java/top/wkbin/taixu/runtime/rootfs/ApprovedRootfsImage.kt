package top.wkbin.taixu.runtime.rootfs

import top.wkbin.taixu.runtime.DistributionSpec

/** The digest is sent to the registry request and verified before layer activation, not checked
 * once then discarded. Pinned references disable mutable-tag fallback in RootfsInstaller. */
internal fun DistributionSpec.approvedImage(digest: String?): DistributionSpec {
    if (digest == null) return this
    require(digest.matches(Regex("sha256:[a-f0-9]{64}"))) { "批准的镜像摘要格式无效" }
    val parsed = OciImageReference.parse(imageReference)
    return copy(imageReference = "${parsed.repo}@$digest")
}
