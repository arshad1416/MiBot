package app.mibot.ui

import android.graphics.Bitmap
import android.graphics.Paint
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import app.mibot.core.Bot
import kotlinx.coroutines.delay
import kotlin.random.Random

/** What the character's eyes are doing. */
enum class Mood {
    /** Drawn once, no movement: for lists. */
    Still,
    /** Looks ahead and blinks now and then. */
    Awake,
    /** Eyes sweep side to side: the bot is on a job. */
    Working,
}

private val VISOR_DARK = Color(0xFF16171A)
private val VISOR_LIGHT = Color(0xFFF2F2F2)

private val paths = HashMap<String, Path>()

/** An SVG path string as something to draw. Parsed once; only touched from the main thread. */
internal fun svgPath(d: String): Path = paths.getOrPut(d) { PathParser().parsePathString(d).toPath() }

/**
 * A bot's character: a body in the bot's colour and a visor with two lit eyes. `ring` draws the
 * outline that marks the chosen one in a picker.
 */
@Composable
fun BotAvatar(shape: String, color: String, size: Dp, modifier: Modifier = Modifier, mood: Mood = Mood.Still, star: Boolean = false, ring: Boolean = false) {
    val def = AVATAR_SHAPES[shape] ?: AVATAR_SHAPES.getValue("blob")
    val fill = botColors(color).fill
    val body = svgPath(def.body)
    // On a dark body the visor is the light part, so the face still reads.
    val visor = if (fill.isDark) VISOR_LIGHT else VISOR_DARK
    val eye = if (fill.isDark) VISOR_DARK else Color.White
    val ringColor = Mi.colors.text

    // 1 = open, 0 = shut.
    val lids = remember { Animatable(1f) }
    if (mood != Mood.Still) {
        LaunchedEffect(Unit) {
            while (true) {
                delay(Random.nextLong(2_200, 5_200))
                lids.animateTo(0.1f, tween(70))
                lids.animateTo(1f, tween(110))
            }
        }
    }
    // -1 = looking left, 1 = looking right.
    val look = if (mood == Mood.Working) {
        val sweep by rememberInfiniteTransition(label = "look").animateFloat(
            initialValue = -1f, targetValue = 1f, label = "look",
            animationSpec = infiniteRepeatable(
                keyframes {
                    durationMillis = 2_400
                    -1f at 0
                    -1f at 500
                    1f at 1_200
                    1f at 1_700
                    -1f at 2_400
                },
                RepeatMode.Restart,
            ),
        )
        sweep
    } else 0f

    Box(modifier.size(size).clearAndSetSemantics { }) {
        Canvas(Modifier.size(size)) {
            val k = this.size.minDimension / 100f
            scale(k, k, pivot = Offset.Zero) {
                if (ring) drawPath(body, ringColor, style = Stroke(width = 5f))
                drawPath(body, fill)
                val s = def.faceSize
                val w = AvatarFace.WIDTH * s
                val h = AvatarFace.HEIGHT * s
                drawRoundRect(visor, Offset(def.faceX - w / 2, def.faceY - h / 2), Size(w, h), CornerRadius(h / 2, h / 2))
                val r = AvatarFace.EYE_RADIUS * s
                val open = lids.value
                for (side in intArrayOf(-1, 1)) {
                    val cx = def.faceX + side * AvatarFace.EYE_GAP * s + look * 3.2f * s
                    drawOval(eye, Offset(cx - r, def.faceY - r * open), Size(2 * r, 2 * r * open))
                }
            }
        }
        if (star) {
            // The mark of the primary bot.
            val badge = size * 0.3f
            Box(
                Modifier.align(Alignment.BottomEnd).size(badge).background(Mi.colors.bg, CircleShape).border(size * 0.012f, Mi.colors.border, CircleShape),
                contentAlignment = Alignment.Center,
            ) { MiIcon(MiIcons.star, badge * 0.56f, Mi.colors.text) }
        }
    }
}

@Composable
fun BotAvatar(bot: Bot, size: Dp, modifier: Modifier = Modifier, mood: Mood = Mood.Still, star: Boolean = false) =
    BotAvatar(bot.avatarShape, bot.avatarColor, size, modifier, mood, star)

/** A bot's character as a still picture `px` wide, for where Compose does not draw: a notification. Main thread only. */
fun avatarBitmap(shape: String, color: String, dark: Boolean, px: Int): Bitmap {
    val def = AVATAR_SHAPES[shape] ?: AVATAR_SHAPES.getValue("blob")
    val fill = botFill(color, dark)
    val bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    canvas.scale(px / 100f, px / 100f)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.color = fill.toArgb()
    canvas.drawPath(svgPath(def.body).asAndroidPath(), paint)
    val s = def.faceSize
    val w = AvatarFace.WIDTH * s
    val h = AvatarFace.HEIGHT * s
    paint.color = (if (fill.isDark) VISOR_LIGHT else VISOR_DARK).toArgb()
    canvas.drawRoundRect(def.faceX - w / 2, def.faceY - h / 2, def.faceX + w / 2, def.faceY + h / 2, h / 2, h / 2, paint)
    paint.color = (if (fill.isDark) VISOR_DARK else Color.White).toArgb()
    for (side in intArrayOf(-1, 1)) canvas.drawCircle(def.faceX + side * AvatarFace.EYE_GAP * s, def.faceY, AvatarFace.EYE_RADIUS * s, paint)
    return bitmap
}

/** A dot that breathes, for "something is happening here". */
@Composable
fun PulseDot(color: Color, size: Dp, modifier: Modifier = Modifier) {
    val alpha by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 0.35f, targetValue = 1f, label = "pulse",
        animationSpec = infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Reverse),
    )
    Box(modifier.size(size).background(color.copy(alpha = alpha), CircleShape))
}
