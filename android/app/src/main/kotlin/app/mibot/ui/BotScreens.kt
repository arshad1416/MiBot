package app.mibot.ui

import android.app.DownloadManager
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mibot.core.ComputerState
import app.mibot.core.FilesResponse
import app.mibot.core.HubException
import app.mibot.core.LibraryFile
import app.mibot.core.Routine
import app.mibot.core.TimeText
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** How many characters the picker shows side by side. */
private const val SHAPES_IN_A_ROW = 5

/** The state of the bots' computer in a word or two. */
private fun computerWord(c: ComputerState?): String = when (c?.status) {
    null, "absent", "stopped" -> "Off"
    "running" -> "On"
    "suspended", "suspending" -> "Asleep"
    "creating", "starting", "resuming" -> "Starting"
    "error" -> "Has a problem"
    else -> c.status.replaceFirstChar { it.uppercase() }
}

/** A bot's own page: who it is, and the way into its routines, files, computer and apps. */
@Composable
fun ProfileScreen(botId: String) {
    val hub = LocalHub.current
    val nav = LocalNav.current
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val copy = rememberCopy()
    val state by hub.state.collectAsStateWithLifecycle()
    val bot = state.bot(botId)
    if (bot == null) {
        if (state.ready) LaunchedEffect(Unit) { nav.home() }
        return
    }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var notifies by remember { mutableStateOf(app.store.notifies(botId)) }
    LaunchedEffect(botId) { runCatching { hub.loadRoutines(botId) } }
    val routines = state.routines[botId]

    fun run(work: suspend () -> Unit) {
        scope.launch {
            try { work(); error = null } catch (e: HubException) { error = e.message }
        }
    }

    Screen(
        "", { nav.back() },
        action = {
            MoreButton(buildList {
                if (!bot.isPrimary) add(MenuItem("Make primary", MiIcons.star) { run { hub.client.makePrimary(botId) } })
                add(MenuItem("Copy ID", MiIcons.copy) { copy(botId) })
                if (!bot.isPrimary) add(MenuItem("Delete bot", MiIcons.trash, danger = true) { confirmDelete = true })
            })
        },
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            BotAvatar(bot, 128.dp, mood = if (state.live[botId]?.isRunning == true) Mood.Working else Mood.Awake, star = bot.isPrimary)
            Spacer(Modifier.height(14.dp))
            Text(bot.name, style = Type.title, color = Mi.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (bot.label.isNotBlank()) Text(bot.label, style = Type.small, color = Mi.colors.textSecondary)
            if (bot.description.isNotBlank()) {
                Text(bot.description, style = Type.body, color = Mi.colors.textSecondary, textAlign = TextAlign.Center, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp, start = 16.dp, end = 16.dp))
            }
            Spacer(Modifier.height(14.dp))
            PillButton("Edit", { nav.go(Dest.EditBot(botId)) }, primary = false, small = true, icon = MiIcons.edit)
            Spacer(Modifier.height(20.dp))

            error?.let { ErrorNote(it, Modifier.padding(bottom = 12.dp)) }

            MiCard {
                val on = routines?.count { it.enabled } ?: 0
                CardRow("Routines", icon = MiIcons.clock, value = when { routines == null -> null; routines.isEmpty() -> "None"; else -> "$on on" }, onClick = { nav.go(Dest.Routines(botId)) })
                Hairline()
                CardRow("Library", icon = MiIcons.folder, onClick = { nav.go(Dest.Library(botId)) })
                Hairline()
                CardRow("Computer", icon = MiIcons.monitor, value = computerWord(state.computer), onClick = { nav.go(Dest.Computer(botId)) })
                Hairline()
                CardRow("Apps", icon = MiIcons.apps, onClick = { nav.go(Dest.Apps(botId)) })
            }
            Spacer(Modifier.height(14.dp))
            MiCard {
                CardRow("Notifications", icon = MiIcons.bell, subtitle = "Get notified when this bot finishes or is waiting for you") {
                    MiToggle(notifies, { notifies = it; app.store.setNotifies(botId, it) }, label = "Notifications for ${bot.name}")
                }
            }
            Spacer(Modifier.height(14.dp))
            MiCard {
                CardRow("Model, voice and more", icon = MiIcons.sliders, subtitle = "Opens MiBot's web app", onClick = { nav.go(Dest.Web("MiBot", "/")) })
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            "Delete ${bot.name}?", "Its conversation, routines and files go with it. This cannot be undone.", "Delete",
            { run { hub.client.deleteBot(botId); hub.loadBots(); nav.home() } }, { confirmDelete = false }, danger = true,
        )
    }
}

