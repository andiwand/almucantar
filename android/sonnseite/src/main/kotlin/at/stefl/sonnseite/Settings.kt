package at.stefl.sonnseite

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import at.stefl.almucantar.shared.TERRAIN_SOURCE

private const val PREFERENCES = "settings"
private const val KEY_TERRAIN = "terrain_horizon"

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

/** The setting "Terrain horizon", on by default. */
@Composable
fun rememberTerrainSetting(): BooleanSetting {
    val context = LocalContext.current
    return remember {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        BooleanSetting(mutableStateOf(preferences.getBoolean(KEY_TERRAIN, true))) {
            preferences.edit { putBoolean(KEY_TERRAIN, it) }
        }
    }
}

/** The settings and the credits of the data. */
@Composable
fun SettingsDialog(terrain: BooleanSetting, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Settings") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Terrain horizon")
                        Text(
                            "Shows the mountains on the horizon and the visible sunrise and " +
                                "sunset. Downloads elevation data (2–5 MB per 1° area).",
                            fontSize = 12.sp,
                        )
                    }
                    Switch(checked = terrain.value, onCheckedChange = { terrain.value = it })
                }
                Text("About", fontWeight = FontWeight.SemiBold)
                Text("Landmark map: © OpenStreetMap contributors", fontSize = 12.sp)
                Text("Place map: Natural Earth (public domain)", fontSize = 12.sp)
                Text(TERRAIN_SOURCE.attribution, fontSize = 12.sp)
            }
        },
    )
}
