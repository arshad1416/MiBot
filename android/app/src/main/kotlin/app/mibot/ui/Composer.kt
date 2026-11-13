package app.mibot.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.mibot.core.Bot
import app.mibot.core.HubException
import app.mibot.core.MessageBody
import app.mibot.core.Row as ChatRow
import app.mibot.core.VoiceState
import app.mibot.voice.Dictation
import app.mibot.voice.hasMicrophone
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What the microphone button is doing. */
private enum class Dictating { Off, Recording, Working }

/**
 * The box at the bottom of a chat: type a message, dictate one with the microphone, or (when the
 * box is empty) start a voice call with the bot. `replying`: the message the next one answers, shown above the box.
 */
@Composable
fun Composer(
    bot: Bot, colors: BotColors, replying: ChatRow?, onCancelReply: () -> Unit,
    onSend: (String) -> Unit, onCall: () -> Unit, onError: (String?) -> Unit,
) {
    val context = LocalContext.current
    val hub = LocalHub.current
    val scope = rememberCoroutineScope()
    var text by rememberSaveable(bot.id) { mutableStateOf("") }
    var dictating by remember { mutableStateOf(Dictating.Off) }
    val dictation = remember { Dictation(context) }
    DisposableEffect(Unit) { onDispose { dictation.cancel() } }

    fun startDictation() {
        try {
            dictation.start()
            dictating = Dictating.Recording
            onError(null)
        } catch (e: IllegalStateException) {
            onError(e.message)
        }
    }
    fun finishDictation() {
        val wav = dictation.finish()
        if (wav == null) { dictating = Dictating.Off; return }
        dictating = Dictating.Working
        scope.launch {
            try {
                val heard = hub.client.transcribe(wav, "dictation.wav", "audio/wav", bot.voiceLanguage).trim()
                if (heard.isNotEmpty()) text = if (text.isBlank()) heard else text.trimEnd() + " " + heard
            } catch (e: HubException) {
                onError(e.message)
            } finally {
                dictating = Dictating.Off
            }
        }
    }

    // The microphone is asked for the first time it is needed, and what was pressed then goes ahead.
    var wanted by remember { mutableStateOf<(() -> Unit)?>(null) }
    val askMicrophone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) wanted?.invoke() else onError("MiBot needs the microphone for that. You can allow it in the phone's settings.")
        wanted = null
    }
    fun withMicrophone(action: () -> Unit) {
        if (hasMicrophone(context)) action() else { wanted = action; askMicrophone.launch(Manifest.permission.RECORD_AUDIO) }
    }

    // Choosing Reply on a message puts the cursor in the box.
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(replying?.seq) {
        if (replying != null && dictating == Dictating.Off) runCatching { focus.requestFocus(); keyboard?.show() }
    }

    val shape = RoundedCornerShape(28.dp)
    Column(Modifier.fillMaxWidth()) {
        (replying?.body as? MessageBody)?.let { quoted ->
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 6.dp).clip(RoundedCornerShape(16.dp)).background(Mi.colors.surface)
                    .padding(start = 14.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MiIcon(MiIcons.reply, 16.dp, Mi.colors.textSecondary)
                Column(Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 8.dp)) {
                    val who = quoted.from?.takeIf { it.kind != "voice" }?.name ?: if (quoted.isUser) "yourself" else bot.name
                    Text("Replying to $who", style = Type.caption, fontWeight = FontWeight.SemiBold, color = Mi.colors.text, maxLines = 1)
                    Text(quoted.text, style = Type.small, color = Mi.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                CircleButton(MiIcons.close, "Cancel reply", onCancelReply, size = 36.dp, fill = Color.Transparent, tint = Mi.colors.textSecondary)
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 10.dp), verticalAlignment = Alignment.Bottom) {
            Row(
                Modifier.weight(1f).clip(shape).background(Mi.colors.surface).border(1.dp, Mi.colors.border, shape).heightIn(min = 52.dp).padding(start = 18.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (dictating == Dictating.Off) {
                    BasicTextField(
                        value = text, onValueChange = { text = it }, modifier = Modifier.weight(1f).focusRequester(focus).padding(vertical = 14.dp), maxLines = 6,
                        textStyle = Type.body.copy(color = Mi.colors.text), cursorBrush = SolidColor(Mi.colors.text),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        decorationBox = { inner ->
                            Box {
                                if (text.isEmpty()) Text("Ask ${bot.name}", style = Type.body, color = Mi.colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                inner()
                            }
                        },
                    )
                    CircleButton(MiIcons.mic, "Dictate", { withMicrophone(::startDictation) }, size = 40.dp, fill = Color.Transparent, tint = Mi.colors.textSecondary)
                } else {
                    Text(if (dictating == Dictating.Recording) "Listening…" else "Writing it down…", style = Type.body, color = Mi.colors.textSecondary)
                    Spacer(Modifier.width(12.dp))
                    LevelBars({ dictation.level }, Mi.colors.text, Modifier.weight(1f).height(24.dp))
                    if (dictating == Dictating.Recording) {
                        CircleButton(MiIcons.close, "Cancel dictation", { dictation.cancel(); dictating = Dictating.Off }, size = 40.dp, fill = Color.Transparent, tint = Mi.colors.textSecondary)
                        CircleButton(MiIcons.check, "Use what I said", ::finishDictation, size = 40.dp, fill = Color.Transparent)
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            val hasText = text.isNotBlank()
            if (hasText) {
                CircleButton(MiIcons.arrowUp, "Send", { onSend(text.trim()); text = "" }, size = 52.dp, fill = colors.action, tint = colors.onAction)
            } else {
                CircleButton(MiIcons.voiceA, "Talk to ${bot.name}", { withMicrophone(onCall) }, size = 52.dp, fill = colors.action, tint = colors.onAction, enabled = dictating == Dictating.Off)
            }
        }
    }
}

/** A row of bars that move with a sound level (0 to 1), read many times a second. */
@Composable
fun LevelBars(level: () -> Float, color: Color, modifier: Modifier = Modifier, bars: Int = 18) {
    val history = remember { FloatArray(bars) }
    var frame by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            System.arraycopy(history, 1, history, 0, bars - 1)
            history[bars - 1] = level()
            frame += 1f
            delay(70)
        }
    }
    Canvas(modifier) {
        frame // read, so each new level redraws
        val gap = size.width / bars
        val w = (gap * 0.45f).coerceAtMost(4.dp.toPx())
        for (i in 0 until bars) {
            val h = (size.height * (0.12f + 0.88f * history[i])).coerceAtLeast(w)
            drawRoundRect(color.copy(alpha = 0.35f + 0.65f * history[i]), Offset(i * gap + (gap - w) / 2, (size.height - h) / 2), Size(w, h), CornerRadius(w / 2, w / 2))
        }
    }
}

/** A voice call in progress: who is speaking, what was just said, mute and hang up. It takes the composer's place. */
@Composable
fun VoiceBar(bot: Bot, voice: VoiceState, colors: BotColors) {
    val app = LocalApp.current
    val shape = RoundedCornerShape(28.dp)
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 10.dp)) {
        if (voice.status == VoiceState.Status.Error) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ErrorNote(voice.error ?: "The call ended.", Modifier.weight(1f))
                TextLink("OK", { app.voice.end() })
            }
            return@Column
        }
        // The last thing said, by either side.
        voice.captions.lastOrNull { it.text.isNotBlank() }?.let { line ->
            Text(
                line.text.trim(), style = Type.small, maxLines = 3, overflow = TextOverflow.Ellipsis,
                color = if (line.role == "user") Mi.colors.textSecondary else Mi.colors.text,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
        Row(
            Modifier.fillMaxWidth().clip(shape).background(Mi.colors.surface).border(1.dp, Mi.colors.border, shape).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BotAvatar(bot, 40.dp, mood = if (voice.speaking || voice.thinking) Mood.Working else Mood.Awake)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                val status = when {
                    voice.status == VoiceState.Status.Connecting -> "Calling ${bot.name}…"
                    voice.muted -> "You are muted"
                    voice.speaking -> "${bot.name} is speaking"
                    voice.working.isNotEmpty() -> "Working on it"
                    voice.thinking -> "Thinking"
                    else -> "Listening"
                }
                Text(status, style = Type.caption, color = Mi.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                LevelBars({ if (voice.speaking) app.voice.outLevel else if (voice.muted) 0f else app.voice.micLevel }, if (voice.speaking) colors.fill else Mi.colors.text, Modifier.fillMaxWidth().height(22.dp))
            }
            Spacer(Modifier.width(8.dp))
            CircleButton(if (voice.muted) MiIcons.micOff else MiIcons.mic, if (voice.muted) "Unmute" else "Mute", { app.voice.setMuted(!voice.muted) }, size = 44.dp)
            Spacer(Modifier.width(8.dp))
            CircleButton(MiIcons.close, "End the call", { app.voice.end() }, size = 44.dp, fill = Mi.colors.danger, tint = Color.White)
        }
    }
}
