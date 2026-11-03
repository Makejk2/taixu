package top.wkbin.taixu.core.database

import androidx.room.*

/** No legacy owner is guessed from a bot ID, role, prompt, or caller-provided JSON. */
@Entity(tableName = "team_group_owners", foreignKeys = [ForeignKey(entity = GroupChatEntity::class,
    parentColumns = ["id"], childColumns = ["groupId"], onDelete = ForeignKey.CASCADE)])
data class TeamGroupOwnerEntity(@PrimaryKey val groupId: String, val ownerSessionId: String)

@Entity(tableName = "team_root_owners", foreignKeys = [ForeignKey(entity = TeamGroupOwnerEntity::class,
    parentColumns = ["groupId"], childColumns = ["groupId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("groupId")])
data class TeamRootOwnerEntity(@PrimaryKey val rootWorkId: String, val groupId: String, val ownerSessionId: String)

@Entity(tableName = "team_suggestions", foreignKeys = [ForeignKey(entity = TeamGroupOwnerEntity::class,
    parentColumns = ["groupId"], childColumns = ["groupId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["groupId", "createdAt"]),
        Index(value = ["sourceSessionId", "idempotencyKey"], unique = true)])
data class TeamSuggestionEntity(
    @PrimaryKey val id: String,
    val groupId: String,
    val sourceAssignmentId: String,
    val sourceSessionId: String,
    val idempotencyKey: String,
    val reason: String,
    val operationJson: String,
    val beforeSnapshot: String,
    val createdAt: Long,
    val sourceRevision: Int,
    val sourceTeamBinding: String,
    val sourceMembershipStamp: String,
    val status: String,
    val proposalId: String?,
    val decidedBy: String?,
    val decidedAt: Long?,
)
