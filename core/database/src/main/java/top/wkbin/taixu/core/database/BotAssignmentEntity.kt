package top.wkbin.taixu.core.database

import androidx.room.*

@Entity(
    tableName = "bot_assignments",
    foreignKeys = [
        ForeignKey(
            entity = GroupChatEntity::class,
            parentColumns = ["id"],
            childColumns = ["groupId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["groupId", "createdAt"]),
        Index(value = ["status"]),
        Index(value = ["sessionId"]),
        Index(value = ["batchId", "status"]),
        Index(value = ["groupId", "rootWorkId", "stageId", "workItemId"], unique = true),
    ],
)
data class BotAssignmentEntity(
    @PrimaryKey val id: String,
    val groupId: String?,
    val botId: String,
    val botName: String,
    val title: String,
    val task: String,
    val status: String,
    val writePathsJson: String,
    val createdAt: Long,
    val startedAt: Long,
    val finishedAt: Long?,
    val summary: String?,
    val error: String?,
    val sessionId: String?,
    val laneName: String?,
    @ColumnInfo(defaultValue = "NULL") val reportToBotId: String? = null,
    @ColumnInfo(defaultValue = "0") val independent: Boolean = false,
    @ColumnInfo(defaultValue = "0") val revision: Int = 0,
    @ColumnInfo(defaultValue = "NULL") val activeRequestId: String? = null,
    @ColumnInfo(defaultValue = "'[]'") val dependsOnJson: String = "[]",
    @ColumnInfo(defaultValue = "NULL") val batchId: String? = null,
    @ColumnInfo(defaultValue = "NULL") val parentAssignmentId: String? = null,
    @ColumnInfo(defaultValue = "NULL") val budgetId: String? = null,
    @ColumnInfo(defaultValue = "NULL") val proposalId: String? = null,
    @ColumnInfo(defaultValue = "NULL") val proposalVersion: Int? = null,
    @ColumnInfo(defaultValue = "NULL") val teamBinding: String? = null,
    @ColumnInfo(defaultValue = "NULL") val rootWorkId: String? = null,
    @ColumnInfo(defaultValue = "NULL") val stageId: String? = null,
    @ColumnInfo(defaultValue = "NULL") val workItemId: String? = null,
)

