package dev.midplane.zap

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What a sheet holds, legible at a glance from its stub. */
enum class SheetKind(val label: String) { TEXT("Text"), LINK("Link"), CODE("Code"), IMAGE("Image"), COLOR("Color") }

private val COMMAND = Regex("""^(\$ |sudo |git |npm |npx |yarn |pnpm |brew |cd |ls |cat |curl |docker |kubectl |python3? |pip3? |make |gradle|\./|~/|/[\w.-]+/)""")
private val HEX_COLOR = Regex("^#?([0-9a-fA-F]{6})$")

val Clip.sheetKind: SheetKind
    get() {
        if (kind == "image") return SheetKind.IMAGE
        val value = text.trim()
        return when {
            link != null -> SheetKind.LINK
            HEX_COLOR.matches(value) -> SheetKind.COLOR
            '\n' !in value && value.length < 300 && COMMAND.containsMatchIn(value) -> SheetKind.CODE
            else -> SheetKind.TEXT
        }
    }

/** A stable four-digit serial, printed on the stub like a duplicate book's page number. */
val Clip.serial: String
    get() = (id.filter(Char::isLetterOrDigit).take(8).toLongOrNull(16)?.rem(10_000) ?: 0L).toString().padStart(4, '0')

private val Clip.swatch: Color?
    get() = HEX_COLOR.find(text.trim())?.groupValues?.get(1)?.toLongOrNull(16)?.let { Color(0xFF000000 or it) }

@Composable
fun stubColor(kind: SheetKind): Color {
    val sheet = LocalSheetColors.current
    return when (kind) {
        SheetKind.TEXT -> sheet.textStub
        SheetKind.LINK -> sheet.linkStub
        SheetKind.CODE -> sheet.codeStub
        SheetKind.IMAGE -> sheet.imageStub
        SheetKind.COLOR -> sheet.colorStub
    }
}

val SerialStyle = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 0.5.sp)
private val StubLabelStyle = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 10.sp, letterSpacing = 1.2.sp)

/** A horizontal row of perforation holes, the tear line between sheets. */
fun Modifier.perforation(color: Color, top: Boolean = false) = drawBehind {
    val y = if (top) 0f else size.height
    drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 4.dp.toPx())))
}

