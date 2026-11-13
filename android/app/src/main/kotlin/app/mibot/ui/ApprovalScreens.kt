package app.mibot.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mibot.core.Approval
import app.mibot.core.HubException
import app.mibot.core.HubSession
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Every request that waits for the person, from any bot, with what it would do and two answers:
 * Approve once or Deny. `focusId` is the one a notification opened; it is shown first and marked.
 * Requests answered here or elsewhere stay on the list with what became of them, without buttons.
 */
@Composable
fun ApprovalsScreen(focusId: String?) {
    val hub = LocalHub.current
    val nav = LocalNav.current
    val state by hub.state.collectAsStateWithLifecycle()
    val tick = rememberTicker(1_000)
    // Fetch on arrival, so a request opened from a notification is current even if the line was down.
    LaunchedEffect(Unit) { runCatching { hub.loadApprovals() } }

    val pending = state.pendingApprovals
    val settled = state.approvals.values.filter { !it.pending }.sortedByDescending { it.resolvedAt ?: it.createdAt }.take(20)
    val list = (pending + settled).let { all -> focusId?.let { id -> all.sortedByDescending { it.id == id } } ?: all }
    val listState = rememberLazyListState()

    Screen("Waiting for you", { nav.back() }) {
        if (list.isEmpty()) EmptyNote("Nothing is waiting for you. When a bot asks before doing something, it shows up here.")
        LazyColumn(state = listState, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(list, key = { it.id }) { a -> ApprovalItem(a, hub, focused = a.id == focusId, skewMs = state.clockSkewMs, tick = tick) }
        }
    }
}

/** One request: who asks, what for, the command or details, how long it waits, and the answers while it can still be answered. */
@Composable
fun ApprovalItem(a: Approval, hub: HubSession, focused: Boolean = false, skewMs: Long = 0, @Suppress("UNUSED_PARAMETER") tick: Int = 0, showBot: Boolean = true) {
    val scope = rememberCoroutineScope()
    val state by hub.state.collectAsStateWithLifecycle()
    val bot = state.bot(a.botId)
    val colors = botColors(bot?.avatarColor ?: "black")
    var busy by remember(a.id) { mutableStateOf(false) }
    var note by rememberSaveable(a.id) { mutableStateOf<String?>(null) }
    val now = System.currentTimeMillis()
    val open = a.answerable(now, skewMs)

    fun answer(decision: String) {
        if (busy) return
        busy = true
        scope.launch {
            note = try {
                hub.decide(a.id, decision).error
            } catch (e: HubException) {
                e.message
            } finally {
                busy = false
            }
        }
    }

    MiCard(
        modifier = if (focused) Modifier.border(2.dp, colors.action, RoundedCornerShape(26.dp)) else Modifier,
        padding = PaddingValues(18.dp),
    ) {
        if (showBot) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (bot != null) BotAvatar(bot, 32.dp) else Box(Modifier.size(32.dp).background(Mi.colors.surfaceHigh, CircleShape))
                Spacer(Modifier.width(10.dp))
                Text(a.botName.ifBlank { bot?.name ?: "A bot" }, style = Type.small, color = Mi.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                a.toolkitName?.let { Text(it, style = Type.caption, color = Mi.colors.textTertiary) }
            }
            Spacer(Modifier.size(10.dp))
        }
        Text(a.title.ifBlank { "Allow ${a.botName} to do this?" }, style = Type.bodyStrong, color = Mi.colors.text)
        CommandDetails(a)
        when {
            open -> {
                Text(expiresIn(a.expiresAt - (now + skewMs)), style = Type.caption, color = Mi.colors.textTertiary, modifier = Modifier.padding(top = 10.dp))
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton("Deny", { answer("deny") }, Modifier.weight(1f), primary = false, enabled = !busy)
                    PillButton(if (busy) "Sending…" else "Approve once", { answer("once") }, Modifier.weight(1f), enabled = !busy, color = colors.action, onColor = colors.onAction)
                }
            }
            // Its time ran out on this phone's clock before the hub said so: no buttons, and no answer the hub would refuse.
            a.pending -> Text("Expired before anyone answered", style = Type.small, color = Mi.colors.textSecondary, modifier = Modifier.padding(top = 12.dp))
            else -> Text(a.outcome, style = Type.small, color = Mi.colors.textSecondary, modifier = Modifier.padding(top = 12.dp))
        }
        note?.let { Text(it, style = Type.small, color = Mi.colors.danger, modifier = Modifier.padding(top = 8.dp)) }
    }
}

/** What the bot would run or send, in a box of fixed-width text that scrolls sideways for long commands. */
@Composable
private fun CommandDetails(a: Approval) {
    val lines = commandLines(a)
    if (lines.isBlank()) return
    Text("Details", style = Type.caption, color = Mi.colors.textTertiary, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
    Box(
        Modifier.fillMaxWidth().background(Mi.colors.surfaceHigh, RoundedCornerShape(10.dp)).padding(12.dp)
            .horizontalScroll(rememberScrollState()).semantics { contentDescription = "Details: $lines" },
    ) {
        Text(lines, style = Type.small.copy(fontFamily = FontFamily.Monospace), color = Mi.colors.text, maxLines = 30, overflow = TextOverflow.Ellipsis)
    }
}

/** A command when there is one, else the hub's one-line-per-argument summary, else the arguments as they are. */
internal fun commandLines(a: Approval): String {
    val args = a.args as? JsonObject
    val command = (args?.get("command") as? JsonPrimitive)?.takeIf { it.isString }?.content
    return when {
        command != null -> command
        a.detail.isNotBlank() -> a.detail
        args != null && args.isNotEmpty() -> args.entries.joinToString("\n") { (k, v) -> "$k: ${if (v is JsonPrimitive && v.isString) v.content else v.toString()}".take(300) }
        else -> ""
    }
}

internal fun expiresIn(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return when {
        s < 60 -> "Expires in ${s}s"
        s < 3600 -> "Expires in ${s / 60} min"
        else -> "Expires in ${s / 3600} h"
    }
}

/** On the bot list: how many requests wait, as a strip that opens them. */
@Composable
fun ApprovalsStrip(count: Int, onClick: () -> Unit) {
    if (count == 0) return
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().background(WAITING.copy(alpha = 0.16f), RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(WAITING, CircleShape))
        Spacer(Modifier.width(10.dp))
        Text(if (count == 1) "1 request is waiting for you" else "$count requests are waiting for you", style = Type.bodyStrong, color = Mi.colors.text, modifier = Modifier.weight(1f))
        MiIcon(MiIcons.chevRight, 18.dp, Mi.colors.textSecondary)
    }
}
