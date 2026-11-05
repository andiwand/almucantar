package at.stefl.shotradar

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// The look of shotradar, the same as its website and its PWA: a warm paper, serif titles like a
// photo book, the numbers of a camera in a monospace face, cards like prints with the corners of a
// viewfinder, and the steps as a strip of film. The colors come from the logo.

object Shade {
    val paper = Color(0xFFF3ECE1)
    val print = Color(0xFFFFFAF2)
    val ink = Color(0xFF2A1F3D)
    val inkSoft = Color(0xFF5D5170)
    val muted = Color(0xFF7A6D7F)
    val rose = Color(0xFF8A4A55)
    val apricot = Color(0xFFE8955E)
    val cream = Color(0xFFFFF3D6)
    val night = Color(0xFF1D1426)
    val line = Color(0x1F2A1F3D)
    val good = Color(0xFF3F7A55)
}

val Serif = FontFamily.Serif
val Mono = FontFamily.Monospace

@Composable
fun ShotTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme =
            lightColorScheme(
                primary = Shade.ink,
                onPrimary = Shade.cream,
                secondary = Shade.rose,
                background = Shade.paper,
                surface = Shade.print,
                onSurface = Shade.ink,
                onBackground = Shade.ink,
            ),
        content = content,
    )
}

/** The head of a card: the number of the frame and the title, like the edge of a film. */
@Composable
fun Step(number: String, label: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            number,
            color = Shade.cream,
            fontFamily = Mono,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.background(Shade.rose).padding(horizontal = 5.dp, vertical = 2.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            label.uppercase(),
            color = Shade.rose,
            fontFamily = Mono,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.6.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun Title(text: String, modifier: Modifier = Modifier, size: Int = 26) {
    Text(
        text,
        modifier,
        fontFamily = Serif,
        fontSize = size.sp,
        lineHeight = (size * 1.18).sp,
        color = Shade.ink,
    )
}

@Composable
fun Muted(text: String, modifier: Modifier = Modifier, small: Boolean = false) {
    Text(
        text,
        modifier,
        color = Shade.muted,
        fontSize = if (small) 13.sp else 15.sp,
        lineHeight = if (small) 18.sp else 21.sp,
    )
}

/** The corners of a viewfinder around the content. */
fun Modifier.finderCorners(
    color: Color = Shade.ink.copy(alpha = 0.55f),
    arm: Dp = 12.dp,
    inset: Dp = 6.dp,
) = drawBehind {
    val a = arm.toPx()
    val i = inset.toPx()
    val w = 1.5.dp.toPx()
    val corners =
        listOf(
            Offset(i, i) to Offset(1f, 1f),
            Offset(size.width - i, i) to Offset(-1f, 1f),
            Offset(i, size.height - i) to Offset(1f, -1f),
            Offset(size.width - i, size.height - i) to Offset(-1f, -1f),
        )
    for ((p, d) in corners) {
        drawLine(color, p, Offset(p.x + d.x * a, p.y), w)
        drawLine(color, p, Offset(p.x, p.y + d.y * a), w)
    }
}

/** A card like a print: the light paper, a thin edge, and the corners of a viewfinder. */
@Composable
fun Print(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    padding: Dp = 18.dp,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .background(Shade.print, RoundedCornerShape(2.dp))
            .border(BorderStroke(1.dp, Shade.line), RoundedCornerShape(2.dp))
            .finderCorners()
            .let { if (onClick != null) it.clickable(enabled = enabled, onClick = onClick) else it }
            .alpha(if (enabled) 1f else 0.5f)
            .padding(padding)
    ) {
        content()
    }
}

/** A button of the app: the dark ink for the main action, else the paper. */
@Composable
fun ShotButton(
    text: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val background = if (primary) Shade.ink else Shade.print
    val color = if (primary) Shade.cream else Shade.ink
    Box(
        modifier
            .alpha(if (enabled) 1f else 0.4f)
            .background(background, RoundedCornerShape(2.dp))
            .border(
                1.dp,
                if (primary) Shade.ink else Shade.ink.copy(alpha = 0.25f),
                RoundedCornerShape(2.dp),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text.uppercase(),
            color = color,
            fontFamily = Mono,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.2.sp,
            maxLines = 1,
        )
    }
}

/** A chip of a choice: a lens, a subject in a filter. */
@Composable
fun Chip(text: String, active: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .background(if (active) Shade.ink else Shade.print, RoundedCornerShape(2.dp))
            .border(1.dp, if (active) Shade.ink else Shade.line, RoundedCornerShape(2.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (active) Shade.cream else Shade.ink,
            fontFamily = Mono,
            fontSize = 12.sp,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** The rows of data under a photo, like the EXIF data of a camera. */
@Composable
fun Exif(rows: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        for ((k, v) in rows) {
            Row(
                Modifier.fillMaxWidth()
                    .drawBehind {
                        drawLine(
                            Shade.line,
                            Offset(0f, size.height),
                            Offset(size.width, size.height),
                            1.dp.toPx(),
                        )
                    }
                    .padding(vertical = 7.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    k.uppercase(),
                    Modifier.width(118.dp),
                    color = Shade.muted,
                    fontFamily = Mono,
                    fontSize = 11.sp,
                    letterSpacing = 0.8.sp,
                )
                Text(v, color = Shade.ink, fontFamily = Mono, fontSize = 13.sp)
            }
        }
    }
}

/** A line with a small spinner: the work in progress, like a print in the developer. */
@Composable
fun Developing(text: String, modifier: Modifier = Modifier) {
    Row(modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(16.dp), color = Shade.rose, strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Muted(text)
    }
}

@Composable
fun ButtonRow(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

val Section = TextStyle(fontFamily = Serif, fontSize = 20.sp, color = Shade.ink)

@Composable fun Gap(height: Int = 12) = Spacer(Modifier.height(height.dp))

/** The slider of the time: the rose thumb on a track of the ink. */
@Composable
fun sliderColors() =
    androidx.compose.material3.SliderDefaults.colors(
        thumbColor = Shade.rose,
        activeTrackColor = Shade.rose,
        inactiveTrackColor = Shade.ink.copy(alpha = 0.15f),
        activeTickColor = Color.Transparent,
        inactiveTickColor = Color.Transparent,
    )
