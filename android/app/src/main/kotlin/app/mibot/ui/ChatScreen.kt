package app.mibot.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mibot.core.Bot
import app.mibot.core.CardBody
import app.mibot.core.ChatItem
import app.mibot.core.EventBody
import app.mibot.core.HubException
import app.mibot.core.LiveState
import app.mibot.core.MessageBody
import app.mibot.core.NoticeBody
import app.mibot.core.Row as ChatRow
import app.mibot.core.TimeText
import app.mibot.core.ToolCallBody
import app.mibot.core.VoiceState
import app.mibot.core.activityPhrase
import app.mibot.core.activityStatus
import app.mibot.core.buildItems
import app.mibot.core.indexOfMessage
import app.mibot.core.isOnHold
import app.mibot.core.isWaitingForYou
import app.mibot.core.stepsThisTurn
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Runs a call to the hub and, when it fails, says why on the screen that asked. */
typealias Run = (suspend () -> Unit) -> Unit

/**
 * One bot's conversation. From the top: who it is, what it is doing right now (the thinking block
 * sits up here, not at the bottom), the conversation, and the box to write or talk in.
 */
@Composable
fun ChatScreen(botId: String) {
    val hub = LocalHub.current
    val nav = LocalNav.current
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val state by hub.state.collectAsStateWithLifecycle()
    val bot = state.bot(botId)
    if (bot == null) {
        // Deleted on another device while this was open.
        if (state.ready) LaunchedEffect(Unit) { nav.back() }
        return
    }
    val live = state.live[botId]
    val transcript = state.transcripts[botId]
    val rows = transcript?.rows.orEmpty()
    val items = remember(rows) { buildItems(rows).asReversed() }
    val colors = botColors(bot.avatarColor)
    var error by remember { mutableStateOf<String?>(null) }
    val run: Run = { work ->
        scope.launch {
            try {
                work()
                error = null
            } catch (e: HubException) {
                error = e.message
            }
        }
    }

    // While this chat is on screen the hub keeps it read, and the phone stays quiet about it.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, botId) {
        val watch = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                hub.openBot(botId)
                app.notifier.seen(botId)
            } else if (event == Lifecycle.Event.ON_STOP) hub.closeBot(botId)
        }
        owner.lifecycle.addObserver(watch)
        onDispose {
            owner.lifecycle.removeObserver(watch)
            hub.closeBot(botId)
        }
    }

    val list = rememberLazyListState()
    // The list is drawn bottom-up, so item 0 is the newest. Stay on it when something new arrives, unless the person scrolled up to read.
    // After the person sends, follow the chat down even if the keyboard closing left it scrolled up.
    var followNext by remember { mutableStateOf(false) }
    val newest = items.firstOrNull()?.key
    LaunchedEffect(newest) {
        if (followNext || list.firstVisibleItemIndex <= 1) list.scrollToItem(0)
        followNext = false
    }
    LaunchedEffect(items.size, transcript?.hasEarlier) {
        if (transcript?.hasEarlier != true) return@LaunchedEffect
        snapshotFlow { (list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= list.layoutInfo.totalItemsCount - 4 }.first { it }
        runCatching { hub.loadEarlier(botId) }
    }
    val scrolledUp by remember { derivedStateOf { list.firstVisibleItemIndex > 2 } }

    // Reply: the message the next one answers, and the one briefly lit after jumping to it from a quote.
    var replyingTo by remember(botId) { mutableStateOf<ChatRow?>(null) }
    var litSeq by remember { mutableStateOf<Int?>(null) }
    val latestItems by rememberUpdatedState(items)
    fun jumpTo(seq: Int) {
        scope.launch {
            // An old message may not be loaded yet: fetch earlier pages a few times before giving up.
            var at = indexOfMessage(latestItems, seq)
            repeat(5) {
                if (at >= 0 || transcript?.hasEarlier != true) return@repeat
                runCatching { hub.loadEarlier(botId) }
                delay(150)
                at = indexOfMessage(latestItems, seq)
            }
            if (at < 0) { error = "That message is no longer in this conversation."; return@launch }
            list.animateScrollToItem(at)
            litSeq = seq
            delay(1200)
            if (litSeq == seq) litSeq = null
        }
    }

    val voice = app.voice.state
    val inCall = voice.botId == botId && voice.status != VoiceState.Status.Idle
    var confirmFresh by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
            CircleButton(MiIcons.chevLeft, "Back", { nav.back() }, Modifier.align(Alignment.CenterStart))
            Row(
                Modifier.align(Alignment.Center).padding(horizontal = 58.dp).clip(RoundedCornerShape(50)).border(1.dp, Mi.colors.border, RoundedCornerShape(50))
                    .clickable { nav.go(Dest.Profile(botId)) }.padding(start = 8.dp, end = 16.dp, top = 7.dp, bottom = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BotAvatar(bot, 30.dp)
                Spacer(Modifier.width(8.dp))
                Text(bot.name, style = Type.bodyStrong, color = Mi.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            MoreButton(
                listOf(
                    MenuItem("View profile", MiIcons.user) { nav.go(Dest.Profile(botId)) },
                    MenuItem("Its computer", MiIcons.monitor) { nav.go(Dest.Computer(botId)) },
                    MenuItem("New conversation", MiIcons.edit) { confirmFresh = true },
                ),
                Modifier.align(Alignment.CenterEnd),
            )
        }

        AnimatedVisibility(live?.isRunning == true || live.isWaitingForYou) {
            if (live != null) ThinkingBlock(bot, live, rows, onStop = { run { hub.client.interrupt(botId) } })
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (transcript?.loaded == true && items.isEmpty()) {
                Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    BotAvatar(bot, 96.dp, mood = Mood.Awake)
                    Spacer(Modifier.height(16.dp))
                    Text(bot.description.ifBlank { "Ask ${bot.name} anything." }, style = Type.body, color = Mi.colors.textSecondary, textAlign = TextAlign.Center)
                }
            }
            LazyColumn(
                Modifier.fillMaxSize(), state = list, reverseLayout = true,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(items, key = { it.key }) { item ->
                    when (item) {
                        is ChatItem.Divider -> Text(
                            TimeText.divider(item.at), style = Type.caption, color = Mi.colors.textTertiary, textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        )
                        is ChatItem.Group -> MessageGroup(item, bot, state.bots, colors, litSeq, onReply = { replyingTo = it }, onQuote = ::jumpTo)
                        is ChatItem.Steps -> StepsLine(item)
                        is ChatItem.Single -> SingleRow(item.row, bot, run)
                    }
                }
                if (transcript?.loaded != true) item { EmptyNote("Loading the conversation…") }
            }
            if (scrolledUp) {
                CircleButton(
                    MiIcons.arrowDown, "Latest messages", { scope.launch { list.animateScrollToItem(0) } },
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp), size = 40.dp, fill = Mi.colors.surfaceHigh,
                )
            }
        }

        error?.let { ErrorNote(it, Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }

        if (inCall) VoiceBar(bot, voice, colors)
        else Composer(
            bot, colors,
            replying = replyingTo,
            onCancelReply = { replyingTo = null },
            onSend = { text ->
                val answers = replyingTo?.seq
                followNext = true
                hub.client.sendMessage(botId, text, answers)
                // A failed send keeps the reply target, too. Do not clear a newer selection.
                if (replyingTo?.seq == answers) replyingTo = null
            },
            onCall = { app.voice.start(botId) },
            onError = { error = it },
        )
    }

    if (confirmFresh) {
        ConfirmDialog(
            "Start a new conversation?", "${bot.name} starts over with a clean chat. What it has saved to memory and its files stay.",
            "Start over", { run { hub.client.newConversation(botId) } }, { confirmFresh = false },
        )
    }
}

