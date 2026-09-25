package com.phonecontrol.assistant.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.phonecontrol.assistant.domain.ActivityEvent
import com.phonecontrol.assistant.domain.ActivityEventKind
import com.phonecontrol.assistant.core.CoordinatorCopy
import com.phonecontrol.assistant.core.ToolNames
import com.phonecontrol.assistant.domain.ActionType
import com.phonecontrol.assistant.execution.TaskDisplayRecord
import com.phonecontrol.assistant.execution.TaskDisplayStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

const val DHD_CONVERSATION_ID = "dhd-assistant"
internal const val CONVERSATION_DATABASE_NAME = "dhd-conversations.db"
const val DHD_THREAD_INACTIVITY_MS = 3 * 60 * 60 * 1000L

internal fun hasDhdConversationExpired(lastActivityEpochMs: Long, nowEpochMs: Long): Boolean =
    nowEpochMs - lastActivityEpochMs >= DHD_THREAD_INACTIVITY_MS


/** Local, app-private conversation metadata. Codex remains the remote context source of truth. */
@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val codexThreadId: String? = null,
    val title: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val deleted: Boolean = false,
)

@Entity(
    tableName = "messages",
    indices = [Index(value = ["conversationId"]), Index(value = ["runId"])],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val runId: String?,
    val role: String,
    val text: String,
    val createdAtEpochMs: Long,
)

@Entity(
    tableName = "agent_runs",
    indices = [Index(value = ["conversationId"])],
)
data class AgentRunEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    // Continuation runs intentionally have no new user message and use an
    // empty id so Play can resume the remote thread without adding a bubble.
    val userMessageId: String,
    val status: String,
    val currentPurpose: String,
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long? = null,
    val codexTurnId: String? = null,
    val error: String? = null,
)

@Entity(
    tableName = "tool_activities",
    indices = [Index(value = ["conversationId"]), Index(value = ["runId"])],
)
data class ToolActivityEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val runId: String,
    val sequence: Long,
    val purpose: String,
    val targetDescription: String?,
    val toolName: String?,
    val actionType: String?,
    val status: String,
    val message: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

/** Durable metadata for a phone-local virtual display. Frames never enter Room. */
@Entity(
    tableName = "task_displays",
    indices = [
        Index(value = ["taskId"]),
        Index(value = ["status"]),
        Index(value = ["expiresAtEpochMs"]),
    ],
)
data class TaskDisplayEntity(
    @PrimaryKey val sessionKey: String,
    val taskId: String,
    val packageName: String,
    val displayId: Int,
    val width: Int,
    val height: Int,
    val densityDpi: Int,
    val rotation: Int,
    val status: String,
    val createdAtEpochMs: Long,
    val terminalAtEpochMs: Long? = null,
    val expiresAtEpochMs: Long? = null,
    val lastPurpose: String,
    val error: String? = null,
    val ownerPackageName: String? = null,
)

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations WHERE deleted = 0 ORDER BY updatedAtEpochMs DESC")
    fun listConversations(): List<ConversationEntity>

    @Query("SELECT * FROM conversations WHERE id = :id LIMIT 1")
    fun findConversation(id: String): ConversationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertConversation(conversation: ConversationEntity)

    @Update
    fun updateConversation(conversation: ConversationEntity)

    @Query("DELETE FROM conversations WHERE id = :id")
    fun deleteConversation(id: String)

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAtEpochMs ASC, id ASC")
    fun listMessages(conversationId: String): List<MessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertMessage(message: MessageEntity)

    @Update
    fun updateMessage(message: MessageEntity)

    @Query("SELECT * FROM messages WHERE id = :id LIMIT 1")
    fun findMessage(id: String): MessageEntity?

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    fun deleteMessages(conversationId: String)

    @Query("SELECT * FROM agent_runs WHERE id = :id LIMIT 1")
    fun findRun(id: String): AgentRunEntity?

    @Query("SELECT * FROM agent_runs WHERE conversationId = :conversationId ORDER BY startedAtEpochMs DESC")
    fun listRuns(conversationId: String): List<AgentRunEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertRun(run: AgentRunEntity)

    @Update
    fun updateRun(run: AgentRunEntity)

    @Query("DELETE FROM agent_runs WHERE conversationId = :conversationId")
    fun deleteRuns(conversationId: String)

    @Query("SELECT * FROM tool_activities WHERE runId = :runId ORDER BY sequence ASC, createdAtEpochMs ASC")
    fun listActivities(runId: String): List<ToolActivityEntity>

    @Query("SELECT * FROM tool_activities WHERE conversationId = :conversationId ORDER BY sequence ASC, createdAtEpochMs ASC")
    fun listConversationActivities(conversationId: String): List<ToolActivityEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertActivity(activity: ToolActivityEntity)

    @Update
    fun updateActivity(activity: ToolActivityEntity)

    @Query("DELETE FROM tool_activities WHERE conversationId = :conversationId")
    fun deleteActivities(conversationId: String)

    @Query("SELECT * FROM task_displays ORDER BY createdAtEpochMs DESC, sessionKey ASC")
    fun listTaskDisplays(): List<TaskDisplayEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertTaskDisplay(display: TaskDisplayEntity)

    @Query("DELETE FROM task_displays")
    fun deleteAllTaskDisplays()
}

