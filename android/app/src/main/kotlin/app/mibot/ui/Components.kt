package app.mibot.ui

import android.content.ClipData
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.LruCache
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

// ---------- icons ----------

/** One of MiBot's own icons (see scripts/gen-icons.mjs), in the colour of the text around it unless told otherwise. */
@Composable
fun MiIcon(icon: IconDef, size: Dp = 22.dp, tint: Color = LocalContentColor.current, modifier: Modifier = Modifier) {
    val fill = icon.fill?.let { svgPath(it) }
    val stroke = icon.stroke?.let { svgPath(it) }
    Canvas(modifier.size(size)) {
        val k = this.size.minDimension / 24f
        scale(k, k, pivot = Offset.Zero) {
            if (fill != null) drawPath(fill, tint)
            if (stroke != null) drawPath(stroke, tint, style = Stroke(width = 1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

// ---------- buttons ----------

/** The round button of the top bars: a thin ring, or filled when it is the main thing to press. */
@Composable
fun CircleButton(
    icon: IconDef,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 46.dp,
    fill: Color? = null,
    tint: Color = Mi.colors.text,
    enabled: Boolean = true,
) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .then(if (fill != null) Modifier.background(fill) else Modifier.border(1.dp, Mi.colors.border, CircleShape))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { MiIcon(icon, size * 0.46f, if (enabled) tint else tint.copy(alpha = 0.4f)) }
}

/** A wide button with words on it. `primary` is the filled one; otherwise it is an outline. */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = true,
    enabled: Boolean = true,
    color: Color = Mi.colors.primary,
    onColor: Color = Mi.colors.onPrimary,
    icon: IconDef? = null,
    small: Boolean = false,
) {
    val shape = RoundedCornerShape(50)
    val ink = if (primary) onColor else Mi.colors.text
    Row(
        modifier
            .heightIn(min = if (small) 36.dp else 48.dp)
            .clip(shape)
            .then(if (primary) Modifier.background(color.copy(alpha = if (enabled) 1f else 0.4f)) else Modifier.border(1.dp, Mi.colors.border, shape))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = if (small) 14.dp else 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            MiIcon(icon, if (small) 16.dp else 19.dp, ink)
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = if (small) Type.small else Type.body, fontWeight = FontWeight.SemiBold, color = ink.copy(alpha = if (enabled || primary) 1f else 0.5f), maxLines = 1)
    }
}

/** A word to press, with no shape around it. */
@Composable
fun TextLink(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = Mi.colors.textSecondary) {
    Text(
        text, style = Type.small, fontWeight = FontWeight.Medium, color = color,
        modifier = modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 8.dp, vertical = 8.dp),
    )
}

/** On or off. The knob is dark on a light track when on, as in the rest of the app's look. */
@Composable
fun MiToggle(checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, label: String = "") {
    val x by animateDpAsState(if (checked) 22.dp else 2.dp, label = "knob")
    val on by animateFloatAsState(if (checked) 1f else 0f, label = "track")
    val track = lerpColor(Mi.colors.toggleOff, Mi.colors.primary, on)
    Box(
        modifier
            .size(52.dp, 32.dp)
            .clip(CircleShape)
            .background(track.copy(alpha = if (enabled) 1f else 0.5f))
            .clickable(enabled = enabled, role = Role.Switch, onClick = { onChange(!checked) })
            .semantics { if (label.isNotEmpty()) contentDescription = label },
    ) {
        Box(Modifier.offset(x = x, y = 2.dp).size(28.dp).background(if (checked) Mi.colors.onPrimary else Color.White, CircleShape))
    }
}

private fun lerpColor(a: Color, b: Color, t: Float) = androidx.compose.ui.graphics.lerp(a, b, t)

// ---------- cards and rows ----------

/** The rounded panel that holds a group of rows or a card in the conversation. */
@Composable
fun MiCard(modifier: Modifier = Modifier, padding: PaddingValues = PaddingValues(0.dp), content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(26.dp)
    Column(modifier.fillMaxWidth().clip(shape).background(Mi.colors.surface).border(1.dp, Mi.colors.border, shape).padding(padding), content = content)
}

/** One line of a card: an icon, what it is, and on the right its value, a toggle, or an arrow. */
@Composable
fun CardRow(
    title: String,
    modifier: Modifier = Modifier,
    icon: IconDef? = null,
    subtitle: String? = null,
    value: String? = null,
    onClick: (() -> Unit)? = null,
    danger: Boolean = false,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).heightIn(min = 60.dp).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val ink = if (danger) Mi.colors.danger else Mi.colors.text
        if (icon != null) {
            MiIcon(icon, 22.dp, ink)
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.body, color = ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrEmpty()) Text(subtitle, style = Type.small, color = Mi.colors.textSecondary)
        }
        if (value != null) {
            Spacer(Modifier.width(12.dp))
            Text(value, style = Type.body, color = Mi.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        } else if (onClick != null && !danger) {
            Spacer(Modifier.width(8.dp))
            MiIcon(MiIcons.chevRight, 16.dp, Mi.colors.textTertiary)
        }
    }
}

@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(horizontal = 20.dp).height(1.dp).background(Mi.colors.border))
}

// ---------- text entry ----------

