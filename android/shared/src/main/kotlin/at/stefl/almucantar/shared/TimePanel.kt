package at.stefl.almucantar.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** The date in the current locale. The locale can change while the app runs. */
private fun dateFormat() = DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.getDefault())

/** The days that the date slider covers, before and after today. */
const val DATE_RANGE_DAYS = 183

/**
 * The date and the time of the view. [dayOffset] counts days from today. [minuteOfDay] is null for
 * the live time ("now").
 */
@Composable
fun TimePanel(
    today: LocalDate,
    dayOffset: Int,
    onDayOffset: (Int) -> Unit,
    minuteOfDay: Int?,
    liveMinuteOfDay: Int,
    onMinuteOfDay: (Int?) -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                dateFormat().format(today.plusDays(dayOffset.toLong())),
                color = Color.White,
                modifier = Modifier.width(150.dp),
            )
            Slider(
                value = dayOffset.toFloat(),
                onValueChange = { onDayOffset(it.roundToInt()) },
                valueRange = -DATE_RANGE_DAYS.toFloat()..DATE_RANGE_DAYS.toFloat(),
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { onDayOffset(0) }, enabled = dayOffset != 0) { Text("Today") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            val minute = minuteOfDay ?: liveMinuteOfDay
            Text(
                "%02d:%02d".format(minute / 60, minute % 60) +
                    if (minuteOfDay == null) " (now)" else "",
                color = Color.White,
                modifier = Modifier.width(150.dp),
            )
            Slider(
                value = minute.toFloat(),
                onValueChange = { onMinuteOfDay(it.roundToInt()) },
                valueRange = 0f..(24 * 60 - 1).toFloat(),
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { onMinuteOfDay(null) }, enabled = minuteOfDay != null) {
                Text("Now")
            }
        }
    }
}
