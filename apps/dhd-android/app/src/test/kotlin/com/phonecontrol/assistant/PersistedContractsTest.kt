package com.phonecontrol.assistant

import androidx.sqlite.db.SupportSQLiteDatabase
import com.phonecontrol.assistant.apps.AppPermissionRepository
import com.phonecontrol.assistant.bridge.auth.BridgeCredentials
import com.phonecontrol.assistant.data.AssistantDatabase
import com.phonecontrol.assistant.data.CONVERSATION_DATABASE_NAME
import com.phonecontrol.assistant.data.ConversationStore
import com.phonecontrol.assistant.data.PermissionSetupRepository
import com.phonecontrol.assistant.data.RunStatus
import com.phonecontrol.assistant.data.UiPreferencesRepository
import com.phonecontrol.assistant.data.activityStatus
import com.phonecontrol.assistant.adb.PhoneAccessController
import com.phonecontrol.assistant.adb.DhdAdbKey
import com.phonecontrol.assistant.adb.DhdAdbPairingNotification
import com.phonecontrol.assistant.adb.DhdAdbPairingService
import com.phonecontrol.assistant.maintenance.DhdMaintenanceBootstrap
import com.phonecontrol.assistant.developer.DhdMaintenanceDaemon
import com.phonecontrol.assistant.adb.PreferenceDhdAdbKeyStore
import com.phonecontrol.assistant.domain.ActionType
import com.phonecontrol.assistant.domain.ActivityEventKind
import com.phonecontrol.assistant.execution.TaskDisplayLayoutPreferences
import com.phonecontrol.assistant.execution.TaskDisplayStatus
import com.phonecontrol.assistant.session.AssistantForegroundService
import com.phonecontrol.assistant.ui.AppRoutes
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistedContractsTest {
    private val moduleDir = File(
        System.getProperty("dhd.moduleDir") ?: error("dhd.moduleDir is not set; run the tests through Gradle."),
    )

    @Test
    fun `ui preference names and keys`() {
        assertEquals("dhd_ui_preferences", UiPreferencesRepository.PREFS_NAME)
        assertEquals("pref_theme_mode", UiPreferencesRepository.KEY_THEME_MODE)
        assertEquals("pref_reasoning_effort", UiPreferencesRepository.KEY_REASONING_EFFORT)
        assertEquals("pref_visible_reasoning_efforts", UiPreferencesRepository.KEY_VISIBLE_REASONING_EFFORTS)
        assertEquals("pref_fast_mode", UiPreferencesRepository.KEY_FAST_MODE)
        assertEquals("pref_overlay_enabled", UiPreferencesRepository.KEY_OVERLAY_ENABLED)
        assertEquals("pref_overlay_bubble_x", UiPreferencesRepository.KEY_BUBBLE_X)
        assertEquals("pref_overlay_bubble_y", UiPreferencesRepository.KEY_BUBBLE_Y)
    }

    @Test
    fun `permission setup preference and saved state keys`() {
        assertEquals("dhd_permission_setup", PermissionSetupRepository.PREFERENCES_NAME)
        assertEquals(
            "first_run_permission_onboarding_completed",
            PermissionSetupRepository.KEY_FIRST_RUN_PERMISSION_ONBOARDING_COMPLETED,
        )
        assertEquals("notification_setup_step_handled", PermissionSetupRepository.KEY_NOTIFICATION_SETUP_STEP_HANDLED)
        assertEquals("permission_setup_step", STATE_PERMISSION_SETUP_STEP)
        assertEquals("notification_setup_step_handled", STATE_NOTIFICATION_SETUP_HANDLED)
        assertEquals("pending_overlay_enable", STATE_PENDING_OVERLAY_ENABLE)
    }

    @Test
    fun `companion link preference keys`() {
        assertEquals("dhd_companion_link", BridgeCredentials.PREFERENCES_NAME)
        assertEquals("bridge_auth_token", BridgeCredentials.KEY_AUTH_TOKEN)
        assertEquals("device_id", BridgeCredentials.KEY_DEVICE_ID)
    }

    @Test
    fun `adb connection and identity preference keys`() {
        assertEquals("dhd_adb_connection", PhoneAccessController.PREFERENCES_NAME)
        assertEquals("paired", PhoneAccessController.KEY_PAIRED)
        assertEquals("maintenance_port", DhdMaintenanceBootstrap.KEY_MAINTENANCE_PORT)
        assertEquals("maintenance_token", DhdMaintenanceBootstrap.KEY_MAINTENANCE_TOKEN)
        assertEquals("dhd_adb_identity", DhdAdbKey.KEY_STORE_NAME)
        assertEquals("encrypted_private_key", PreferenceDhdAdbKeyStore.KEY_PRIVATE_KEY)
        assertEquals("dhd_adb_encryption_key", DhdAdbKey.ENCRYPTION_KEY_ALIAS)
    }

    @Test
    fun `app access and task display preference keys`() {
        assertEquals("phone_control_permissions", AppPermissionRepository.PREFERENCES_NAME)
        assertEquals("enabled_packages", AppPermissionRepository.KEY_ENABLED_PACKAGES)
        assertEquals("full_access_enabled", AppPermissionRepository.KEY_FULL_ACCESS)
        assertEquals("dhd_task_display_preferences", TaskDisplayLayoutPreferences.PREFERENCES_NAME)
        assertEquals("full_size_layout_packages", TaskDisplayLayoutPreferences.KEY_FULL_SIZE_LAYOUT_PACKAGES)
    }

    @Test
    fun `assistant service intent actions and extras`() {
        assertEquals("com.phonecontrol.assistant.action.START", AssistantForegroundService.ACTION_START)
        assertEquals("com.phonecontrol.assistant.action.ENABLE_OVERLAY", AssistantForegroundService.ACTION_ENABLE_OVERLAY)
        assertEquals("com.phonecontrol.assistant.action.DISABLE_OVERLAY", AssistantForegroundService.ACTION_DISABLE_OVERLAY)
        assertEquals("com.phonecontrol.assistant.action.REFRESH", AssistantForegroundService.ACTION_REFRESH)
        assertEquals("com.phonecontrol.assistant.action.SESSION_ENDED", AssistantForegroundService.ACTION_SESSION_ENDED)
        assertEquals("com.phonecontrol.assistant.action.CONTINUE", AssistantForegroundService.ACTION_CONTINUE)
        assertEquals("com.phonecontrol.assistant.action.STOP", AssistantForegroundService.ACTION_STOP)
        assertEquals("com.phonecontrol.assistant.action.STOP_USER", AssistantForegroundService.ACTION_STOP_USER)
        assertEquals("com.phonecontrol.assistant.action.START_FRESH", AssistantForegroundService.ACTION_START_FRESH)
        assertEquals("com.phonecontrol.assistant.extra.REQUEST", AssistantForegroundService.EXTRA_REQUEST)
        assertEquals("com.phonecontrol.assistant.extra.CONVERSATION_ID", AssistantForegroundService.EXTRA_CONVERSATION_ID)
        assertEquals("com.phonecontrol.assistant.extra.REASONING_EFFORT", AssistantForegroundService.EXTRA_REASONING_EFFORT)
        assertEquals("com.phonecontrol.assistant.extra.FAST_MODE", AssistantForegroundService.EXTRA_FAST_MODE)
    }

    @Test
    fun `main activity extras and deep link routes`() {
        assertEquals("com.phonecontrol.assistant.extra.CONVERSATION_ID", MainActivity.EXTRA_CONVERSATION_ID)
        assertEquals("com.phonecontrol.assistant.extra.OPEN_ROUTE", MainActivity.EXTRA_OPEN_ROUTE)
        assertEquals("pairing", AppRoutes.PAIRING)
        assertEquals("companion", AppRoutes.COMPANION)
    }

    @Test
    fun `adb pairing service actions and notification`() {
        assertEquals("com.phonecontrol.assistant.action.START_ADB_PAIRING", DhdAdbPairingService.ACTION_START)
        assertEquals("com.phonecontrol.assistant.action.SUBMIT_ADB_PAIRING_CODE", DhdAdbPairingService.ACTION_SUBMIT_CODE)
        assertEquals("com.phonecontrol.assistant.action.STOP_ADB_PAIRING", DhdAdbPairingService.ACTION_STOP)
        assertEquals("dhd_adb_pairing_code", DhdAdbPairingNotification.REMOTE_INPUT_RESULT_KEY)
    }

    @Test
    fun `notification channels and ids`() {
        assertEquals("assistant_sessions", AssistantForegroundService.CHANNEL_ID)
        assertEquals("assistant_results", AssistantForegroundService.RESULT_CHANNEL_ID)
        assertEquals("assistant_attention", AssistantForegroundService.ATTENTION_CHANNEL_ID)
        assertEquals("dhd_adb_pairing", DhdAdbPairingNotification.CHANNEL_ID)
        assertEquals(
            listOf(4201, 4202, 4204, 4205, 4206),
            listOf(
                AssistantForegroundService.NOTIFICATION_ID,
                AssistantForegroundService.REQUEST_OPEN_APP,
                AssistantForegroundService.REQUEST_STOP,
                AssistantForegroundService.COMPLETION_NOTIFICATION_ID,
                AssistantForegroundService.ATTENTION_NOTIFICATION_ID,
            ),
        )
        assertEquals(
            listOf(4207, 4207, 4208, 4209, 4210, 4211, 4212),
            listOf(
                DhdAdbPairingNotification.SEARCHING_NOTIFICATION_ID,
                DhdAdbPairingNotification.NOTIFICATION_ID,
                DhdAdbPairingNotification.RESULT_NOTIFICATION_ID,
                DhdAdbPairingNotification.REQUEST_SUBMIT_CODE,
                DhdAdbPairingNotification.REQUEST_OPEN_APP,
                DhdAdbPairingNotification.LEGACY_FOUND_NOTIFICATION_ID,
                DhdAdbPairingNotification.REQUEST_STOP_SEARCHING,
            ),
        )
    }

    @Test
    fun `room database name version and stored values`() {
        assertEquals("dhd-conversations.db", CONVERSATION_DATABASE_NAME)
        assertEquals("user", ConversationStore.ROLE_USER)
        assertEquals("steer", ConversationStore.ROLE_STEER)
        assertEquals("assistant", ConversationStore.ROLE_ASSISTANT)
        assertEquals(setOf("proposed", "running"), ConversationStore.ACTIVE_ACTIVITY_STATUSES)
        assertEquals(
            mapOf(
                "SESSION_STARTED" to "info",
                "SESSION_PAUSED" to "info",
                "SESSION_RESUMED" to "info",
                "SESSION_STOPPED" to "info",
                "SESSION_COMPLETED" to "info",
                "ACTION_PROPOSED" to "proposed",
                "ACTION_STARTED" to "running",
                "ACTION_SUCCEEDED" to "completed",
                "ACTION_FAILED" to "failed",
                "AGENT_MESSAGE" to "info",
                "ATTENTION_REQUIRED" to "attention",
                "SYSTEM" to "info",
            ),
            ActivityEventKind.entries.associate { it.name to it.activityStatus() },
        )
        assertEquals(
            listOf("QUEUED", "RUNNING", "PAUSED", "ATTENTION", "COMPLETED", "FAILED", "STOPPED"),
            RunStatus.entries.map { it.name },
        )
        assertEquals(
            listOf("RUNNING", "PAUSED", "COMPLETED", "FAILED", "STOPPED", "UNAVAILABLE", "ENDED", "EXPIRED"),
            TaskDisplayStatus.entries.map { it.name },
        )
        assertEquals(
            listOf("OPEN_APP", "TAP", "TYPE", "SWIPE", "BACK", "KEYPRESS", "WAIT"),
            ActionType.entries.map { it.name },
        )
    }

    @Test
    fun `exported room schema is pinned`() {
        val schemaFile = File(moduleDir, "schemas/com.phonecontrol.assistant.data.AssistantDatabase/3.json")
        assertTrue("Missing exported Room schema ${schemaFile.absolutePath}", schemaFile.isFile)
        val database = JSONObject(schemaFile.readText()).getJSONObject("database")
        assertEquals(3, database.getInt("version"))
        assertEquals("eb25db9d364023ec3ac5b060e2cebe3e", database.getString("identityHash"))
        val entities = database.getJSONArray("entities")
        val columns = (0 until entities.length()).associate { index ->
            val entity = entities.getJSONObject(index)
            val fields = entity.getJSONArray("fields")
            entity.getString("tableName") to (0 until fields.length()).map { fields.getJSONObject(it).getString("columnName") }
        }
        assertEquals(
            mapOf(
                "conversations" to listOf("id", "codexThreadId", "title", "createdAtEpochMs", "updatedAtEpochMs", "deleted"),
                "messages" to listOf("id", "conversationId", "runId", "role", "text", "createdAtEpochMs"),
                "agent_runs" to listOf(
                    "id", "conversationId", "userMessageId", "status", "currentPurpose",
                    "startedAtEpochMs", "endedAtEpochMs", "codexTurnId", "error",
                ),
                "tool_activities" to listOf(
                    "id", "conversationId", "runId", "sequence", "purpose", "targetDescription",
                    "toolName", "actionType", "status", "message", "createdAtEpochMs", "updatedAtEpochMs",
                ),
                "task_displays" to listOf(
                    "sessionKey", "taskId", "packageName", "displayId", "width", "height", "densityDpi",
                    "rotation", "status", "createdAtEpochMs", "terminalAtEpochMs", "expiresAtEpochMs",
                    "lastPurpose", "error", "ownerPackageName",
                ),
            ),
            columns,
        )
    }

    @Test
    fun `room migrations are pinned`() {
        assertEquals(1 to 2, AssistantDatabase.MIGRATION_1_2.startVersion to AssistantDatabase.MIGRATION_1_2.endVersion)
        assertEquals(2 to 3, AssistantDatabase.MIGRATION_2_3.startVersion to AssistantDatabase.MIGRATION_2_3.endVersion)
        assertEquals(
            listOf(
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
                "CREATE INDEX IF NOT EXISTS `index_task_displays_taskId` ON `task_displays` (`taskId`)",
                "CREATE INDEX IF NOT EXISTS `index_task_displays_status` ON `task_displays` (`status`)",
                "CREATE INDEX IF NOT EXISTS `index_task_displays_expiresAtEpochMs` ON `task_displays` (`expiresAtEpochMs`)",
            ),
            executedSql { AssistantDatabase.MIGRATION_1_2.migrate(it) },
        )
        assertEquals(
            listOf("ALTER TABLE `task_displays` ADD COLUMN `ownerPackageName` TEXT"),
            executedSql { AssistantDatabase.MIGRATION_2_3.migrate(it) },
        )
    }

    @Test
    fun `maintenance daemon class name is baked into the start command`() {
        assertEquals("com.phonecontrol.assistant.developer.DhdMaintenanceDaemon", DhdMaintenanceDaemon::class.java.name)
        val main = DhdMaintenanceDaemon::class.java.getDeclaredMethod("main", Array<String>::class.java)
        assertTrue(Modifier.isStatic(main.modifiers) && Modifier.isPublic(main.modifiers))
    }

    @Test
    fun `jni pairing context matches the native registration table`() {
        val nativeSource = File(moduleDir, "src/main/cpp/adb_pairing.cpp").readText()
        val registeredClass = Regex("\"(com/phonecontrol/assistant/developer/DhdAdbPairingContext)\"")
            .find(nativeSource)!!.groupValues[1]
        assertEquals("com/phonecontrol/assistant/developer/DhdAdbPairingContext", registeredClass)
        val registered = Regex("\\{\"(native\\w+)\", \"([^\"]+)\"")
            .findAll(nativeSource)
            .associate { it.groupValues[1] to it.groupValues[2] }
        val pairingContext = Class.forName(
            registeredClass.replace('/', '.'),
            false,
            PersistedContractsTest::class.java.classLoader,
        )
        val declared = pairingContext.declaredMethods
            .filter { Modifier.isNative(it.modifiers) }
            .associate { it.name to it.jniDescriptor() }
        assertEquals(
            mapOf(
                "nativeConstructor" to "(Z[B)J",
                "nativeMessage" to "(J)[B",
                "nativeInitCipher" to "(J[B)Z",
                "nativeEncrypt" to "(J[B)[B",
                "nativeDecrypt" to "(J[B)[B",
                "nativeDestroy" to "(J)V",
            ),
            registered,
        )
        assertEquals(registered, declared)
        assertTrue(File(moduleDir, "src/main/cpp/CMakeLists.txt").readText().contains("add_library(dhd_adb SHARED"))
    }

    private fun executedSql(block: (SupportSQLiteDatabase) -> Unit): List<String> {
        val statements = mutableListOf<String>()
        val database = Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java),
        ) { _, method, args ->
            require(method.name == "execSQL") { "Unexpected migration call ${method.name}" }
            statements += args!![0] as String
            null
        } as SupportSQLiteDatabase
        block(database)
        return statements
    }

    private fun Method.jniDescriptor(): String =
        parameterTypes.joinToString(separator = "", prefix = "(", postfix = ")") { it.jniName() } + returnType.jniName()

    private fun Class<*>.jniName(): String = when (this) {
        java.lang.Long.TYPE -> "J"
        java.lang.Boolean.TYPE -> "Z"
        java.lang.Void.TYPE -> "V"
        java.lang.Integer.TYPE -> "I"
        ByteArray::class.java -> "[B"
        else -> "L${name.replace('.', '/')};"
    }
}
