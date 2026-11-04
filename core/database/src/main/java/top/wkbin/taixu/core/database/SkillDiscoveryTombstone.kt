package top.wkbin.taixu.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Preserve native removal intent when files are explicitly retained on disk. */
@Entity(tableName="skill_discovery_tombstones")
data class SkillDiscoveryTombstone(@PrimaryKey val resourcePath: String, val removedAt: Long)
object SkillDiscoveryTombstoneSchema {
    const val CREATE="CREATE TABLE IF NOT EXISTS `skill_discovery_tombstones` (`resourcePath` TEXT NOT NULL, `removedAt` INTEGER NOT NULL, PRIMARY KEY(`resourcePath`))"
}