/**
 * What the bot is doing right now, docked under the top bar: its face at work, a line in words, how
 * many steps and how long, and a way to stop it. Tap to see the steps of this turn.
 */
@Composable
private fun ThinkingBlock(bot: Bot, live: LiveState, rows: List<ChatRow>, onStop: () -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    val tick = rememberTicker(1_000)
    val waiting = live.isWaitingForYou
    val steps = maxOf(live.steps, stepsThisTurn(rows))
    val headline = when {
        waiting -> live.awaiting?.reason?.takeIf { it.isNotBlank() } ?: "Needs your input"
        else -> activityPhrase(live, rows, tick / 6)
    }
    val detail = buildList {
        add(activityStatus(live, rows))
        if (steps > 0) add(if (steps == 1) "1 step" else "$steps steps")
        live.startedAt?.let { if (!live.isOnHold) add(TimeText.duration(System.currentTimeMillis() - it)) }
    }.joinToString(" · ")

    Column(
        Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Mi.colors.surface)
            .border(1.dp, Mi.colors.border, RoundedCornerShape(24.dp)).clickable { open = !open }.animateContentSize().padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BotAvatar(bot, 40.dp, mood = if (waiting || live.isOnHold) Mood.Awake else Mood.Working)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(headline, style = Type.bodyStrong, color = if (waiting) WAITING else Mi.colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(detail, style = Type.caption, color = Mi.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            CircleButton(MiIcons.stop, "Stop", onStop, size = 38.dp)
        }
        // The model's own passing thought, when the hub has one.
        live.caption?.takeIf { it.isNotBlank() && !waiting }?.let {
            Text(it, style = Type.small, fontStyle = FontStyle.Italic, color = Mi.colors.textSecondary, maxLines = if (open) 6 else 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp))
        }
        if (open) {
            val calls = rows.takeLastWhile { !((it.body as? MessageBody)?.isUser ?: false) }.mapNotNull { it.body as? ToolCallBody }.takeLast(8)
            if (calls.isEmpty()) Text("No steps yet.", style = Type.small, color = Mi.colors.textTertiary, modifier = Modifier.padding(top = 8.dp, start = 4.dp))
            else Column(Modifier.padding(top = 8.dp)) { calls.forEach { StepRow(it) } }
        }
    }
}

