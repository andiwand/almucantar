package at.stefl.starradar

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import at.stefl.almucantar.shared.TERRAIN_SOURCE

private const val PREFERENCES = "settings"

/** A switch of the settings. A change is stored at once. */
class BooleanSetting(
    private val state: MutableState<Boolean>,
    private val save: (Boolean) -> Unit,
) {
    var value: Boolean
        get() = state.value
        set(value) {
            state.value = value
            save(value)
        }
}

/** The settings of starradar. */
class Settings(
    /** The lines and the names of the constellations. */
    val lines: BooleanSetting,
    /** The terrain horizon; when it is off, the app downloads nothing. */
    val terrain: BooleanSetting,
    /** The red light, so the eyes stay adapted to the dark. */
    val red: BooleanSetting,
)

@Composable
fun rememberSettings(): Settings {
    val context = LocalContext.current
    return remember {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        fun setting(key: String, default: Boolean) =
            BooleanSetting(mutableStateOf(preferences.getBoolean(key, default))) {
                preferences.edit { putBoolean(key, it) }
            }
        Settings(setting("lines", true), setting("terrain", true), setting("red", false))
    }
}

/** The settings and the credits of the data. */
@Composable
fun SettingsDialog(settings: Settings, stars: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Toggle("Constellation lines", settings.lines)
                Toggle("Terrain horizon", settings.terrain)
                Toggle("Red light", settings.red)
                Text(
                    "$stars ${TERRAIN_SOURCE.attribution} Maps: Natural Earth (public domain), " +
                        "© OpenStreetMap contributors.",
                    fontSize = 12.sp,
                )
            }
        },
    )
}

@Composable
private fun Toggle(label: String, setting: BooleanSetting) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = setting.value, onCheckedChange = { setting.value = it })
    }
}
