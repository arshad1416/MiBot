package app.mibot.core

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/*
 * What the hub sends and takes, as server/src/types.ts has it. Statuses and kinds stay plain strings,
 * and unknown keys are skipped, so a newer hub does not break an older app.
 */

val HubJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
    isLenient = true
}

@Serializable
data class User(val id: String, val email: String = "", val name: String = "", val avatarUrl: String? = null)

@Serializable
data class Bot(
    val id: String,
    val name: String = "",
    val label: String = "",
    val description: String = "",
    val instructions: String = "",
    val avatarShape: String = "blob",
    val avatarColor: String = "black",
    val mode: String = "personal",
    val isPrimary: Boolean = false,
    /** "room" = a group chat: its members answer instead of the bot itself. */
    val kind: String? = null,
    val pinned: Boolean = false,
    val effort: String = "medium",
    val voice: String = "",
    val voiceSpeed: Double = 1.0,
    val voiceLanguage: String = "auto",
    val model: String? = null,
    val provider: String? = null,
    val autoModel: Boolean = false,
    val weight: String = "heavy",
    val generation: Int = 0,
    val unread: Int = 0,
    val lastPreview: String? = null,
    val lastActivityAt: Long = 0,
    val createdAt: Long = 0,
) {
    val isRoom get() = kind == "room"
    val displayName get() = if (label.isNotEmpty()) "$name · $label" else name
}

/** One line of a conversation. `seq` orders rows; `updatedSeq` grows each time a row is rewritten (rows change while a reply streams). */
@Serializable
data class Row(
    val botId: String,
    val generation: Int = 0,
    val seq: Int,
    val updatedSeq: Int = 0,
    val kind: String = "",
    val body: RowBody,
    val createdAt: Long = 0,
)

@Serializable(with = RowBodySerializer::class)
sealed interface RowBody

@Serializable
data class Sender(val kind: String = "", val id: String = "", val name: String = "")

@Serializable
data class Citation(val url: String, val title: String? = null)

@Serializable
data class MessageBody(
    val role: String = "assistant",
    val text: String = "",
    val streaming: Boolean = false,
    /** Set when the message came from another bot, a routine or a voice call rather than the person typing. */
    val from: Sender? = null,
    val citations: List<Citation> = emptyList(),
) : RowBody {
    val isUser get() = role == "user"
}

@Serializable
data class ToolCallBody(
    val callId: String = "",
    val name: String = "",
    /** What the step does, in words: "Clicking on screen". */
    val label: String = "",
    /** running | done | error | awaiting-approval | denied */
    val status: String = "done",
    val args: JsonElement? = null,
    val summary: String? = null,
    /** A data: URL of a small picture, for steps on the bot's computer. */
    val screenshot: String? = null,
) : RowBody

sealed interface CardBody : RowBody {
    val status: String
}

@Serializable
data class ApprovalCard(
    val approvalId: String = "",
    val title: String = "",
    val detail: String = "",
    val tool: String = "",
    val toolkit: String? = null,
    val toolkitName: String? = null,
    val logo: String? = null,
    val args: JsonElement? = null,
    /** pending | allowed | always | denied | expired */
    override val status: String = "pending",
) : CardBody

@Serializable
data class QuestionCard(
    val question: String = "",
    val options: List<String> = emptyList(),
    /** pending | answered | dismissed */
    override val status: String = "pending",
    val answer: String? = null,
) : CardBody

@Serializable
data class ConnectCard(
    val toolkit: String = "",
    val toolkitName: String = "",
    val logo: String? = null,
    val reason: String = "",
    /** pending | connected | skipped */
    override val status: String = "pending",
) : CardBody

@Serializable
data class DraftCard(
    /** email | slack | message */
    val channel: String = "message",
    val to: String = "",
    val subject: String? = null,
    val text: String = "",
    val approvalId: String = "",
    /** pending | sent | discarded */
    override val status: String = "pending",
) : CardBody

@Serializable
data class VoiceLine(val role: String = "", val text: String = "")

@Serializable
data class VoiceCallRecord(
    val id: String = "",
    val phase: String = "",
    val durationMs: Long? = null,
    val summary: String? = null,
    val transcript: List<VoiceLine> = emptyList(),
    val tasks: List<String> = emptyList(),
)

@Serializable
data class EventBody(val event: String = "", val text: String = "", val ref: String? = null, val call: VoiceCallRecord? = null) : RowBody

@Serializable
data class NoticeBody(val text: String = "", val tone: String = "info") : RowBody

/** A kind of row this app does not know yet. It is not shown. */
@Serializable
data object UnknownBody : RowBody

