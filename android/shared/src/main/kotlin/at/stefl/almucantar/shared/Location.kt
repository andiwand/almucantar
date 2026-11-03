package at.stefl.almucantar.shared

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.location.LocationManagerCompat
import androidx.core.util.Consumer
import at.stefl.almucantar.core.Observer
import java.io.IOException
import java.time.ZoneId
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** Where the place comes from. */
enum class PlaceSource {
    /** The location of the device, with its accuracy. */
    DEVICE,
    /**
     * A place that the user dropped on the map or found in the search. It stays until the user
     * chooses "Use my location".
     */
    MANUAL,
    /** The device location of an earlier start of the app, until a new location arrives. */
    LAST,
    /** The main city of the device time zone. The error can be hundreds of kilometres. */
    TIME_ZONE,
    /** Nothing is known. The app uses Vienna. */
    DEFAULT,
}

/** The place of the user. */
data class Place(
    val observer: Observer,
    val source: PlaceSource,
    /** The accuracy of a device location in metres (68 %), if the device gives it. */
    val accuracyM: Double? = null,
    /** The nearest town, if it is known. */
    val name: String? = null,
    /**
     * The time zone of the place. Null is the time zone of the device: a device location is where
     * the device is, so its time zone is the zone of the device.
     */
    val zone: ZoneId? = null,
) {
    /**
     * True if the user is at this place or chose it, so the terrain of the place is useful. The
     * city of the time zone and the default place can be far away from the user.
     */
    val isKnown: Boolean
        get() =
            source == PlaceSource.DEVICE ||
                source == PlaceSource.MANUAL ||
                source == PlaceSource.LAST
}

val VIENNA = Place(Observer(48.2082, 16.3738), PlaceSource.DEFAULT, name = "Vienna")

/** The place without a location: the city of the time zone, or Vienna. */
fun fallbackPlace(context: Context, zone: ZoneId = ZoneId.systemDefault()): Place =
    TimeZonePlaces.find(context, zone.id)?.let {
        Place(it.observer, PlaceSource.TIME_ZONE, name = it.city)
    } ?: VIENNA

/**
 * The last place in the shared preferences, so the app starts at that place also offline and
 * without the location permission. The store keeps the zone also for a device location, because the
 * device can be in another zone at the next start.
 */
object PlaceStore {
    private const val PREFERENCES = "place"

    fun save(context: Context, place: Place, deviceZone: ZoneId) {
        if (!place.isKnown) return
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit {
            putString("latitude", place.observer.latitudeDeg.toString())
            putString("longitude", place.observer.longitudeDeg.toString())
            putString("name", place.name)
            putString("accuracy", place.accuracyM?.toString())
            putString("zone", (place.zone ?: deviceZone).id)
            putBoolean("manual", place.source == PlaceSource.MANUAL)
        }
    }

    /** The stored place: [PlaceSource.MANUAL], or [PlaceSource.LAST] for a device location. */
    fun load(context: Context): Place? {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val latitude = preferences.getString("latitude", null)?.toDoubleOrNull() ?: return null
        val longitude = preferences.getString("longitude", null)?.toDoubleOrNull() ?: return null
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
        return Place(
            Observer(latitude, longitude),
            if (preferences.getBoolean("manual", false)) PlaceSource.MANUAL else PlaceSource.LAST,
            preferences.getString("accuracy", null)?.toDoubleOrNull(),
            preferences.getString("name", null),
            zoneOrNull(preferences.getString("zone", null)),
        )
    }
}

/** The place, and the actions of the place panel. */
class PlaceState(initial: Place) {
    var place by mutableStateOf(initial)
        internal set

    /** True after "Use my location": the next device location replaces also a manual place. */
    internal var wantsDevice by mutableStateOf(false)

    /** Counts the requests of "Use my location", so each one asks for a new location. */
    internal var deviceRequests by mutableIntStateOf(0)

    /** Uses a place that the user dropped. A device location does not replace it. */
    fun drop(observer: Observer, name: String, zone: ZoneId) {
        // A request of "Use my location" that is not done yet does not replace this place.
        wantsDevice = false
        place = Place(observer, PlaceSource.MANUAL, name = name, zone = zone)
    }

    /** Uses the device location again ("Use my location"). */
    fun useDevice() {
        wantsDevice = true
        deviceRequests++
    }
}

/**
 * Gives the place of the user. The core needs the place only to about 1 km (PLAN.md, section 2), so
 * the coarse location is sufficient. The app starts with the last place. Without it, the place is
 * the city of the time zone. A device location replaces them, but not a place that the user
 * dropped. The name comes from the geocoder of the device, if it has one. If [persistent] is false
 * (the demo mode), the app does not read or write the last place.
 */
