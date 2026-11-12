package app.mibot.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mibot.core.Bot
import app.mibot.core.LiveState
import app.mibot.core.TimeText
import app.mibot.core.User
import app.mibot.core.isWaitingForYou
import app.mibot.core.previewFor
import app.mibot.notify.HubService

/** The first letters of a name, for the round account button: "Arshad Kazi" is "AK". */
internal fun initials(user: User?): String {
    val words = (user?.name?.takeIf { it.isNotBlank() } ?: user?.email.orEmpty()).trim().split(Regex("[\\s@._-]+")).filter { it.isNotEmpty() }
    return words.take(2).joinToString("") { it.take(1).uppercase() }.ifEmpty { "Me" }
}

/** The bot list: the primary bot large at the top, the others under it, a search box at the bottom. */
@Composable
fun HomeScreen() {
    val hub = LocalHub.current
    val nav = LocalNav.current
    val app = LocalApp.current
    val state by hub.state.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    val tick = rememberTicker(30_000) // keeps "5m" and "Yesterday" true while the screen stays open

    // Asked once, here, after sign-in: without it Android shows nothing when a bot finishes.
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { HubService.sync(app) }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && app.store.notifications && !app.notifier.allowed) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    val primary = state.primary
    val q = query.trim()
    val others = state.others.filter { q.isEmpty() || it.name.contains(q, ignoreCase = true) || it.label.contains(q, ignoreCase = true) }
    val showPrimary = primary != null && (q.isEmpty() || primary.name.contains(q, ignoreCase = true))

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(46.dp).clip(CircleShape).border(1.dp, Mi.colors.border, CircleShape).clickable(role = Role.Button) { nav.go(Dest.Settings) }
                    .semantics { contentDescription = "Settings" },
                contentAlignment = Alignment.Center,
            ) { Text(initials(state.user), style = Type.small, fontWeight = FontWeight.SemiBold, color = Mi.colors.text) }
            Spacer(Modifier.weight(1f))
            CircleButton(MiIcons.plus, "New bot", { nav.go(Dest.CreateBot) })
        }

        if (!state.online && state.problem != null) {
            Text(
                "Not connected to your hub. ${state.problem}", style = Type.small, color = Mi.colors.textSecondary, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
            )
        }

        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = 12.dp)) {
            if (showPrimary && primary != null) {
                item(key = "primary") { PrimaryBot(primary, state.live[primary.id]) { nav.go(Dest.Chat(primary.id)) } }
            }
            items(others, key = { it.id }) { bot -> BotRow(bot, state.live[bot.id], tick) { nav.go(Dest.Chat(bot.id)) } }
            if (!state.ready) item { EmptyNote(if (state.problem != null) "Waiting for your hub…" else "Loading your bots…") }
            else if (!showPrimary && others.isEmpty()) item { EmptyNote(if (q.isEmpty()) "No bots yet. Make one with the plus button." else "No bot is called that.") }
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            MiTextField(query, { query = it }, "Search", Modifier.weight(1f), leading = { MiIcon(MiIcons.search, 20.dp, Mi.colors.textTertiary) })
            Spacer(Modifier.width(10.dp))
            CircleButton(MiIcons.apps, "Connect apps", { nav.go(Dest.Apps(null)) }, size = 52.dp)
        }
    }
}

@Composable
private fun PrimaryBot(bot: Bot, live: LiveState?, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(top = 18.dp, bottom = 26.dp, start = 24.dp, end = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BotAvatar(bot, 148.dp, mood = if (live?.isRunning == true) Mood.Working else Mood.Awake, star = true)
        Spacer(Modifier.height(16.dp))
        Text(bot.name, style = Type.title, color = Mi.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            StatusDot(live, bot.unread)
            Text(previewFor(bot, live), style = Type.small, color = Mi.colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun BotRow(bot: Bot, live: LiveState?, @Suppress("UNUSED_PARAMETER") tick: Int, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        BotAvatar(bot, 52.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(bot.name, style = Type.bodyStrong, color = Mi.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (bot.label.isNotEmpty()) Text("  ${bot.label}", style = Type.small, color = Mi.colors.textTertiary, maxLines = 1)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(live, bot.unread)
                Text(previewFor(bot, live), style = Type.small, color = Mi.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(TimeText.listTime(bot.lastActivityAt), style = Type.caption, color = Mi.colors.textTertiary)
    }
}

/** Before the preview: a breathing dot while the bot works, an amber one when it waits for you, a solid one for unread. */
@Composable
private fun StatusDot(live: LiveState?, unread: Int) {
    when {
        live.isWaitingForYou -> Box(Modifier.padding(end = 7.dp).size(8.dp).background(WAITING, CircleShape))
        live?.isRunning == true -> PulseDot(Mi.colors.positive, 8.dp, Modifier.padding(end = 7.dp))
        unread > 0 -> Box(Modifier.padding(end = 7.dp).size(8.dp).background(Mi.colors.text, CircleShape))
    }
}

internal val WAITING = androidx.compose.ui.graphics.Color(0xFFF59312)
