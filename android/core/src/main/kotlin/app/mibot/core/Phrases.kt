package app.mibot.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/* The words for what a bot is doing, the same ones the web app uses (web/src/lib/phrases.ts). */

private val PHRASES = mapOf(
    "thinking" to listOf("Thinking it through", "Mulling it over", "Putting it together"),
    "working" to listOf("Getting it done", "Making progress", "Working on it", "Handling it", "Chipping away"),
    "searching_web" to listOf("Digging around"),
    "reading_web" to listOf("Doing some reading"),
    "browsing_web" to listOf("Clicking through pages"),
    "drafting_file" to listOf("Jotting it down"),
    "on_its_computer" to listOf("Clicking around"),
    "running_commands" to listOf("Running a few commands"),
    "waiting_on_command" to listOf("Waiting on results"),
    "waiting_for_you" to listOf("Needs your input"),
    "remembering" to listOf("Making a note of it"),
)

private val STATUS = mapOf(
    "thinking" to "Thinking", "working" to "Working", "searching_web" to "Searching", "reading_web" to "Reading",
    "browsing_web" to "Browsing", "on_its_computer" to "On its computer", "running_commands" to "Running commands",
    "waiting_on_command" to "Waiting on results", "messaging_named" to "Checking in", "connecting_service" to "Using an app",
    "waiting_for_you" to "Needs you", "drafting_file" to "Writing", "remembering" to "Remembering",
    "waiting_for_machine" to "Waiting for a machine", "paused" to "Paused",
)

/** A detail that reads as a name ("Gmail") rather than as an id ("gmail_send"). */
private fun nice(s: String?) = !s.isNullOrEmpty() && !s.contains('_') && !Regex("^[a-z0-9-]+$").matches(s)

private fun messagedName(live: LiveState, rows: List<Row>): String? {
    val running = rows.lastOrNull { (it.body as? ToolCallBody)?.status == "running" }?.body as? ToolCallBody
    // The model wrote these arguments; a name that is not text is ignored, as the web app does.
    ((running?.args as? JsonObject)?.get("bot_name") as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { return it }
    return live.activity?.detail?.takeIf { nice(it) }
}

/** The bot has stopped to wait for the person (an approval, a question, an app to connect). */
val LiveState?.isWaitingForYou: Boolean get() = this != null && (awaiting != null || activity?.kind == "waiting_for_you")

/** The job cannot move: no machine can take it yet, or the machine running it went away. */
val LiveState?.isOnHold: Boolean get() = this?.activity?.kind == "waiting_for_machine" || this?.activity?.kind == "paused"

/** The line beside a working bot when the hub sent none of its own. `tick` rotates kinds that have several. */
fun activityPhrase(live: LiveState, rows: List<Row>, tick: Int): String {
    val a = live.activity ?: return if (live.awaiting != null) "Needs your input" else "Working on it"
    return when {
        a.kind == "messaging_named" -> messagedName(live, rows)?.let { "Checking with $it" } ?: "Checking with another Bot"
        a.kind == "connecting_service" -> if (nice(a.detail)) "Working in ${a.detail}" else "Working in a connected app"
        live.isOnHold -> a.detail?.takeIf { it.isNotEmpty() } ?: if (a.kind == "paused") "Paused until its machine is back" else "Waiting for a machine to take this"
        else -> (PHRASES[a.kind] ?: PHRASES.getValue("working")).let { it[Math.floorMod(tick, it.size)] }
    }
}

/** One or two words for the same thing: "Thinking", "Browsing", "Needs you". */
fun activityStatus(live: LiveState, rows: List<Row>): String {
    if (live.isWaitingForYou) return "Needs you"
    val a = live.activity ?: return "Thinking"
    return when (a.kind) {
        "messaging_named" -> messagedName(live, rows)?.let { "Checking with $it" } ?: STATUS.getValue("messaging_named")
        "connecting_service" -> if (nice(a.detail)) "Working in ${a.detail}" else STATUS.getValue("connecting_service")
        else -> STATUS[a.kind] ?: "Working"
    }
}

/** The second line of a bot on the home screen. */
fun previewFor(bot: Bot, live: LiveState?): String = when {
    live?.awaiting != null -> "Waiting for you: ${live.awaiting.reason}"
    live?.isRunning == true && bot.lastPreview.isNullOrEmpty() -> "Working…"
    else -> bot.lastPreview?.takeIf { it.isNotEmpty() } ?: "No messages yet"
}

private val KNOWN_APPS = mapOf(
    "github" to "GitHub", "gitlab" to "GitLab", "hubspot" to "HubSpot", "linkedin" to "LinkedIn", "youtube" to "YouTube", "clickup" to "ClickUp",
    "googledocs" to "Google Docs", "googledrive" to "Google Drive", "googlesheets" to "Google Sheets", "googlecalendar" to "Google Calendar",
    "googletasks" to "Google Tasks", "googlemeet" to "Google Meet",
)

/** "googlecalendar" is "Google Calendar", "slack" is "Slack". */
fun appName(slug: String): String = KNOWN_APPS[slug.lowercase()]
    ?: slug.replace(Regex("^(google|microsoft)(?=\\w)", RegexOption.IGNORE_CASE), "$1 ").replace(Regex("[-_]+"), " ")
        .split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }

/** "GMAIL_SEND_EMAIL" is "Gmail send email". */
fun toolName(tool: String): String = tool.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }
