package com.phonecontrol.assistant.data

internal class InMemoryConversationDao : ConversationDao {
    val conversations = linkedMapOf<String, ConversationEntity>()
    val messages = linkedMapOf<String, MessageEntity>()
    val runs = linkedMapOf<String, AgentRunEntity>()
    val activities = linkedMapOf<String, ToolActivityEntity>()
    val taskDisplays = linkedMapOf<String, TaskDisplayEntity>()
    val calls = mutableListOf<String>()
    val callingThreads = mutableSetOf<String>()

    private fun record(name: String) {
        calls += name
        callingThreads += Thread.currentThread().name
    }

    override fun listConversations(): List<ConversationEntity> {
        record("listConversations")
        return conversations.values.filterNot { it.deleted }.sortedByDescending { it.updatedAtEpochMs }
    }

    override fun findConversation(id: String): ConversationEntity? {
        record("findConversation")
        return conversations[id]
    }

    override fun insertConversation(conversation: ConversationEntity) {
        record("insertConversation")
        conversations[conversation.id] = conversation
    }

    override fun updateConversation(conversation: ConversationEntity) {
        record("updateConversation")
        if (conversation.id in conversations) conversations[conversation.id] = conversation
    }

    override fun deleteConversation(id: String) {
        record("deleteConversation")
        conversations.remove(id)
    }

    override fun listMessages(conversationId: String): List<MessageEntity> {
        record("listMessages")
        return messages.values.filter { it.conversationId == conversationId }
            .sortedWith(compareBy<MessageEntity> { it.createdAtEpochMs }.thenBy { it.id })
    }

    override fun insertMessage(message: MessageEntity) {
        record("insertMessage")
        messages[message.id] = message
    }

    override fun updateMessage(message: MessageEntity) {
        record("updateMessage")
        if (message.id in messages) messages[message.id] = message
    }

    override fun findMessage(id: String): MessageEntity? {
        record("findMessage")
        return messages[id]
    }

    override fun deleteMessages(conversationId: String) {
        record("deleteMessages")
        messages.values.removeAll { it.conversationId == conversationId }
    }

    override fun findRun(id: String): AgentRunEntity? {
        record("findRun")
        return runs[id]
    }

    override fun listRuns(conversationId: String): List<AgentRunEntity> {
        record("listRuns")
        return runs.values.filter { it.conversationId == conversationId }.sortedByDescending { it.startedAtEpochMs }
    }

    override fun insertRun(run: AgentRunEntity) {
        record("insertRun")
        runs[run.id] = run
    }

    override fun updateRun(run: AgentRunEntity) {
        record("updateRun")
        if (run.id in runs) runs[run.id] = run
    }

    override fun deleteRuns(conversationId: String) {
        record("deleteRuns")
        runs.values.removeAll { it.conversationId == conversationId }
    }

    override fun listActivities(runId: String): List<ToolActivityEntity> {
        record("listActivities")
        return activities.values.filter { it.runId == runId }.sortedWith(activityOrder)
    }

    override fun listConversationActivities(conversationId: String): List<ToolActivityEntity> {
        record("listConversationActivities")
        return activities.values.filter { it.conversationId == conversationId }.sortedWith(activityOrder)
    }

    override fun insertActivity(activity: ToolActivityEntity) {
        record("insertActivity")
        activities[activity.id] = activity
    }

    override fun updateActivity(activity: ToolActivityEntity) {
        record("updateActivity")
        if (activity.id in activities) activities[activity.id] = activity
    }

    override fun deleteActivities(conversationId: String) {
        record("deleteActivities")
        activities.values.removeAll { it.conversationId == conversationId }
    }

    override fun listTaskDisplays(): List<TaskDisplayEntity> {
        record("listTaskDisplays")
        return taskDisplays.values.sortedWith(compareByDescending<TaskDisplayEntity> { it.createdAtEpochMs }.thenBy { it.sessionKey })
    }

    override fun insertTaskDisplay(display: TaskDisplayEntity) {
        record("insertTaskDisplay")
        taskDisplays[display.sessionKey] = display
    }

    override fun deleteAllTaskDisplays() {
        record("deleteAllTaskDisplays")
        taskDisplays.clear()
    }

    private companion object {
        val activityOrder = compareBy<ToolActivityEntity> { it.sequence }.thenBy { it.createdAtEpochMs }
    }
}