private fun JsonElement.field(name: String): String? = (this as? JsonObject)?.get(name)?.jsonPrimitive?.contentOrNull

object RowBodySerializer : JsonContentPolymorphicSerializer<RowBody>(RowBody::class) {
    override fun selectDeserializer(element: JsonElement): DeserializationStrategy<RowBody> = when (element.field("type")) {
        "message" -> MessageBody.serializer()
        "tool-call" -> ToolCallBody.serializer()
        "event" -> EventBody.serializer()
        "notice" -> NoticeBody.serializer()
        "card" -> when (element.field("card")) {
            "approval" -> ApprovalCard.serializer()
            "question" -> QuestionCard.serializer()
            "connect" -> ConnectCard.serializer()
            "draft" -> DraftCard.serializer()
            else -> UnknownBody.serializer()
        }
        else -> UnknownBody.serializer()
    }
}

@Serializable
data class Activity(val kind: String = "working", val detail: String? = null, val since: Long = 0)

@Serializable
data class Awaiting(val reason: String = "", val since: Long = 0)

/** What a bot is doing right now. */
@Serializable
data class LiveState(
    val botId: String,
    val isRunning: Boolean = false,
    val activity: Activity? = null,
    val awaiting: Awaiting? = null,
    val startedAt: Long? = null,
    /** A short line from the model's own reasoning. */
    val caption: String? = null,
    /** Steps taken so far in this turn. */
    val steps: Int = 0,
)

@Serializable
data class Routine(
    val id: String,
    val botId: String = "",
    val name: String = "",
    val prompt: String = "",
    val schedule: String = "",
    val timezone: String = "",
    val enabled: Boolean = false,
    val nextRunAt: Long? = null,
    val lastRunAt: Long? = null,
    val description: String = "",
)

@Serializable
data class LibraryFile(val name: String, val size: Long = 0, val modifiedAt: Long = 0, val isDir: Boolean = false)

@Serializable
data class Screen(val botId: String = "", val screenId: String = "", val width: Int? = null, val height: Int? = null)

@Serializable
data class ComputerState(
    /** absent | creating | starting | running | suspending | suspended | resuming | error | stopped */
    val status: String = "absent",
    val provider: String? = null,
    val error: String? = null,
    val screens: List<Screen> = emptyList(),
)

@Serializable
data class Toolkit(
    val slug: String,
    val name: String = "",
    val logo: String? = null,
    val description: String? = null,
    val categories: List<String> = emptyList(),
    val connected: Boolean = false,
    /** oauth | api_key | none */
    val auth: String? = null,
    val custom: Boolean = false,
    /** For a connected app: the bots it is switched on for. */
    val bots: List<String> = emptyList(),
)

@Serializable
data class VoiceOption(val id: String, val name: String = "", val gender: String? = null)

/** What the hub pushes on GET /api/events. */
@Serializable(with = ServerEventSerializer::class)
sealed interface ServerEvent {
    @Serializable data class Hello(val serverTime: Long = 0) : ServerEvent
    @Serializable data class Rows(val botId: String, val generation: Int = 0, val rows: List<Row> = emptyList()) : ServerEvent
    @Serializable data class Cleared(val botId: String, val generation: Int = 0) : ServerEvent
    @Serializable data class Live(val state: LiveState) : ServerEvent
    @Serializable data object Roster : ServerEvent
    @Serializable data class BotChanged(val bot: Bot) : ServerEvent
    @Serializable data class Computer(val state: ComputerState) : ServerEvent
    @Serializable data class Routines(val botId: String) : ServerEvent
    @Serializable data object Connections : ServerEvent
    /** Pings, and kinds this app does not act on. */
    @Serializable data object Other : ServerEvent
}

object ServerEventSerializer : JsonContentPolymorphicSerializer<ServerEvent>(ServerEvent::class) {
    override fun selectDeserializer(element: JsonElement): DeserializationStrategy<ServerEvent> = when (element.field("type")) {
        "hello" -> ServerEvent.Hello.serializer()
        "rows" -> ServerEvent.Rows.serializer()
        "cleared" -> ServerEvent.Cleared.serializer()
        "live" -> ServerEvent.Live.serializer()
        "roster" -> ServerEvent.Roster.serializer()
        "bot" -> ServerEvent.BotChanged.serializer()
        "computer" -> ServerEvent.Computer.serializer()
        "routines" -> ServerEvent.Routines.serializer()
        "connections" -> ServerEvent.Connections.serializer()
        else -> ServerEvent.Other.serializer()
    }
}