@Composable
private fun StepRow(call: ToolCallBody) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        when (call.status) {
            "running" -> PulseDot(Mi.colors.positive, 8.dp)
            "error", "denied" -> Box(Modifier.size(8.dp).background(Mi.colors.danger, RoundedCornerShape(4.dp)))
            "awaiting-approval" -> Box(Modifier.size(8.dp).background(WAITING, RoundedCornerShape(4.dp)))
            else -> MiIcon(MiIcons.check, 12.dp, Mi.colors.textTertiary)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(call.label.ifBlank { call.name }, style = Type.small, color = Mi.colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            call.summary?.takeIf { it.isNotBlank() }?.let { Text(it, style = Type.caption, color = Mi.colors.textSecondary, maxLines = 3, overflow = TextOverflow.Ellipsis) }
        }
    }
}

/** A run of steps, folded into one line that opens. */
@Composable
private fun StepsLine(item: ChatItem.Steps) {
    var open by rememberSaveable(item.key) { mutableStateOf(false) }
    val calls = item.calls
    val running = calls.lastOrNull { it.status == "running" }
    val count = if (calls.size == 1) "1 step" else "${calls.size} steps"
    val label = when {
        running != null -> running.label.ifBlank { "Working" }
        item.workedMs != null -> "Worked for ${TimeText.duration(item.workedMs!!)} · $count"
        else -> count
    }
    Column(Modifier.fillMaxWidth().animateContentSize()) {
        Row(
            Modifier.clip(RoundedCornerShape(12.dp)).clickable { open = !open }.padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (running != null) PulseDot(Mi.colors.positive, 8.dp) else MiIcon(MiIcons.check, 14.dp, Mi.colors.textTertiary)
            Spacer(Modifier.width(8.dp))
            Text(label, style = Type.small, color = Mi.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(6.dp))
            MiIcon(if (open) MiIcons.chevDown else MiIcons.chevRight, 12.dp, Mi.colors.textTertiary)
        }
        if (open) {
            Column(Modifier.padding(start = 8.dp)) {
                calls.forEach { StepRow(it) }
                // The last look at the bot's screen during these steps, when there is one.
                val shot = rememberPicture(calls.lastOrNull { !it.screenshot.isNullOrEmpty() }?.screenshot)
                if (shot != null) {
                    Image(shot, contentDescription = "The bot's screen", contentScale = ContentScale.FillWidth, modifier = Modifier.padding(top = 6.dp).fillMaxWidth(0.8f).clip(RoundedCornerShape(12.dp)))
                }
            }
        }
    }
}

/**
 * Messages in a row from one sender. The person's own sit on the right in the bot's colour. Holding a
 * message offers Reply and Copy; a reply shows the message it answers at its top, and tapping that jumps to it.
 */