/**
 * One sheet of the book: a tinted stub carrying serial, kind and time, a perforation, then the content.
 * Tap copies and lands a stamp; long-press or the trailing button opens the preview.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SheetRow(
    clip: Clip,
    time: String,
    stamp: String?,
    onStampShown: () -> Unit,
    onCopy: () -> Unit,
    onPreview: () -> Unit,
    image: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier
) {
    val kind = clip.sheetKind
    val sheet = LocalSheetColors.current
    val reducedMotion = LocalReducedMotion.current
    val view = LocalView.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // The sheet gives slightly under the finger, like paper on a pad.
    val press by animateFloatAsStateCompat(if (pressed && !reducedMotion) 0.985f else 1f)
    val impression = remember { Animatable(0f) }

    LaunchedEffect(stamp) {
        if (stamp == null) return@LaunchedEffect
        if (!reducedMotion) {
            impression.snapTo(1f)
            impression.animateTo(0f, tween(650))
        }
    }

    Box(
        modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = press; scaleY = press }
            .perforation(sheet.ruling)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClickLabel = if (kind == SheetKind.IMAGE) "Copy image" else "Copy text",
                onLongClickLabel = "Preview",
                onLongClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    onPreview()
                },
                onClick = {
                    view.performHapticFeedback(
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.KEYBOARD_TAP
                    )
                    onCopy()
                }
            )
    ) {
        Row(Modifier.fillMaxWidth()) {
            Box(Modifier.weight(1f)) {
                Row(Modifier.padding(start = 20.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        SheetContent(clip, kind, image)
                        SheetMeta(clip, kind, time)
                    }
                    IconButton(onClick = onPreview) { Icon(Icons.Outlined.OpenInFull, "Preview", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                // The impression: carbon ink blooms where the sheet was pressed, then fades into the paper.
                Box(Modifier.matchParentSize().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f * impression.value)))
                stamp?.let { Stamp(it, Modifier.align(Alignment.CenterEnd).padding(end = 56.dp), onStampShown) }
            }
        }
    }
}

@Composable
private fun animateFloatAsStateCompat(target: Float): State<Float> =
    androidx.compose.animation.core.animateFloatAsState(target, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessHigh), label = "press")

/** One line under the content: kind tag in its copy tint, serial in red, then source and time. */
@Composable
private fun SheetMeta(clip: Clip, kind: SheetKind, time: String) {
    val sheet = LocalSheetColors.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.background(stubColor(kind), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            clip.swatch?.let { Box(Modifier.size(8.dp).clip(CircleShape).background(it).border(0.5.dp, sheet.onStub.copy(alpha = 0.4f), CircleShape)) }
            if (clip.pinned) Icon(Icons.Filled.PushPin, "Pinned", Modifier.size(10.dp), tint = sheet.serial)
            Text(kind.label.uppercase(), style = StubLabelStyle, color = sheet.onStub)
        }
        Text("№${clip.serial}", style = SerialStyle, color = sheet.serial, modifier = Modifier.clearAndSetSemantics { })
        Text(
            "${clip.source} · $time",
            style = MaterialTheme.typography.labelMedium,
            color = muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SheetContent(clip: Clip, kind: SheetKind, image: @Composable (Modifier) -> Unit) {
    val text = clip.text.trim()
    when (kind) {
        SheetKind.IMAGE -> image(Modifier.height(88.dp).widthIn(max = 220.dp).clip(MaterialTheme.shapes.small))
        SheetKind.LINK -> {
            val uri = android.net.Uri.parse(text)
            Text(uri.host?.removePrefix("www.") ?: text, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val rest = (uri.encodedPath.orEmpty() + (uri.encodedQuery?.let { "?$it" } ?: "")).takeIf { it.length > 1 }
            rest?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        SheetKind.CODE -> Text(
            text,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
        else -> Text(text, style = MaterialTheme.typography.bodyLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

/** A rubber stamp: lands slightly too big, settles with an overshoot, holds, then lifts away. */
@Composable
private fun Stamp(text: String, modifier: Modifier, onShown: () -> Unit) {
    val sheet = LocalSheetColors.current
    val reducedMotion = LocalReducedMotion.current
    val scale = remember(text) { Animatable(if (reducedMotion) 1f else 1.6f) }
    val alpha = remember(text) { Animatable(if (reducedMotion) 1f else 0f) }
    val currentOnShown by rememberUpdatedState(onShown)
    LaunchedEffect(text) {
        if (!reducedMotion) {
            launch { alpha.animateTo(1f, tween(90)) }
            scale.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 900f))
        }
        delay(900)
        if (!reducedMotion) alpha.animateTo(0f, tween(260)) else alpha.snapTo(0f)
        currentOnShown()
    }
    Text(
        text,
        modifier
            .graphicsLayer { scaleX = scale.value; scaleY = scale.value; rotationZ = -8f; this.alpha = alpha.value }
            .border(2.dp, sheet.serial, RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clearAndSetSemantics { },
        style = TextStyle(fontWeight = FontWeight.Black, fontSize = 15.sp, letterSpacing = 2.sp),
        color = sheet.serial
    )
}

/** A ruled page heading: tracked small caps, a rule to the margin, and the sheet count. */
@Composable
fun PageHeading(text: String, count: Int) = RuledHeading(text, count, Modifier.padding(horizontal = 20.dp))

/** The same ruled heading for settings sections, which supply their own margins. */
@Composable
fun SectionHeading(text: String, count: Int? = null) = RuledHeading(text, count, Modifier.padding(horizontal = 16.dp))

@Composable
private fun RuledHeading(text: String, count: Int?, margins: Modifier) {
    val sheet = LocalSheetColors.current
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).then(margins).padding(top = 20.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp),
            color = MaterialTheme.colorScheme.primary
        )
        Box(Modifier.weight(1f).height(1.dp).background(sheet.ruling))
        count?.let { Text(it.toString().padStart(2, '0'), style = SerialStyle, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** Ruled writing paper behind text, one rule per line. */
@Composable
fun Modifier.ruled(lineHeight: androidx.compose.ui.unit.TextUnit): Modifier {
    val color = LocalSheetColors.current.ruling
    val density = LocalDensity.current
    val step = with(density) { lineHeight.toPx() }
    return drawBehind {
        var y = step - 2.dp.toPx()
        while (y < size.height) {
            drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
            y += step
        }
    }
}