@Composable
fun rememberPlace(
    locationGranted: Boolean,
    deviceZone: ZoneId,
    persistent: Boolean,
    onMessage: (String) -> Unit,
): PlaceState {
    val context = LocalContext.current
    val state = remember {
        PlaceState(
            (if (persistent) PlaceStore.load(context) else null)
                ?: fallbackPlace(context, deviceZone)
        )
    }
    val message by rememberUpdatedState(onMessage)
    // A new time zone gives a new fallback city, but a known place stays.
    LaunchedEffect(deviceZone) {
        if (!state.place.isKnown) state.place = fallbackPlace(context, deviceZone)
    }
    LaunchedEffect(state.place, persistent) {
        if (persistent) PlaceStore.save(context, state.place, deviceZone)
    }
    LaunchedEffect(locationGranted, state.deviceRequests) {
        if (!locationGranted || !hasPermission(context)) {
            if (state.wantsDevice) {
                state.wantsDevice = false
                message("No location access: the place stays.")
            }
            return@LaunchedEffect
        }
        suspend fun use(location: Location) {
            // A place that the user dropped stays, until the user asks for the device location.
            if (state.place.source == PlaceSource.MANUAL && !state.wantsDevice) return
            state.wantsDevice = false
            val observer = Observer(location.latitude, location.longitude)
            val accuracy = if (location.hasAccuracy()) location.accuracy.toDouble() else null
            state.place = Place(observer, PlaceSource.DEVICE, accuracy)
            townName(context, observer)?.let {
                if (state.place.observer == observer) state.place = state.place.copy(name = it)
            }
        }
        // The last known location comes at once, but it can be old. So the app shows it first,
        // and a current location replaces it when it arrives.
        val last = lastKnownLocation(context)
        last?.let { use(it) }
        val current = currentLocation(context)
        current?.let { use(it) }
        if (last == null && current == null && state.wantsDevice) {
            state.wantsDevice = false
            message("No location: the place stays.")
        }
    }
    return state
}

/** The enabled location providers, the best first. */
private fun providers(manager: LocationManager): List<String> = buildList {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
    add(LocationManager.NETWORK_PROVIDER)
    add(LocationManager.GPS_PROVIDER)
}
    .filter { manager.isProviderEnabled(it) }

/** The newest last known location, or null. */
private fun lastKnownLocation(context: Context): Location? {
    val manager = context.getSystemService(LocationManager::class.java) ?: return null
    return try {
        providers(manager).mapNotNull { manager.getLastKnownLocation(it) }.maxByOrNull { it.time }
    } catch (_: SecurityException) {
        // The user can remove the permission at any time. Then the fallback stays.
        null
    }
}

/** One current location from the best enabled provider, or null. */
private suspend fun currentLocation(context: Context): Location? {
    val manager = context.getSystemService(LocationManager::class.java) ?: return null
    val provider = providers(manager).firstOrNull() ?: return null
    return try {
        suspendCancellableCoroutine { continuation ->
            LocationManagerCompat.getCurrentLocation(
                manager,
                provider,
                null as android.os.CancellationSignal?,
                ContextCompat.getMainExecutor(context),
                Consumer<Location?> { continuation.resume(it) },
            )
        }
    } catch (_: SecurityException) {
        null
    }
}

/** The nearest town from the geocoder of the device, or null. The geocoder can use the network. */
private suspend fun townName(context: Context, observer: Observer): String? {
    if (!Geocoder.isPresent()) return null
    val geocoder = Geocoder(context)
    val address: Address? =
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                suspendCancellableCoroutine { continuation ->
                    geocoder.getFromLocation(
                        observer.latitudeDeg,
                        observer.longitudeDeg,
                        1,
                        object : Geocoder.GeocodeListener {
                            override fun onGeocode(addresses: MutableList<Address>) {
                                continuation.resume(addresses.firstOrNull())
                            }

                            override fun onError(errorMessage: String?) {
                                continuation.resume(null)
                            }
                        },
                    )
                }
            } else {
                withContext(Dispatchers.IO) {
                    @Suppress("DEPRECATION")
                    geocoder
                        .getFromLocation(observer.latitudeDeg, observer.longitudeDeg, 1)
                        ?.firstOrNull()
                }
            }
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    return address?.run { locality ?: subAdminArea ?: adminArea ?: countryName }
}

private fun hasPermission(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

/**
 * The location error as a power of ten (±1 m, ±10 m, ... ±100 km), and the error that it causes on
 * the sky: moving by d on the Earth tilts the local vertical by d / 6371 km, which is about 1° per
 * 111 km.
 */
fun errorScale(accuracyM: Double): String {
    val exponent = kotlin.math.ceil(kotlin.math.log10(accuracyM.coerceIn(1.0, 1e5))).toInt()
    val label =
        when (exponent) {
            0 -> "±1 m"
            1 -> "±10 m"
            2 -> "±100 m"
            3 -> "±1 km"
            4 -> "±10 km"
            else -> "±100 km"
        }
    val skyDeg = Math.pow(10.0, exponent.toDouble()) / 111_195.0
    val sky = if (skyDeg < 0.01) "< 0.01°" else "%.2f°".format(Locale.ROOT, skyDeg)
    return "$label (sky $sky)"
}
