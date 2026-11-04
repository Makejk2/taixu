package top.wkbin.taixu.core.tools.skill

import top.wkbin.taixu.core.common.result.AppError
import top.wkbin.taixu.core.common.result.AppResult
import top.wkbin.taixu.core.common.result.ErrorCode
import top.wkbin.taixu.core.database.AgentSkillRepository
import top.wkbin.taixu.core.model.AgentSkill
import top.wkbin.taixu.core.model.skill.AuditLevel
import top.wkbin.taixu.core.model.skill.SecurityAuditReport
import top.wkbin.taixu.core.model.skill.SkillCompatibilityResult
import top.wkbin.taixu.core.model.skill.SkillPackage
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.sync.withLock

/**
 * 技能安装审查上下文（包含解构后的技能包、端侧静态安全审计报告与兼容性评估结果）。
 */
data class SkillInstallInspection(
    val packageId: String,
    val pkg: SkillPackage,
    val auditReport: SecurityAuditReport,
    val compatibilityResult: SkillCompatibilityResult,
) {
    val isBlocked: Boolean get() = auditReport.isBlocked
    val canProceed: Boolean get() = !isBlocked
}

class SkillSecurityBlockedException(
    val report: SecurityAuditReport,
    message: String = "技能包未能通过端侧静态安全审查，已被系统阻断安装",
) : SecurityException(message)

/**
 * 技能安全审查与安装事务管理器（连接 ClawHub 市场、静态审计引擎与太墟持久化仓储）。
 */