/** Make a new bot (`botId` null) or change one: its name, what it is for, and its character. */
@Composable
fun EditBotScreen(botId: String?) {
    val hub = LocalHub.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val existing = remember(botId) { hub.state.value.bot(botId) }
    var name by rememberSaveable { mutableStateOf(existing?.name.orEmpty()) }
    var label by rememberSaveable { mutableStateOf(existing?.label.orEmpty()) }
    var description by rememberSaveable { mutableStateOf(existing?.description.orEmpty()) }
    var instructions by rememberSaveable { mutableStateOf(existing?.instructions.orEmpty()) }
    var shape by rememberSaveable { mutableStateOf(existing?.avatarShape ?: "blob") }
    var color by rememberSaveable { mutableStateOf(existing?.avatarColor ?: "blue") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun save() {
        if (busy || name.isBlank()) return
        busy = true
        scope.launch {
            try {
                if (botId == null) {
                    val made = hub.client.createBot(name.trim(), shape, color)
                    hub.loadBots()
                    nav.back()
                    nav.go(Dest.Chat(made.id))
                } else {
                    hub.client.updateBot(botId, buildJsonObject {
                        put("name", name.trim()); put("label", label.trim()); put("description", description.trim())
                        put("instructions", instructions.trim()); put("avatarShape", shape); put("avatarColor", color)
                    })
                    nav.back()
                }
            } catch (e: HubException) {
                error = e.message
            } finally {
                busy = false
            }
        }
    }

    Screen(if (botId == null) "New bot" else "Edit", { nav.back() }) {
        Column(Modifier.weight(1f).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
            BotAvatar(shape, color, 112.dp, Modifier.align(Alignment.CenterHorizontally), mood = Mood.Awake)
            Spacer(Modifier.height(20.dp))

            FieldLabel("Name")
            MiTextField(name, { name = it }, "What to call it", keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words))
            Spacer(Modifier.height(18.dp))

            FieldLabel("Shape")
            Column(Modifier.widthIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (row in AVATAR_SHAPES.keys.chunked(SHAPES_IN_A_ROW)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (s in row) {
                            Box(
                                Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(16.dp)).clickable(role = Role.RadioButton) { shape = s },
                                contentAlignment = Alignment.Center,
                            ) { BotAvatar(s, color, 48.dp, ring = s == shape) }
                        }
                        repeat(SHAPES_IN_A_ROW - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
            Spacer(Modifier.height(18.dp))

            FieldLabel("Colour")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for (c in BOT_COLOR_NAMES) {
                    Box(
                        Modifier.size(40.dp).clip(CircleShape)
                            .then(if (c == color) Modifier.border(2.dp, Mi.colors.text, CircleShape).padding(5.dp) else Modifier.padding(3.dp))
                            .background(botColors(c).fill, CircleShape).clickable(role = Role.RadioButton) { color = c },
                    )
                }
            }

            if (botId != null) {
                Spacer(Modifier.height(18.dp))
                FieldLabel("Label")
                MiTextField(label, { label = it }, "A word after the name, such as Work")
                Spacer(Modifier.height(18.dp))
                FieldLabel("What it is for")
                MiTextField(description, { description = it }, "One line about this bot", singleLine = false, maxLines = 4)
                Spacer(Modifier.height(18.dp))
                FieldLabel("Instructions")
                MiTextField(instructions, { instructions = it }, "How it should work and what it should know", singleLine = false, minLines = 4, maxLines = 14)
            }

            error?.let { ErrorNote(it, Modifier.padding(top = 16.dp)) }
            Spacer(Modifier.height(22.dp))
            PillButton(
                when { busy -> "Saving…"; botId == null -> "Create"; else -> "Save" }, ::save, Modifier.fillMaxWidth(),
                enabled = !busy && name.isNotBlank(), color = botColors(color).action, onColor = botColors(color).onAction,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** What a bot does on a schedule. Each can be switched off, or run right now. */
@Composable
fun RoutinesScreen(botId: String) {
    val hub = LocalHub.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val state by hub.state.collectAsStateWithLifecycle()
    val bot = state.bot(botId)
    val routines = state.routines[botId]
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(botId) {
        try { hub.loadRoutines(botId) } catch (e: HubException) { error = e.message }
    }
    fun run(work: suspend () -> Unit) {
        scope.launch {
            try { work(); hub.loadRoutines(botId); error = null } catch (e: HubException) { error = e.message }
        }
    }

    Screen("Routines", { nav.back() }) {
        error?.let { ErrorNote(it, Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
        when {
            routines == null -> EmptyNote("Loading…")
            routines.isEmpty() -> EmptyNote("No routines yet. Ask ${bot?.name ?: "the bot"} to do something every day or every week, and it shows up here.")
            else -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(routines, key = { it.id }) { r -> RoutineCard(r, { on -> run { hub.client.setRoutine(r.id, on) } }, { run { hub.client.runRoutine(r.id) } }) }
            }
        }
    }
}

@Composable
private fun RoutineCard(r: Routine, onToggle: (Boolean) -> Unit, onRun: () -> Unit) {
    MiCard(padding = PaddingValues(start = 20.dp, end = 16.dp, top = 16.dp, bottom = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(r.name.ifBlank { "Routine" }, style = Type.bodyStrong, color = Mi.colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val about = r.description.ifBlank { r.schedule }
                if (about.isNotBlank()) Text(about, style = Type.small, color = Mi.colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(12.dp))
            MiToggle(r.enabled, onToggle, label = r.name)
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            val next = r.nextRunAt
            Text(
                if (r.enabled && next != null) "Next: ${TimeText.divider(next)}" else if (r.enabled) "On" else "Off",
                style = Type.caption, color = Mi.colors.textTertiary, modifier = Modifier.weight(1f),
            )
            TextLink("Run now", onRun)
        }
    }
}

/** The files a bot keeps. Folders open; a file is saved to the phone's Downloads. */
@Composable
fun LibraryScreen(botId: String) {
    val hub = LocalHub.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var path by rememberSaveable { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }
    val listing by produceState<FilesResponse?>(null, path) {
        value = null
        value = try { hub.client.files(botId, path) } catch (e: HubException) { FilesResponse(status = "error", error = e.message) }
    }
    BackHandler(enabled = path.isNotEmpty()) { path = path.substringBeforeLast('/', "") }

    fun save(file: LibraryFile) {
        scope.launch {
            note = try {
                val url = hub.client.fileUrl(botId, if (path.isEmpty()) file.name else "$path/${file.name}")
                val request = DownloadManager.Request(Uri.parse(url))
                    .setTitle(file.name)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, file.name)
                // A file that lives on the hub itself needs this phone's sign-in to fetch.
                if (url.startsWith(hub.client.account.url)) request.addRequestHeader("Cookie", hub.client.cookie)
                (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
                "Saving ${file.name} to Downloads."
            } catch (e: HubException) {
                e.message
            } catch (e: RuntimeException) {
                "That file could not be saved."
            }
        }
    }

    Screen(if (path.isEmpty()) "Library" else path.substringAfterLast('/'), { if (path.isEmpty()) nav.back() else path = path.substringBeforeLast('/', "") }) {
        note?.let { Text(it, style = Type.small, color = Mi.colors.textSecondary, modifier = Modifier.padding(horizontal = 22.dp, vertical = 6.dp)) }
        val l = listing
        when {
            l == null -> EmptyNote("Loading…")
            l.status != "ok" -> EmptyNote(l.error ?: "The library cannot be read right now. The bot's computer may be asleep.")
            l.files.isEmpty() -> EmptyNote(if (path.isEmpty()) "Nothing here yet. Files the bot makes show up here." else "This folder is empty.")
            else -> LazyColumn(contentPadding = PaddingValues(vertical = 4.dp)) {
                items(l.files.sortedWith(compareByDescending<LibraryFile> { it.isDir }.thenBy { it.name.lowercase() }), key = { it.name }) { f ->
                    CardRow(
                        f.name, icon = if (f.isDir) MiIcons.folder else MiIcons.file,
                        subtitle = if (f.isDir) null else listOfNotNull(TimeText.size(f.size), f.modifiedAt.takeIf { it > 0 }?.let { TimeText.listTime(it) }).joinToString(" · "),
                        onClick = { if (f.isDir) path = if (path.isEmpty()) f.name else "$path/${f.name}" else save(f) },
                    )
                }
            }
        }
    }
}

/** A look at the bot's screen, refreshed every few seconds while this is open. */
@Composable
fun ComputerScreen(botId: String) {
    val hub = LocalHub.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val state by hub.state.collectAsStateWithLifecycle()
    val computer = state.computer
    var error by remember { mutableStateOf<String?>(null) }
    val running = computer?.status == "running"
    val screen by produceState<ImageBitmap?>(null, botId, running) {
        while (running) {
            runCatching { hub.client.screenshot(botId) }.getOrNull()?.let { bytes ->
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { value = it.asImageBitmap() }
            }
            delay(2_500)
        }
    }

    Screen("Computer", { nav.back() }) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val picture = screen
            val shape = RoundedCornerShape(18.dp)
            if (picture != null) {
                Image(picture, contentDescription = "The bot's screen", contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth().clip(shape).border(1.dp, Mi.colors.border, shape))
            } else {
                Box(Modifier.fillMaxWidth().height(210.dp).clip(shape).background(Mi.colors.surface).border(1.dp, Mi.colors.border, shape), contentAlignment = Alignment.Center) {
                    Text(
                        when {
                            running -> "Waiting for a picture of the screen…"
                            computer?.status == "error" -> "The computer has a problem."
                            else -> "The computer is ${computerWord(computer).lowercase()}."
                        },
                        style = Type.body, color = Mi.colors.textSecondary)
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                when {
                    computer?.status == "error" -> computer.error ?: "The computer has a problem."
                    running -> "This is what the bot sees. It updates every few seconds."
                    else -> "The computer starts by itself when a bot needs it."
                },
                style = Type.small, color = Mi.colors.textSecondary, textAlign = TextAlign.Center,
            )
            error?.let { ErrorNote(it, Modifier.padding(top = 12.dp)) }
            Spacer(Modifier.height(16.dp))
            if (!running) {
                PillButton("Wake it up", {
                    scope.launch { try { hub.client.wakeComputer(); error = null } catch (e: HubException) { error = e.message } }
                }, primary = false, icon = MiIcons.play)
                Spacer(Modifier.height(10.dp))
            }
            PillButton("Take control in the web app", { nav.go(Dest.Web("MiBot", "/")) }, primary = false, icon = MiIcons.pointer)
        }
    }
}
