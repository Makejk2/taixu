package top.wkbin.taixu.harness.admin

/** Backed by native creation/explicit claim rows. Missing provenance is not USER. */
fun interface NativeSessionOwnership { suspend fun owns(id: String): Boolean }
