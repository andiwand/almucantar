package at.stefl.sunradar

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
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.util.Consumer
import at.stefl.sunradar.core.Observer
import java.io.IOException
import java.time.ZoneId
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** Where the place comes from. */
enum class PlaceSource {
    /** The location of the device, with its accuracy. */
    DEVICE,
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
)

private val VIENNA = Place(Observer(48.2082, 16.3738), PlaceSource.DEFAULT, name = "Vienna")

/** The place without a location: the city of the time zone, or Vienna. */
fun fallbackPlace(context: Context, zone: ZoneId = ZoneId.systemDefault()): Place =
    TimeZonePlaces.find(context, zone.id)?.let {
        Place(it.observer, PlaceSource.TIME_ZONE, name = it.city)
    } ?: VIENNA

/**
 * Gives the place of the user. The core needs the place only to about 1 km (PLAN.md, section 2), so
 * the coarse location is sufficient. Without the permission or a location, the place is the city of
 * the time zone. The name comes from the geocoder of the device, if it has one.
 */
@Composable
fun rememberPlace(locationGranted: Boolean): State<Place> {
    val context = LocalContext.current
    val state = remember { mutableStateOf(fallbackPlace(context)) }
    LaunchedEffect(locationGranted) {
        if (!locationGranted || !hasPermission(context)) {
            return@LaunchedEffect
        }
        suspend fun use(location: Location) {
            val observer = Observer(location.latitude, location.longitude)
            val accuracy = if (location.hasAccuracy()) location.accuracy.toDouble() else null
            state.value = Place(observer, PlaceSource.DEVICE, accuracy)
            state.value = state.value.copy(name = townName(context, observer))
        }
        // The last known location comes at once, but it can be old. So the app shows it first,
        // and a current location replaces it when it arrives.
        lastKnownLocation(context)?.let { use(it) }
        currentLocation(context)?.let { use(it) }
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
