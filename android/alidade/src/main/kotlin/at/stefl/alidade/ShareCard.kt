package at.stefl.alidade

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withClip
import java.io.File
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// The card of a shot, to share it: the photo as the shot screen shows it (the time, the lens, and
// the pan), the title, the rows of data (the time, the place, the elevation, the subject), and the
// logo. The app writes the card to its cache and gives it to the share sheet of Android with a
// FileProvider, together with a short text and a link to the spot on OpenStreetMap.

/** What the card shows. */
class ShareCard(
    val shot: Shot,
    val horizon: Horizon,
    val unixUtc: Double,
    val focalMm: Int,
    val pan: Pan,
    val zone: ZoneId,
    val sky: Sky?,
)

private const val CARD_WIDTH = 1080
/** The scale of the card: 1 dp of the photo is this number of pixels. */
private const val CARD_DENSITY = 2.75f
private const val MARGIN = 64f
private const val ROW_HEIGHT = 58f

/** Draws the card and opens the share sheet. */
suspend fun shareShot(context: Context, card: ShareCard) {
    val file = withContext(Dispatchers.Default) { writeCard(context, card) }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.share", file)
    val send =
        Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, shareText(card))
            putExtra(Intent.EXTRA_TITLE, shotTitle(card.shot))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    context.startActivity(Intent.createChooser(send, "Share the shot"))
}

/** The text that goes with the card: the title, the time, and the spot. */
fun shareText(card: ShareCard): String {
    val s = card.shot.spot
    val lat = "%.5f".format(Locale.ROOT, s.latitudeDeg)
    val lon = "%.5f".format(Locale.ROOT, s.longitudeDeg)
    return "${shotTitle(card.shot)}\n${dateTime(card.unixUtc, card.zone)}\n" +
        "https://www.openstreetmap.org/?mlat=$lat&mlon=$lon#map=15/$lat/$lon"
}

private fun writeCard(context: Context, card: ShareCard): File {
    val bitmap = drawCard(context, card)
    val dir = File(context.cacheDir, "share").apply { mkdirs() }
    val file = File(dir, "alidade-shot.png")
    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    bitmap.recycle()
    return file
}

private fun drawCard(context: Context, card: ShareCard): Bitmap {
    val shot = card.shot
    val rows = shotRows(shot, card.unixUtc, card.focalMm, card.zone, card.horizon)
    // The photo has the same aspect as on the screen of the shot.
    val photoHeight = (CARD_WIDTH / 0.9f)
    val titleTop = photoHeight + MARGIN
    val rowsTop = titleTop + 150f
    val height = (rowsTop + rows.size * ROW_HEIGHT + 150f).toInt()
    val bitmap = createBitmap(CARD_WIDTH, height)
    val canvas = Canvas(bitmap)
    canvas.drawColor(Shade.paper.toArgb())

    // The photo, as the shot screen shows it.
    val track = trackOf(shot, card.zone)
    val frame =
        frameOf(
                shot,
                card.horizon,
                card.unixUtc,
                card.focalMm,
                false,
                card.zone,
                card.sky,
                track = track,
            )
            .withView(viewOf(shot, card.focalMm, false, card.pan))
    val photo = RectF(0f, 0f, CARD_WIDTH.toFloat(), photoHeight)
    canvas.withClip(photo) { PhotoPainter(CARD_DENSITY).draw(this, photo, frame) }

    // The title in the serif face of the app, and the step label above it.
    val label =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Shade.rose.toArgb()
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textSize = 30f
            letterSpacing = 0.12f
        }
    canvas.drawText(
        (if (shot.eclipse != null) "THE ECLIPSE"
        else if (shot.target != null) "THE SHOT" else "THE SKY"),
        MARGIN,
        titleTop + 30f,
        label,
    )
    val title =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Shade.ink.toArgb()
            typeface = Typeface.SERIF
            textSize = 64f
        }
    canvas.drawText(
        fit(shotTitle(shot), title, CARD_WIDTH - 2 * MARGIN),
        MARGIN,
        titleTop + 105f,
        title,
    )

    // The rows of data, like the EXIF data of a camera.
    val key =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Shade.muted.toArgb()
            typeface = Typeface.MONOSPACE
            textSize = 27f
            letterSpacing = 0.06f
        }
    val value =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Shade.ink.toArgb()
            typeface = Typeface.MONOSPACE
            textSize = 33f
        }
    val line =
        Paint().apply {
            color = Shade.line.toArgb()
            strokeWidth = 2f
        }
    val valueLeft = MARGIN + 300f
    rows.forEachIndexed { i, (k, v) ->
        val y = rowsTop + i * ROW_HEIGHT
        canvas.drawText(k.uppercase(), MARGIN, y + 38f, key)
        canvas.drawText(fit(v, value, CARD_WIDTH - MARGIN - valueLeft), valueLeft, y + 38f, value)
        canvas.drawLine(MARGIN, y + ROW_HEIGHT, CARD_WIDTH - MARGIN, y + ROW_HEIGHT, line)
    }

    // The logo and the name of the app at the bottom.
    val bottom = height - 50f
    ContextCompat.getDrawable(context, R.drawable.logo)?.let {
        it.setBounds(MARGIN.toInt(), (bottom - 64).toInt(), MARGIN.toInt() + 64, bottom.toInt())
        it.draw(canvas)
    }
    val brand =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Shade.ink.toArgb()
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textSize = 28f
            letterSpacing = 0.2f
        }
    canvas.drawText("ALIDADE", MARGIN + 90f, bottom - 20f, brand)
    val note = Paint(key).apply { textAlign = Paint.Align.RIGHT }
    canvas.drawText("The photo planner", CARD_WIDTH - MARGIN, bottom - 20f, note)
    return bitmap
}

/** The text, cut with an ellipsis so it fits into [width]. */
private fun fit(text: String, paint: Paint, width: Float): String {
    if (paint.measureText(text) <= width) return text
    var end = text.length
    while (end > 1 && paint.measureText(text, 0, end) + paint.measureText("…") > width) end--
    return text.substring(0, end).trimEnd() + "…"
}
