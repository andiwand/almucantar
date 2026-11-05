package at.stefl.sonnseite

import android.graphics.Paint
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import at.stefl.almucantar.shared.labelPaint

// The look of sonnseite: the bright look of the day of the website and the web app
// (`web/sonnseite/app/src/style.css`). The panels are warm white with dark text, and the main
// button is sun yellow. The shared code keeps its own look, because sternstunde and alidade use it.

object Look {
    /**
     * The background of the panels and the buttons. The web app uses an alpha of 0.9 with a blur
     * behind it. Here the paper is opaque, because Android draws the shadow below the shape, and
     * the shadow shows through a paper that is not opaque.
     */
    val paper = Color(0xFFFFFCF5)
    val paperBorder = Color(255, 255, 255, (0.7f * 255).toInt())
    val text = Color(0xFF1F2633)
    val secondary = Color(0xFF485264)
    /** The big time of the readout. */
    val time = Color(0xFFB45F06)
    val accent = Color(0xFFF0A91C)
    val primaryTop = Color(0xFFFFD45E)
    val primaryBottom = Color(0xFFF0A91C)
    val onPrimary = Color(0xFF2B1D00)
    val shadow = Color(20, 50, 100)
    val good = Color(0xFF2E7D32)
    val fair = Color(0xFF9A6700)
    val poor = Color(0xFFC2410C)

    /** The labels and the crosshair on a bright sky view. */
    val ink = Color(0xFF1F2633)
    /** The halo of [ink], so the labels are readable on the path and on the ground. */
    val inkHalo = Color(255, 255, 255, (0.85f * 255).toInt())
    /** The vertical line through the crosshair and its ring, on a bright sky view. */
    val helper = Color(0xFF1F4F8F)
}

/** The Material colors of the dialogs, the menus, and the switches. */
private val colorScheme =
    lightColorScheme(
        primary = Look.time,
        onPrimary = Color.White,
        secondaryContainer = Color(0xFFF6E7C8),
        onSecondaryContainer = Look.text,
        background = Color(0xFFFFFCF5),
        onBackground = Look.text,
        surface = Color(0xFFFFFCF5),
        onSurface = Look.text,
        onSurfaceVariant = Look.secondary,
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFFFFCF5),
        surfaceContainer = Color(0xFFFFFCF5),
        surfaceContainerHigh = Color(0xFFFFFAF0),
        surfaceContainerHighest = Color(0xFFF7F0E2),
        outline = Color(0xFF8A8F99),
        outlineVariant = Color(0xFFE2DCCF),
    )

@Composable
fun SonnseiteTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colorScheme, content = content)
}

/** The sliders of the time panel, in the accent of the website. */
@Composable
fun sliderColors() =
    SliderDefaults.colors(
        thumbColor = Look.accent,
        activeTrackColor = Look.accent,
        inactiveTrackColor = Look.accent.copy(alpha = 0.25f),
    )

private val panelShape = RoundedCornerShape(16.dp)

/** A panel: warm white with a soft shadow, as the cards of the website. */
@Composable
fun Panel(content: @Composable () -> Unit) {
    Box(
        Modifier.shadow(8.dp, panelShape, ambientColor = Look.shadow, spotColor = Look.shadow)
            .background(Look.paper, panelShape)
            .border(1.dp, Look.paperBorder, panelShape)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        content()
    }
}

/**
 * A pill button. The [primary] button has the sun yellow gradient, the other buttons are warm
 * white.
 */
@Composable
fun PillButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit,
) {
    val background =
        if (primary) {
            Modifier.shadow(6.dp, CircleShape, ambientColor = Look.accent, spotColor = Look.accent)
                .background(
                    Brush.verticalGradient(listOf(Look.primaryTop, Look.primaryBottom)),
                    CircleShape,
                )
        } else {
            Modifier.shadow(5.dp, CircleShape, ambientColor = Look.shadow, spotColor = Look.shadow)
                .background(Look.paper, CircleShape)
        }
    Button(
        onClick = onClick,
        modifier = modifier.then(background),
        shape = CircleShape,
        colors =
            ButtonDefaults.buttonColors(
                containerColor = Color.Transparent,
                contentColor = if (primary) Look.onPrimary else Look.text,
            ),
        border = if (primary) null else BorderStroke(1.dp, Look.paperBorder),
        contentPadding = contentPadding,
        content = content,
    )
}

private val inkPaints = mutableMapOf<Pair<Float, Boolean>, Paint>()

/**
 * A paint for the labels of the sky view. On a bright sky view the text is dark with a light halo;
 * else it is the white label of the shared code. The paints are kept, because each frame needs
 * them; do not change one.
 */
fun skyLabelPaint(sizePx: Float, bold: Boolean, bright: Boolean): Paint =
    if (!bright) {
        labelPaint(sizePx, bold)
    } else {
        inkPaints.getOrPut(sizePx to bold) {
            Paint(labelPaint(sizePx, bold)).apply {
                color = Look.ink.toArgb()
                setShadowLayer(sizePx / 4, 0f, 0f, Look.inkHalo.toArgb())
            }
        }
    }
