package app.mibot.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.mibot.core.ApprovalCard
import app.mibot.core.Bot
import app.mibot.core.CardBody
import app.mibot.core.ConnectCard
import app.mibot.core.DraftCard
import app.mibot.core.HubClient
import app.mibot.core.HubException
import androidx.compose.ui.text.font.FontFamily
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mibot.core.QuestionCard
import app.mibot.core.Row as ChatRow
import app.mibot.core.appName
import app.mibot.core.toolName

/** Open a web address in the phone's browser. Signing in to Google and the like only works there, not inside an app. */
internal fun openInBrowser(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/**
 * Connecting an app, wherever it starts from: the hub either says it is done, sends a sign-in page
 * (opened in the browser), or asks for a key, which the person types here.
 */
class ConnectFlow(private val client: HubClient, private val context: Context) {
    /** Set when the app wants a key: the words to show over the field. */
    var keyPrompt by mutableStateOf<String?>(null)

    suspend fun start(slug: String, botId: String? = null, seq: Int? = null, apiKey: String? = null) {
        val r = client.connectApp(slug, botId, seq, apiKey)
        when (r.status) {
            "redirect" -> r.redirectUrl?.let { openInBrowser(context, if (it.startsWith("/")) client.url(it) else it) }
            "needs_key" -> keyPrompt = r.keyPrompt?.takeIf { it.isNotBlank() } ?: "Paste the key for this app"
            else -> keyPrompt = null
        }
    }
}

/** The field for an app's key and the button that hands it in. */
@Composable
fun KeyEntry(prompt: String, onSave: (String) -> Unit, modifier: Modifier = Modifier) {
    var key by remember { mutableStateOf("") }
    Column(modifier) {
        Text(prompt, style = Type.small, color = Mi.colors.textSecondary, modifier = Modifier.padding(bottom = 8.dp, start = 4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            MiTextField(
                key, { key = it.trim() }, "Key", Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onDone = { if (key.isNotEmpty()) onSave(key) }),
            )
            Spacer(Modifier.width(8.dp))
            PillButton("Save", { onSave(key) }, enabled = key.isNotEmpty(), small = true)
        }
    }
}

@Composable
internal fun CardView(row: ChatRow, body: CardBody, bot: Bot, run: Run) {
    val hub = LocalHub.current
    val colors = botColors(bot.avatarColor)
    MiCard(padding = PaddingValues(18.dp)) {
        when (body) {
            is ApprovalCard -> {
                val app = body.toolkitName ?: body.toolkit?.let(::appName)
                CardHead(app ?: bot.name, body.logo, body.title.ifBlank { "${bot.name} wants to ${toolName(body.tool).lowercase()}" }, if (app != null) toolName(body.tool) else null)
                if (body.detail.isNotBlank()) Text(body.detail, style = Type.small.copy(fontFamily = FontFamily.Monospace), color = Mi.colors.textSecondary, maxLines = 12, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
                // The hub's copy says who answered and when it runs out; the card in the chat may lag behind it.
                val state by hub.state.collectAsStateWithLifecycle()
                val held = state.approvals[body.approvalId]
                rememberTicker(5_000)
                val open = body.status == "pending" && (held == null || held.answerable(System.currentTimeMillis(), state.clockSkewMs))
                if (open) {
                    val decide = { decision: String -> run { hub.decide(body.approvalId, decision).error?.let { throw HubException(409, it) } } }
                    Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PillButton("Deny", { decide("deny") }, Modifier.weight(1f), primary = false)
                        PillButton("Approve once", { decide("once") }, Modifier.weight(1f), color = colors.action, onColor = colors.onAction)
                    }
                } else CardOutcome(held?.takeIf { !it.pending }?.outcome ?: when (body.status) { "allowed" -> "Approved"; "always" -> "Always allowed"; "denied" -> "Denied"; "expired" -> "Expired before anyone answered"; else -> "Expired before anyone answered" })
            }

            is QuestionCard -> {
                Text(body.question, style = Type.bodyStrong, color = Mi.colors.text)
                if (body.status == "pending") {
                    var own by rememberSaveable(row.seq) { mutableStateOf("") }
                    val send = { text: String -> run { hub.client.answer(row.botId, row.seq, text) } }
                    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (option in body.options) PillButton(option, { send(option) }, Modifier.fillMaxWidth(), primary = false)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            MiTextField(
                                own, { own = it }, if (body.options.isEmpty()) "Your answer" else "Something else", Modifier.weight(1f),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                                keyboardActions = KeyboardActions(onSend = { if (own.isNotBlank()) send(own.trim()) }),
                            )
                            Spacer(Modifier.width(8.dp))
                            CircleButton(MiIcons.arrowUp, "Send answer", { send(own.trim()) }, fill = colors.action, tint = colors.onAction, enabled = own.isNotBlank())
                        }
                    }
                    TextLink("Skip", { run { hub.client.dismiss(row.botId, row.seq) } }, Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp))
                } else CardOutcome(if (body.status == "answered") "You answered: ${body.answer.orEmpty()}" else "Skipped")
            }

            is ConnectCard -> {
                val name = body.toolkitName.ifBlank { appName(body.toolkit) }
                CardHead(name, body.logo, "Connect $name", null)
                if (body.reason.isNotBlank()) Text(body.reason, style = Type.small, color = Mi.colors.textSecondary, modifier = Modifier.padding(top = 10.dp))
                if (body.status == "pending") {
                    val context = LocalContext.current
                    val flow = remember(row.seq) { ConnectFlow(hub.client, context) }
                    flow.keyPrompt?.let { prompt ->
                        KeyEntry(prompt, { key -> run { flow.start(body.toolkit, row.botId, row.seq, key) } }, Modifier.padding(top = 12.dp))
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PillButton("Skip", { run { hub.client.dismiss(row.botId, row.seq) } }, Modifier.weight(1f), primary = false)
                        PillButton("Connect", { run { flow.start(body.toolkit, row.botId, row.seq) } }, Modifier.weight(1f), color = colors.action, onColor = colors.onAction)
                    }
                } else CardOutcome(if (body.status == "connected") "Connected" else "Skipped")
            }

            is DraftCard -> {
                val what = when (body.channel) { "email" -> "Email"; "slack" -> "Slack message"; else -> "Message" }
                Text(if (body.to.isNotBlank()) "$what to ${body.to}" else what, style = Type.caption, color = Mi.colors.textSecondary)
                body.subject?.takeIf { it.isNotBlank() }?.let { Text(it, style = Type.bodyStrong, color = Mi.colors.text, modifier = Modifier.padding(top = 4.dp)) }
                Spacer(Modifier.height(8.dp))
                Markdown(body.text)
                if (body.status == "pending") {
                    Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PillButton("Discard", { run { hub.client.decide(body.approvalId, "deny") } }, Modifier.weight(1f), primary = false)
                        PillButton("Send", { run { hub.client.decide(body.approvalId, "allow") } }, Modifier.weight(1f), color = colors.action, onColor = colors.onAction)
                    }
                } else CardOutcome(if (body.status == "sent") "Sent" else "Discarded")
            }
        }
    }
}

@Composable
private fun CardHead(app: String, logo: String?, title: String, subtitle: String?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AppLogo(app, logo, 40.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.bodyStrong, color = Mi.colors.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) Text(subtitle, style = Type.caption, color = Mi.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** What became of a card that has been dealt with. */
@Composable
private fun CardOutcome(text: String) {
    Text(text, style = Type.small, color = Mi.colors.textSecondary, modifier = Modifier.padding(top = 12.dp))
}