/** A field to type in, drawn as a rounded panel. */
@Composable
fun MiTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else 8,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    textStyle: TextStyle = Type.body,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val shape = RoundedCornerShape(if (singleLine) 50 else 12)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        textStyle = textStyle.copy(color = Mi.colors.text),
        cursorBrush = SolidColor(Mi.colors.text),
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        decorationBox = { inner ->
            Row(
                Modifier.clip(shape).background(Mi.colors.surface).border(1.dp, Mi.colors.border, shape).heightIn(min = 52.dp).padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top,
            ) {
                if (leading != null) {
                    leading()
                    Spacer(Modifier.width(10.dp))
                }
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Text(placeholder, style = textStyle, color = Mi.colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    inner()
                }
                if (trailing != null) {
                    Spacer(Modifier.width(10.dp))
                    trailing()
                }
            }
        },
    )
}

/** The small heading over a field or a group. */
@Composable
fun FieldLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = Type.small, fontWeight = FontWeight.Medium, color = Mi.colors.textSecondary, modifier = modifier.padding(start = 6.dp, bottom = 8.dp))
}

// ---------- screens ----------

/** A screen under the bot list: a back button, a title in the middle, room for one button on the right, then the content. */
@Composable
fun Screen(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxSize().background(Mi.colors.bg).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
            CircleButton(MiIcons.chevLeft, "Back", onBack, Modifier.align(Alignment.CenterStart))
            Text(
                title, style = Type.heading, color = Mi.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 60.dp),
            )
            if (action != null) Box(Modifier.align(Alignment.CenterEnd)) { action() }
        }
        content()
    }
}

/** A line of words in the middle of an otherwise empty screen. */
@Composable
fun EmptyNote(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, style = Type.body, color = Mi.colors.textSecondary, textAlign = TextAlign.Center)
    }
}

/** Something went wrong, said in a strip. */
@Composable
fun ErrorNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text, style = Type.small, color = Mi.colors.danger,
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Mi.colors.danger.copy(alpha = 0.12f)).padding(horizontal = 14.dp, vertical = 10.dp),
    )
}

// ---------- menus ----------

class MenuItem(val label: String, val icon: IconDef? = null, val danger: Boolean = false, val action: () -> Unit)

/** The list that drops from a "more" button. Put it inside the same Box as the button. */
@Composable
fun MiMenu(open: Boolean, onDismiss: () -> Unit, items: List<MenuItem>) {
    DropdownMenu(expanded = open, onDismissRequest = onDismiss, shape = RoundedCornerShape(18.dp), containerColor = Mi.colors.surfaceHigh) {
        for (item in items) {
            val ink = if (item.danger) Mi.colors.danger else Mi.colors.text
            DropdownMenuItem(
                text = { Text(item.label, style = Type.body, color = ink) },
                leadingIcon = item.icon?.let { { MiIcon(it, 20.dp, ink) } },
                onClick = { onDismiss(); item.action() },
            )
        }
    }
}

/** A round "more" button that opens a menu. */
@Composable
fun MoreButton(items: List<MenuItem>, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        CircleButton(MiIcons.more, "More", { open = true })
        MiMenu(open, { open = false }, items)
    }
}

// ---------- small helpers ----------

/** Puts text on the phone's clipboard. */
@Composable
fun rememberCopy(): (String) -> Unit {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    return { text -> scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("MiBot", text))) } }
}

/** A number that grows by one every `periodMs`, to redraw things that depend on the clock. */
@Composable
fun rememberTicker(periodMs: Long): Int {
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(periodMs) {
        while (true) {
            delay(periodMs)
            tick++
        }
    }
    return tick
}

// ---------- pictures ----------

private val pictures = LruCache<String, ImageBitmap>(60)
private val pictureHttp by lazy { OkHttpClient() }

private fun decode(bytes: ByteArray): ImageBitmap? = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }.getOrNull()

/** A picture from a web address or a `data:` URL, or null while it loads or when it cannot be shown (an SVG, a dead link). */
@Composable
fun rememberPicture(url: String?, cookie: String? = null): ImageBitmap? {
    val picture by produceState(initialValue = url?.let { pictures.get(it) }, url) {
        if (url.isNullOrEmpty() || value != null) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching {
                if (url.startsWith("data:")) decode(Base64.decode(url.substringAfter("base64,", ""), Base64.DEFAULT))
                else {
                    val req = Request.Builder().url(url).apply { if (cookie != null) header("Cookie", cookie) }.build()
                    pictureHttp.newCall(req).execute().use { res -> if (res.isSuccessful) res.body?.bytes()?.let(::decode) else null }
                }
            }.getOrNull()
        }?.also { pictures.put(url, it) }
    }
    return picture
}

/** An app's logo, or its first letter on a tile when there is no logo to show. */
@Composable
fun AppLogo(name: String, logo: String?, size: Dp, modifier: Modifier = Modifier) {
    val picture = rememberPicture(logo)
    val shape = RoundedCornerShape(size * 0.24f)
    if (picture != null) {
        Image(picture, contentDescription = null, contentScale = ContentScale.Fit, modifier = modifier.size(size).clip(shape).background(Color.White).padding(size * 0.12f))
    } else {
        Box(modifier.size(size).clip(shape).background(Mi.colors.surfaceHigh), contentAlignment = Alignment.Center) {
            Text(name.take(1).uppercase(), style = Type.bodyStrong, color = Mi.colors.text)
        }
    }
}

/** "Are you sure?", for the few things that cannot be taken back. */
@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit, danger: Boolean = false) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Mi.colors.surfaceHigh,
        title = { Text(title, style = Type.heading, color = Mi.colors.text) },
        text = { Text(text, style = Type.body, color = Mi.colors.textSecondary) },
        confirmButton = { TextLink(confirm, { onDismiss(); onConfirm() }, color = if (danger) Mi.colors.danger else Mi.colors.text) },
        dismissButton = { TextLink("Cancel", onDismiss) },
    )
}