@Composable
private fun MessageGroup(
    group: ChatItem.Group, bot: Bot, bots: List<Bot>, colors: BotColors, litSeq: Int?,
    onReply: (ChatRow) -> Unit, onQuote: (Int) -> Unit,
) {
    val copy = rememberCopy()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val widest = maxWidth * if (group.mine) 0.82f else 0.92f
        Column(Modifier.fillMaxWidth(), horizontalAlignment = if (group.mine) Alignment.End else Alignment.Start, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // In a group chat, and for things a routine or another bot sent, say who is speaking.
            val from = group.from
            if (from != null && from.kind != "voice") {
                val speaker = bots.firstOrNull { it.id == from.id }
                Row(Modifier.padding(start = 6.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (speaker != null) {
                        BotAvatar(speaker, 18.dp)
                        Spacer(Modifier.width(6.dp))
                    } else if (from.kind == "routine") {
                        MiIcon(MiIcons.clock, 13.dp, Mi.colors.textTertiary)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(from.name.ifBlank { speaker?.name ?: bot.name }, style = Type.caption, color = Mi.colors.textSecondary)
                }
            }
            for (row in group.rows) {
                val body = row.body as MessageBody
                val shape = RoundedCornerShape(22.dp)
                var menu by remember { mutableStateOf(false) }
                val ink = if (group.mine) colors.onBubble else Mi.colors.text
                Column(
                    Modifier.widthIn(max = widest).clip(shape).background(if (group.mine) colors.bubble else Mi.colors.surface)
                        .then(if (row.seq == litSeq) Modifier.border(2.dp, Mi.colors.textTertiary, shape) else Modifier)
                        .combinedClickable(onClick = {}, onLongClick = { menu = true })
                        .padding(horizontal = 16.dp, vertical = 11.dp),
                ) {
                    body.replyTo?.let { q ->
                        Row(
                            Modifier.padding(bottom = 8.dp).height(IntrinsicSize.Min).clip(RoundedCornerShape(10.dp)).background(ink.copy(alpha = 0.08f))
                                .clickable(onClickLabel = "Show the message this answers") { onQuote(q.seq) }
                                .padding(end = 10.dp),
                        ) {
                            Box(Modifier.width(3.dp).fillMaxHeight().background(ink.copy(alpha = 0.35f)))
                            Column(Modifier.padding(start = 8.dp, top = 5.dp, bottom = 5.dp)) {
                                Text(q.whose(bot.name), style = Type.caption, fontWeight = FontWeight.SemiBold, color = ink.copy(alpha = 0.8f), maxLines = 1)
                                Text(q.text, style = Type.small, color = ink.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    if (group.mine) Text(body.text, style = Type.body, color = colors.onBubble)
                    else if (body.text.isEmpty() && body.streaming) PulseDot(Mi.colors.textSecondary, 9.dp, Modifier.padding(vertical = 6.dp))
                    else Markdown(body.text, color = Mi.colors.text)
                    if (body.citations.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Markdown(
                            body.citations.take(6).joinToString("  ") { c -> "[${c.title?.takeIf { it.isNotBlank() } ?: hostOf(c.url)}](${c.url})" },
                            color = Mi.colors.textSecondary, style = Type.caption,
                        )
                    }
                    MiMenu(
                        menu, { menu = false },
                        listOfNotNull(
                            MenuItem("Reply", MiIcons.reply) { onReply(row) }.takeUnless { body.streaming },
                            MenuItem("Copy", MiIcons.copy) { copy(body.text) },
                        ),
                    )
                }
            }
        }
    }
}

private fun hostOf(url: String) = url.substringAfter("://").substringBefore('/').removePrefix("www.")

/** A card, a line saying something happened, or a notice. */
@Composable
private fun SingleRow(row: ChatRow, bot: Bot, run: Run) {
    when (val body = row.body) {
        is CardBody -> CardView(row, body, bot, run)
        is EventBody -> {
            val call = body.call
            Column(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                val line = if (call != null) "Voice call" + (call.durationMs?.let { " · ${TimeText.callLength(it)}" } ?: "") else body.text
                Text(line, style = Type.caption, fontWeight = FontWeight.Medium, color = Mi.colors.textTertiary, textAlign = TextAlign.Center)
                call?.summary?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = Type.small, color = Mi.colors.textSecondary, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp, start = 24.dp, end = 24.dp))
                }
            }
        }
        is NoticeBody -> Text(
            body.text, style = Type.small, textAlign = TextAlign.Center,
            color = if (body.tone == "error") Mi.colors.danger else Mi.colors.textSecondary,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
        )
        else -> {}
    }
}