class SkillInstallationManager(
    private val packageParser: SkillPackageParser,
    private val inspector: SkillPackageInspector,
    private val compatibilityEvaluator: SkillCompatibilityEvaluator,
    private val clawHubClient: ClawHubClient,
    private val agentSkillRepository: AgentSkillRepository,
) {

    private val lifecycle = kotlinx.coroutines.sync.Mutex()

    /**
     * 准备并审查来自 ClawHub 市场的技能包。
     */
    suspend fun prepareMarketSkill(skillId: String): AppResult<SkillInstallInspection> {
        val downloadRes = clawHubClient.downloadPackage(skillId)
        if (downloadRes !is AppResult.Success) {
            return AppResult.Failure(AppError(ErrorCode.DOWNLOAD, "下载市场技能包失败"))
        }

        return runCatching {
            val inspection = inspectZipBytes(downloadRes.data, fallbackId = skillId)
            AppResult.Success(inspection)
        }.getOrElse { err ->
            AppResult.Failure(AppError(ErrorCode.SECURITY, err.message ?: "审查失败", err))
        }
    }

    /**
     * 对 ZIP 字节流执行解构与端侧静态安全审计。
     */
    fun inspectZipBytes(zipBytes: ByteArray, fallbackId: String = "skill_${UUID.randomUUID()}"): SkillInstallInspection {
        val pkg = packageParser.parseFromZip(zipBytes, fallbackId)
        return inspectPackage(pkg)
    }

    /**
     * 对本地技能目录执行解构与端侧静态安全审计。
     */
    fun inspectDirectory(dir: File): SkillInstallInspection {
        val pkg = packageParser.parseFromDirectory(dir)
        return inspectPackage(pkg)
    }

    /**
     * 对解构后的技能包执行完整的安全与兼容性评估。
     */
    fun inspectPackage(pkg: SkillPackage): SkillInstallInspection {
        val auditReport = inspector.inspect(pkg)
        val compatibility = compatibilityEvaluator.evaluate(pkg)

        return SkillInstallInspection(
            packageId = pkg.manifest.id,
            pkg = pkg,
            auditReport = auditReport,
            compatibilityResult = compatibility,
        )
    }

    /**
     * 提交安装：将经安全审查通过的技能包安全解压落盘，并注册到 AgentSkillRepository。
     *
     * @param inspection 审查上下文
     * @param targetSkillsDir 宿主技能安装根目录（通常为 attachments/skills）
     * @param guestPrefix 沙箱内挂载路径前缀（通常为 /attachments/skills）
     */
    suspend fun commitInstallation(
        inspection: SkillInstallInspection,
        targetSkillsDir: File,
        guestPrefix: String = "/attachments/skills",
    ): AgentSkill = lifecycle.withLock {
        commitLocked(inspection, targetSkillsDir, guestPrefix)
    }

    /** Native admin supplies approved target identity; UI commit uses the exact same owner lock. */
    suspend fun commitApproved(inspection: SkillInstallInspection, targetSkillsDir: File,
        guestPrefix: String, expectedTargetDigest: String?, authorize: suspend () -> Boolean = { true },
        expectedRegistration: AgentSkill? = null): AgentSkill = lifecycle.withLock {
        val target = File(targetSkillsDir, inspection.pkg.manifest.id)
        check(directoryDigest(target) == expectedTargetDigest) { "技能目标已改变，请重新提案" }
        check(authorize()) { "原生管理授权已失效" }
        commitLocked(inspection, targetSkillsDir, guestPrefix) { next ->
            check(agentSkillRepository.replaceApproved(expectedRegistration,next,authorize)) { "技能注册已变化，请重新批准" }
        }
    }

    /** Exact reviewed tree is detached before row CAS, so a conflict can restore the old package.
     * This path never follows a model-supplied directory: expected.resourcePath is a native row. */
    suspend fun removeApproved(expected: AgentSkill, expectedDigest: String?, targetSkillsDir: File,
        authorize: suspend () -> Boolean): Boolean = lifecycle.withLock {
        require(!expected.isBuiltin && !expected.isImmutable)
        val target = expected.resourcePath?.let(::File)
        if (target == null) return@withLock agentSkillRepository.deleteApproved(expected, authorize)
        val base = targetSkillsDir.canonicalFile
        require(target.canonicalFile.parentFile == base) { "SKILL_DELETE_TARGET_OUTSIDE_INSTALL_ROOT" }
        check(directoryDigest(target) == expectedDigest) { "SKILL_DIRECTORY_CHANGED" }
        check(authorize()) { "SKILL_AUTHORIZATION_REVOKED" }
        val quarantineRoot = File(base.parentFile,".management-skill-quarantine").also {
            check(it.isDirectory || it.mkdirs())
        }
        val quarantine = File(quarantineRoot, "${target.name}_${UUID.randomUUID()}")
        val moved = target.exists()
        if (moved) check(target.renameTo(quarantine)) { "SKILL_QUARANTINE_FAILED" }
        try {
            val deleted = agentSkillRepository.deleteApproved(expected, authorize)
            if (!deleted) {
                if (moved) check(quarantine.renameTo(target)) { "SKILL_ROLLBACK_PENDING" }
                return@withLock false
            }
        } catch (failure: Throwable) {
            // A registry commit may be uncertain. Preserve the detached tree rather than
            // putting it back into discovery and accidentally resurrecting a deleted skill.
            throw IOException("技能删除结果需核对；保留的隔离目录不得自动恢复", failure)
        }
        if (moved) check(quarantine.deleteRecursively()) { "SKILL_DATA_CLEANUP_PENDING" }
        true
    }

    fun directoryDigest(dir: File): String? {
        val root = dir.toPath()
        if (!java.nio.file.Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return null
        check(!java.nio.file.Files.isSymbolicLink(root) &&
            java.nio.file.Files.isDirectory(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) { "技能目录类型无效" }
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        // Do not follow links while enumerating: checking isFile after canonicalization could
        // otherwise traverse a linked directory before discovering the escape.
        java.nio.file.Files.walk(root).use { stream ->
            val paths = stream.iterator()
            val files = mutableListOf<java.nio.file.Path>()
            while (paths.hasNext()) {
                val path = paths.next()
                check(!java.nio.file.Files.isSymbolicLink(path)) { "技能目录含符号链接" }
                if (java.nio.file.Files.isDirectory(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) continue
                check(java.nio.file.Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) { "技能目录含特殊文件" }
                files += path
            }
            files.sortedBy { root.relativize(it).toString() }.forEach { path ->
                val relative = root.relativize(path).joinToString("/").toByteArray(Charsets.UTF_8)
                digest.update(java.nio.ByteBuffer.allocate(4).putInt(relative.size).array())
                digest.update(relative)
                digest.update(java.nio.ByteBuffer.allocate(8).putLong(java.nio.file.Files.size(path)).array())
                java.nio.file.Files.newInputStream(path, java.nio.file.LinkOption.NOFOLLOW_LINKS).use { input ->
                    val buf = ByteArray(8192)
                    while (true) { val n = input.read(buf); if (n < 0) break; digest.update(buf, 0, n) }
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private suspend fun commitLocked(inspection: SkillInstallInspection, targetSkillsDir: File,
        guestPrefix: String, register: suspend (AgentSkill) -> Unit = { agentSkillRepository.addCustom(it) }): AgentSkill {
        if (inspection.isBlocked) {
            throw SkillSecurityBlockedException(inspection.auditReport)
        }

        // 提交前对内存中的包体重新审计，防止调用方传入被篡改的审查结论
        val pkg = inspection.pkg
        val reReport = inspector.inspect(pkg)
        if (reReport.isBlocked) {
            throw SkillSecurityBlockedException(reReport)
        }

        val skillId = pkg.manifest.id
        if (!SKILL_ID_PATTERN.matches(skillId)) {
            throw SecurityException("技能 id 含非法字符，已拒绝安装: $skillId")
        }

        // 先写入独立暂存目录，成功后整体重命名：回滚时只需删除暂存目录，绝不触碰其他技能
        val stagingDir = File(targetSkillsDir, "$skillId.staging_${UUID.randomUUID().toString().take(8)}").apply { mkdirs() }
        var renamed = false
        val targetDir = File(targetSkillsDir, skillId)
        val backupDir = File(targetSkillsDir, "$skillId.backup_${UUID.randomUUID()}")
        var backedUp = false
        var registered = false
        val canonicalTarget = stagingDir.canonicalPath

        try {
            pkg.rawFiles.forEach { (relPath, bytes) ->
                val safePath = relPath.trimStart('/')
                val destFile = File(stagingDir, safePath)
                val canonicalDest = destFile.canonicalPath
                if (!canonicalDest.startsWith(canonicalTarget + File.separator)) {
                    throw SecurityException("检测到非法的文件写入逃逸: $relPath")
                }
                destFile.parentFile?.mkdirs()
                destFile.writeBytes(bytes)
            }

            if (targetDir.exists()) {
                check(targetDir.renameTo(backupDir)) { "无法保留现有技能以供回滚" }
                backedUp = true
            }
            if (!stagingDir.renameTo(targetDir)) {
                throw IOException("技能目录暂存重命名失败: ${stagingDir.absolutePath} -> ${targetDir.absolutePath}")
            }
            renamed = true

            val guestPath = guestPrefix.trimEnd('/') + "/$skillId"
            val composedPrompt = pkg.templates.composeSystemPrompt(resourceGuestPath = guestPath)

            val agentSkill = AgentSkill(
                id = "custom_$skillId",
                name = pkg.manifest.name,
                description = pkg.manifest.description,
                systemPrompt = composedPrompt,
                triggerCommand = pkg.manifest.triggerCommand,
                iconName = pkg.manifest.icon,
                isEnabled = true,
                isBuiltin = false,
                isImmutable = false,
                category = pkg.manifest.category,
                resourcePath = targetDir.absolutePath,
            )

            register(agentSkill)
            registered = true
            if (backedUp) backupDir.deleteRecursively() // orphan backup is safer than deleting a valid installation
            return agentSkill
        } catch (e: Throwable) {
            if (!registered) {
                if (renamed) targetDir.deleteRecursively() else stagingDir.deleteRecursively()
                if (backedUp && !backupDir.renameTo(targetDir))
                    throw IOException("技能回滚未完成；备份已保留，需原生恢复", e)
            }
            throw e
        }
    }

    companion object {
        // 与 SkillPackageParser.sanitizeSkillId 的输出字符集保持一致（清洗结果可能以 _ 开头）
        // 显式全锚定：即便未来调用方误用 containsMatchIn/find 语义，也不会放行 "../../etc" 类路径穿越串
        private val SKILL_ID_PATTERN = Regex("^[a-z0-9_-]+$")
    }
}