@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        AgentRunEntity::class,
        ToolActivityEntity::class,
        TaskDisplayEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class AssistantDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao

    companion object {
        /** Preserve existing conversations while adding display metadata. */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `task_displays` (
                        `sessionKey` TEXT NOT NULL,
                        `taskId` TEXT NOT NULL,
                        `packageName` TEXT NOT NULL,
                        `displayId` INTEGER NOT NULL,
                        `width` INTEGER NOT NULL,
                        `height` INTEGER NOT NULL,
                        `densityDpi` INTEGER NOT NULL,
                        `rotation` INTEGER NOT NULL,
                        `status` TEXT NOT NULL,
                        `createdAtEpochMs` INTEGER NOT NULL,
                        `terminalAtEpochMs` INTEGER,
                        `expiresAtEpochMs` INTEGER,
                        `lastPurpose` TEXT NOT NULL,
                        `error` TEXT,
                        PRIMARY KEY(`sessionKey`)
                    )
                    """.trimIndent(),
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_task_displays_taskId` " +
                        "ON `task_displays` (`taskId`)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_task_displays_status` " +
                        "ON `task_displays` (`status`)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_task_displays_expiresAtEpochMs` " +
                        "ON `task_displays` (`expiresAtEpochMs`)"
                )
            }
        }

        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE `task_displays` ADD COLUMN `ownerPackageName` TEXT")
            }
        }
    }
}

enum class RunStatus {
    QUEUED,
    RUNNING,
    PAUSED,
    ATTENTION,
    COMPLETED,
    FAILED,
    STOPPED,
}

data class ConversationSummary(
    val id: String,
    val title: String,
    val preview: String,
    val updatedAtEpochMs: Long,
    val status: RunStatus?,
)

sealed interface TimelineItem {
    val id: String
    val timestampEpochMs: Long

    data class Message(
        override val id: String,
        val runId: String?,
        val role: String,
        val text: String,
        override val timestampEpochMs: Long,
    ) : TimelineItem

    data class Activity(
        override val id: String,
        val runId: String,
        val purpose: String,
        val targetDescription: String?,
        val toolName: String?,
        val actionType: String?,
        val status: String,
        val message: String,
        val createdAtEpochMs: Long,
        override val timestampEpochMs: Long,
    ) : TimelineItem
}

data class StartedRun(
    val conversationId: String,
    val runId: String,
    val userMessageId: String,
)

/**
 * A deliberately small local store. The data is safe presentation history:
 * requests, assistant messages, purposes and statuses only. Screenshots,
 * typed payloads, raw arguments and private reasoning never enter this store.
 *
 * v0 uses Room's synchronous DAO calls because the coordinator is already a
 * process-local serialized runtime. The database is app-private and bounded
 * by the same text limits as the phone policy layer.
 */
class ConversationStore(context: Context) {
    private val database = Room.databaseBuilder(
        context.applicationContext,
        AssistantDatabase::class.java,
        CONVERSATION_DATABASE_NAME,
    )
        .addMigrations(AssistantDatabase.MIGRATION_1_2, AssistantDatabase.MIGRATION_2_3)
        .allowMainThreadQueries()
        .build()
    private val dao = database.conversationDao()
    private val lock = Any()
    private val _conversations = MutableStateFlow<List<ConversationSummary>>(emptyList())
    private val timelineFlows = mutableMapOf<String, MutableStateFlow<List<TimelineItem>>>()

    val conversations: StateFlow<List<ConversationSummary>> = _conversations.asStateFlow()
    private val _conversationExpiryPrompt = MutableStateFlow(false)
    val conversationExpiryPrompt: StateFlow<Boolean> = _conversationExpiryPrompt.asStateFlow()

    init {
        refreshConversations()
    }

    fun timeline(conversationId: String = DHD_CONVERSATION_ID): StateFlow<List<TimelineItem>> = synchronized(lock) {
        val canonicalId = canonicalConversationId(conversationId)
        timelineFlows.getOrPut(canonicalId) {
            MutableStateFlow(loadTimeline(canonicalId))
        }.asStateFlow()
    }

    /** Report whether the DHD conversation should ask the user before expiring. */
    fun promptForInactiveConversation(nowEpochMs: Long = System.currentTimeMillis()): Boolean = synchronized(lock) {
        val existing = dao.findConversation(DHD_CONVERSATION_ID)
        val shouldPrompt = existing != null &&
            hasDhdConversationExpired(existing.updatedAtEpochMs, nowEpochMs)
        _conversationExpiryPrompt.value = shouldPrompt
        shouldPrompt
    }

    fun dismissInactiveConversationPrompt() = synchronized(lock) {
        _conversationExpiryPrompt.value = false
    }

    /** Keep a stale conversation and restart its inactivity window. */
    fun keepInactiveConversation(nowEpochMs: Long = System.currentTimeMillis()): Boolean = synchronized(lock) {
        val existing = dao.findConversation(DHD_CONVERSATION_ID) ?: run {
            _conversationExpiryPrompt.value = false
            return@synchronized false
        }
        if (!hasDhdConversationExpired(existing.updatedAtEpochMs, nowEpochMs)) {
            _conversationExpiryPrompt.value = false
            return@synchronized false
        }

        dao.updateConversation(
            existing.copy(
                updatedAtEpochMs = nowEpochMs,
                deleted = false,
            ),
        )
        _conversationExpiryPrompt.value = false
        refreshConversations()
        true
    }

    /** Clear the DHD conversation once it has been inactive for the full window. */
    fun expireInactiveConversation(nowEpochMs: Long = System.currentTimeMillis()): Boolean = synchronized(lock) {
        val existing = dao.findConversation(DHD_CONVERSATION_ID) ?: run {
            _conversationExpiryPrompt.value = false
            return@synchronized false
        }
        if (!hasDhdConversationExpired(existing.updatedAtEpochMs, nowEpochMs)) {
            _conversationExpiryPrompt.value = false
            return@synchronized false
        }

        clearConversationRows(DHD_CONVERSATION_ID)
        _conversationExpiryPrompt.value = false
        refreshConversations()
        true
    }

    fun startRun(runId: String, request: String, requestedConversationId: String? = null): StartedRun = synchronized(lock) {
        val now = System.currentTimeMillis()
        val safeRequest = request.trim().take(MAX_MESSAGE_CHARS)
        require(safeRequest.isNotEmpty()) { "A request is required." }

        // DHD is one assistant, not a collection of user-facing chats. Keep a
        // stable local conversation row until it has been inactive long enough
        // to require a full fresh conversation.
        val existing = dao.findConversation(DHD_CONVERSATION_ID)
        val resetConversation = existing != null &&
            hasDhdConversationExpired(existing.updatedAtEpochMs, now)
        if (resetConversation) {
            // A thread rotation is a full conversation reset from the user's
            // perspective. Keep the old remote Codex thread unbound, but clear
            // the local presentation history before creating the new run.
            clearConversationRows(DHD_CONVERSATION_ID)
            _conversationExpiryPrompt.value = false
        }
        val conversation = if (existing == null || resetConversation) {
            ConversationEntity(
                id = DHD_CONVERSATION_ID,
                title = titleFor(safeRequest),
                createdAtEpochMs = now,
                updatedAtEpochMs = now,
            )
        } else {
            existing.copy(
                updatedAtEpochMs = now,
                deleted = false,
            )
        }
        if (existing == null || resetConversation) dao.insertConversation(conversation)
        else dao.updateConversation(conversation)

        val messageId = UUID.randomUUID().toString()
        dao.insertMessage(
            MessageEntity(
                id = messageId,
                conversationId = conversation.id,
                runId = runId,
                role = ROLE_USER,
                text = safeRequest,
                createdAtEpochMs = now,
            ),
        )
        dao.insertRun(
            AgentRunEntity(
                id = runId,
                conversationId = conversation.id,
                userMessageId = messageId,
                status = RunStatus.RUNNING.name,
                currentPurpose = CoordinatorCopy.PREPARING_REQUEST,
                startedAtEpochMs = now,
            ),
        )
        refresh(conversation.id)
        StartedRun(DHD_CONVERSATION_ID, runId, messageId)
    }

    /** Start a hidden local run for a Codex continuation with no local user message. */
    fun startContinuationRun(runId: String, requestedConversationId: String? = null): StartedRun? = synchronized(lock) {
        val now = System.currentTimeMillis()
        val conversationId = canonicalConversationId(requestedConversationId)
        val existing = dao.findConversation(conversationId) ?: return@synchronized null
        val conversation = existing.copy(
            updatedAtEpochMs = now,
            deleted = false,
        )
        dao.updateConversation(conversation)
        dao.insertRun(
            AgentRunEntity(
                id = runId,
                conversationId = conversation.id,
                userMessageId = "",
                status = RunStatus.RUNNING.name,
                currentPurpose = CoordinatorCopy.PREPARING_REQUEST,
                startedAtEpochMs = now,
            ),
        )
        refresh(conversation.id)
        StartedRun(conversation.id, runId, "")
    }

    fun setCurrentPurpose(runId: String, purpose: String) = synchronized(lock) {
        val run = dao.findRun(runId) ?: return@synchronized
        val safePurpose = purpose.trim().take(MAX_PURPOSE_CHARS).ifBlank { return@synchronized }
        dao.updateRun(run.copy(currentPurpose = safePurpose))
        touchConversation(run.conversationId)
        refresh(run.conversationId)
    }

    fun setRunStatus(runId: String, status: RunStatus, error: String? = null) = synchronized(lock) {
        val run = dao.findRun(runId) ?: return@synchronized
        val endedAt = when (status) {
            RunStatus.COMPLETED, RunStatus.FAILED, RunStatus.STOPPED -> System.currentTimeMillis()
            else -> null
        }
        dao.updateRun(run.copy(status = status.name, endedAtEpochMs = endedAt, error = error?.take(MAX_MESSAGE_CHARS)))
        touchConversation(run.conversationId)
        refresh(run.conversationId)
    }

    fun completeRun(runId: String, status: RunStatus, assistantText: String? = null) = synchronized(lock) {
        val run = dao.findRun(runId) ?: return@synchronized
        val now = System.currentTimeMillis()
        dao.updateRun(run.copy(status = status.name, endedAtEpochMs = now, error = null))
        val safeText = assistantText?.trim()?.take(MAX_AGENT_MESSAGE_CHARS)?.ifBlank { null }
        if (safeText != null) {
            dao.insertMessage(
                MessageEntity(
                    id = UUID.randomUUID().toString(),
                    conversationId = run.conversationId,
                    runId = runId,
                    role = ROLE_ASSISTANT,
                    text = safeText,
                    createdAtEpochMs = now,
                ),
            )
        }
        touchConversation(run.conversationId)
        refresh(run.conversationId)
    }

    /** Insert or update the single assistant message shown while a run streams. */
    fun upsertAgentMessage(runId: String, messageId: String, text: String): Boolean = synchronized(lock) {
        val run = dao.findRun(runId) ?: return@synchronized false
        val safeId = messageId.trim().take(MAX_MESSAGE_ID_CHARS).ifBlank { return@synchronized false }
        val safeText = text.replace(Regex("\\r\\n?"), "\n").take(MAX_AGENT_MESSAGE_CHARS)
        if (safeText.isBlank()) return@synchronized false

        val existing = dao.findMessage(safeId)
        if (existing == null) {
            dao.insertMessage(
                MessageEntity(
                    id = safeId,
                    conversationId = run.conversationId,
                    runId = runId,
                    role = ROLE_ASSISTANT,
                    text = safeText,
                    createdAtEpochMs = System.currentTimeMillis(),
                ),
            )
        } else {
            if (existing.conversationId != run.conversationId ||
                existing.runId != runId ||
                existing.role != ROLE_ASSISTANT
            ) {
                return@synchronized false
            }
            dao.updateMessage(existing.copy(text = safeText))
        }
        touchConversation(run.conversationId)
        refresh(run.conversationId)
        true
    }

    /** Persist a user steering instruction alongside the run it modifies. */
    fun recordSteer(steerId: String, runId: String, text: String) = synchronized(lock) {
        val run = dao.findRun(runId) ?: return@synchronized
        val safeText = text.trim().take(MAX_AGENT_MESSAGE_CHARS).ifBlank { return@synchronized }
        if (dao.findMessage(steerId) == null) {
            dao.insertMessage(
                MessageEntity(
                    id = steerId,
                    conversationId = run.conversationId,
                    runId = runId,
                    role = ROLE_STEER,
                    text = safeText,
                    createdAtEpochMs = System.currentTimeMillis(),
                ),
            )
        }
        touchConversation(run.conversationId)
        refresh(run.conversationId)
    }

    fun recordEvent(event: ActivityEvent) = synchronized(lock) {
        val runId = event.sessionId ?: return@synchronized
        val run = dao.findRun(runId) ?: return@synchronized
        val purpose = event.purpose
            ?.trim()
            ?.take(MAX_PURPOSE_CHARS)
            ?.ifBlank { null }
        if (purpose != null && event.kind in PURPOSE_EVENT_KINDS) {
            val actionName = event.actionType?.name
            val existing = dao.listActivities(runId).lastOrNull {
                it.purpose == purpose &&
                    it.actionType == actionName &&
                    // A new proposal starts a new stack row even when the
                    // model repeats the same action. Later lifecycle events
                    // update only the latest in-flight row, so repeated taps
                    // remain visible as separate tool calls.
                    (event.kind == ActivityEventKind.ACTION_PROPOSED ||
                        it.status in ACTIVE_ACTIVITY_STATUSES)
            }.takeUnless { event.kind == ActivityEventKind.ACTION_PROPOSED }
            val status = event.kind.activityStatus()
            val message = event.message.trim().take(MAX_MESSAGE_CHARS)
            if (existing == null) {
                dao.insertActivity(
                    ToolActivityEntity(
                        id = event.id,
                        conversationId = run.conversationId,
                        runId = runId,
                        sequence = dao.listActivities(runId).size.toLong(),
                        purpose = purpose,
                        targetDescription = event.targetDescription?.trim()?.take(MAX_PURPOSE_CHARS),
                        // Action lifecycle events are emitted by the DHD tool
                        // boundary. Keep the public tool name so the UI can
                        // distinguish real tool calls from system activity.
                        toolName = event.toolName ?: when (event.actionType) {
                            ActionType.OPEN_APP -> ToolNames.OPEN_APP
                            null -> null
                            else -> ToolNames.EXECUTE
                        },
                        actionType = actionName,
                        status = status,
                        message = message,
                        createdAtEpochMs = event.timestampEpochMs,
                        updatedAtEpochMs = event.timestampEpochMs,
                    ),
                )
            } else {
                val updatedMessage = when (event.kind) {
                    // The proposed event carries the provider's safe purpose.
                    // Keep it as the expandable explanation while later status
                    // events update the status badge rather than replacing the
                    // useful context with "Executing tap" or "Tap succeeded".
                    ActivityEventKind.ACTION_STARTED,
                    ActivityEventKind.ACTION_SUCCEEDED,
                    -> existing.message
                    ActivityEventKind.ACTION_FAILED -> listOf(existing.message, message)
                        .filter { it.isNotBlank() }
                        .distinct()
                        .joinToString(" — ")
                        .take(MAX_MESSAGE_CHARS)
                    else -> message
                }
                dao.updateActivity(
                    existing.copy(
                        toolName = event.toolName ?: existing.toolName,
                        status = status,
                        message = updatedMessage,
                        updatedAtEpochMs = event.timestampEpochMs,
                    ),
                )
            }
        }
        if (event.kind == ActivityEventKind.AGENT_MESSAGE) {
            val existingMessage = dao.findMessage(event.id)
            if (existingMessage == null) {
                dao.insertMessage(
                    MessageEntity(
                        id = event.id,
                        conversationId = run.conversationId,
                        runId = runId,
                        role = ROLE_ASSISTANT,
                        text = event.message.trim().take(MAX_AGENT_MESSAGE_CHARS),
                        createdAtEpochMs = event.timestampEpochMs,
                    ),
                )
            } else if (
                existingMessage.conversationId == run.conversationId &&
                existingMessage.runId == runId &&
                existingMessage.role == ROLE_ASSISTANT
            ) {
                dao.updateMessage(
                    existingMessage.copy(
                        text = event.message.trim().take(MAX_AGENT_MESSAGE_CHARS),
                    ),
                )
            }
        }
        touchConversation(run.conversationId)
        refresh(run.conversationId)
    }

    /** Return the durable task-display registry, newest display first. */
    fun listTaskDisplays(): List<TaskDisplayRecord> = synchronized(lock) {
        dao.listTaskDisplays().mapNotNull { it.toTaskDisplayRecord() }
    }

    fun currentPurpose(runId: String): String? = synchronized(lock) {
        dao.findRun(runId)?.currentPurpose
    }

    /** Insert or replace one display's metadata without storing frames. */
    fun upsertTaskDisplay(record: TaskDisplayRecord) = synchronized(lock) {
        dao.insertTaskDisplay(record.toEntity())
    }

    fun deleteAllTaskDisplays() = synchronized(lock) {
        dao.deleteAllTaskDisplays()
    }

    fun bindCodexThread(conversationId: String, codexThreadId: String) = synchronized(lock) {
        val conversation = dao.findConversation(canonicalConversationId(conversationId)) ?: return@synchronized
        dao.updateConversation(conversation.copy(codexThreadId = codexThreadId, updatedAtEpochMs = System.currentTimeMillis()))
        refreshConversations()
    }

    fun codexThreadId(conversationId: String?): String? = synchronized(lock) {
        dao.findConversation(canonicalConversationId(conversationId))?.codexThreadId
    }

    fun deleteConversation(conversationId: String): Boolean = synchronized(lock) {
        val canonicalId = canonicalConversationId(conversationId)
        clearConversationRows(canonicalId)
        if (canonicalId == DHD_CONVERSATION_ID) {
            _conversationExpiryPrompt.value = false
        }
        // Keep the flow instance that Compose is already collecting alive and
        // publish the empty state before dropping it from the cache. A
        // collector must not stay stuck displaying the deleted timeline.
        timelineFlows[canonicalId]?.value = emptyList()
        timelineFlows.remove(canonicalId)
        refreshConversations()
        true
    }

    private fun clearConversationRows(conversationId: String) {
        database.runInTransaction {
            dao.deleteActivities(conversationId)
            dao.deleteMessages(conversationId)
            dao.deleteRuns(conversationId)
            dao.deleteConversation(conversationId)
        }
        timelineFlows[conversationId]?.value = emptyList()
    }

    private fun refresh(conversationId: String) {
        refreshConversations()
        timelineFlows[conversationId]?.value = loadTimeline(conversationId)
    }

    private fun refreshConversations() {
        val records = dao.listConversations().filter { it.id == DHD_CONVERSATION_ID }.map { conversation ->
            val latestMessage = dao.listMessages(conversation.id).lastOrNull()
            val latestRun = dao.listRuns(conversation.id).firstOrNull()
            ConversationSummary(
                id = conversation.id,
                title = conversation.title,
                preview = latestMessage?.text.orEmpty(),
                updatedAtEpochMs = conversation.updatedAtEpochMs,
                status = latestRun?.status?.let { runStatusOrNull(it) },
            )
        }
        _conversations.value = records
    }

    private fun loadTimeline(conversationId: String): List<TimelineItem> {
        val messages = dao.listMessages(conversationId).map {
            TimelineItem.Message(it.id, it.runId, it.role, it.text, it.createdAtEpochMs)
        }
        val activities = dao.listConversationActivities(conversationId)
            .filterNot { ToolNames.isCloseDisplay(it.toolName) }
            .map {
            TimelineItem.Activity(
                id = it.id,
                runId = it.runId,
                purpose = it.purpose,
                targetDescription = it.targetDescription,
                toolName = it.toolName,
                actionType = it.actionType,
                status = it.status,
                message = it.message,
                createdAtEpochMs = it.createdAtEpochMs,
                timestampEpochMs = it.updatedAtEpochMs,
            )
        }
        return (messages + activities).sortedWith(compareBy<TimelineItem> { it.timestampEpochMs }.thenBy { it.id })
    }

    private fun touchConversation(conversationId: String) {
        val conversation = dao.findConversation(conversationId) ?: return
        dao.updateConversation(conversation.copy(updatedAtEpochMs = System.currentTimeMillis()))
    }

    private fun runStatusOrNull(value: String): RunStatus? = runCatching { RunStatus.valueOf(value) }.getOrNull()

    private fun titleFor(request: String): String = request
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(MAX_TITLE_CHARS)

    private fun canonicalConversationId(@Suppress("UNUSED_PARAMETER") requestedId: String?): String =
        DHD_CONVERSATION_ID

    internal companion object {
        const val ROLE_USER = "user"
        const val ROLE_STEER = "steer"
        const val ROLE_ASSISTANT = "assistant"
        const val MAX_TITLE_CHARS = 48
        const val MAX_MESSAGE_ID_CHARS = 240
        const val MAX_PURPOSE_CHARS = 240
        const val MAX_MESSAGE_CHARS = 4_000
        const val MAX_AGENT_MESSAGE_CHARS = 4_000
        val PURPOSE_EVENT_KINDS = setOf(
            ActivityEventKind.ACTION_PROPOSED,
            ActivityEventKind.ACTION_STARTED,
            ActivityEventKind.ACTION_SUCCEEDED,
            ActivityEventKind.ACTION_FAILED,
            ActivityEventKind.ATTENTION_REQUIRED,
            ActivityEventKind.SYSTEM,
        )
        val ACTIVE_ACTIVITY_STATUSES = setOf("proposed", "running")
    }
}

internal fun ActivityEventKind.activityStatus(): String = when (this) {
    ActivityEventKind.ACTION_PROPOSED -> "proposed"
    ActivityEventKind.ACTION_STARTED -> "running"
    ActivityEventKind.ACTION_SUCCEEDED -> "completed"
    ActivityEventKind.ACTION_FAILED -> "failed"
    ActivityEventKind.ATTENTION_REQUIRED -> "attention"
    else -> "info"
}

private fun TaskDisplayRecord.toEntity(): TaskDisplayEntity = TaskDisplayEntity(
    sessionKey = sessionKey,
    taskId = taskId,
    packageName = packageName,
    displayId = displayId,
    width = width,
    height = height,
    densityDpi = densityDpi,
    rotation = rotation,
    status = status.name,
    createdAtEpochMs = createdAtEpochMs,
    terminalAtEpochMs = terminalAtEpochMs,
    expiresAtEpochMs = expiresAtEpochMs,
    lastPurpose = lastPurpose,
    error = error,
    ownerPackageName = ownerPackageName,
)

private fun TaskDisplayEntity.toTaskDisplayRecord(): TaskDisplayRecord? {
    val parsedStatus = runCatching { TaskDisplayStatus.valueOf(status) }.getOrNull() ?: return null
    return runCatching {
        TaskDisplayRecord(
            sessionKey = sessionKey,
            taskId = taskId,
            packageName = packageName,
            displayId = displayId,
            width = width,
            height = height,
            densityDpi = densityDpi,
            rotation = rotation,
            status = parsedStatus,
            createdAtEpochMs = createdAtEpochMs,
            terminalAtEpochMs = terminalAtEpochMs,
            expiresAtEpochMs = expiresAtEpochMs,
            lastPurpose = lastPurpose,
            error = error,
            ownerPackageName = ownerPackageName,
        )
    }.getOrNull()
}
