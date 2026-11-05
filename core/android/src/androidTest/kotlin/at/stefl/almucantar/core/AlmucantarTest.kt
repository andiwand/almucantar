package at.stefl.almucantar.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the Kotlin API on a device or an emulator, so it tests the JNI layer, the packing, and the
 * native library of the ABI. The C++ tests check the precision; these tests check the binding.
 */
@RunWith(AndroidJUnit4::class)
class AlmucantarTest {
    private val vienna = Observer(latitudeDeg = 48.2082, longitudeDeg = 16.3738)

    // 2026-06-20T22:00Z, the local midnight of 2026-06-21 in Vienna (CEST).
    private val dayStart = 1781992800.0
    private val day = 86400.0

    // A device held upright in portrait, with the back camera to the north.
    private val northUpright = Quaternion(w = cos(PI / 4), x = sin(PI / 4))

    @Test
    fun versionHasThreeParts() {
        assertEquals(2, version().count { it == '.' })
    }

    @Test
    fun defaultsAreTheSameAsInCpp() {
        val expected =
            SunOptions().pack() +
                EventOptions().pack() +
                PathOptions().pack() +
                SearchOptions().pack() +
                Camera().pack() +
                View().pack().copyOfRange(0, 6) +
                MagnetometerOptions().pack() +
                HeadingEstimate().pack() +
                MoonOptions().pack() +
                doubleArrayOf(DEFAULT_HEADING_DRIFT_DEG2_PER_S) +
                HeadingFilterOptions().pack() +
                HorizonOptions().pack() +
                TerrainEventOptions().pack() +
                SkyOptions().pack() +
                BodyOptions().pack() +
                SightOptions().pack() +
                AlignmentOptions().pack() +
                AlignmentLineOptions().pack() +
                DarkOptions().pack()
        assertEquals(expected.toList(), AlmucantarNative.defaults().toList())
    }

    @Test
    fun sunPositionAtNoon() {
        val h = sunPosition(1782039420.0, vienna)
        assertTrue(h.azimuthDeg in 179.0..181.0)
        assertTrue(h.altitudeDeg in 65.0..65.5)
        val airless = sunPosition(1782039420.0, vienna, SunOptions(refraction = false))
        assertTrue(h.altitudeDeg > airless.altitudeDeg)
    }

    @Test
    fun sunEventsAndPolarDay() {
        val events = sunEvents(vienna, dayStart, dayStart + day)
        assertEquals(DayKind.NORMAL, events.kind)
        assertEquals(listOf(true, false), events.crossings.map { it.rising })
        assertTrue(events.crossings[0].unixUtc < events.culminationUtc)

        val tromso = Observer(latitudeDeg = 69.6492, longitudeDeg = 18.9553)
        val polar = sunEvents(tromso, dayStart, dayStart + day)
        assertEquals(DayKind.POLAR_DAY, polar.kind)
        assertTrue(polar.crossings.isEmpty())
    }

    @Test
    fun sunPath() {
        val path = sunPath(vienna, dayStart, dayStart + day, PathOptions(stepS = 3600.0))
        assertEquals(25, path.size)
        assertEquals(dayStart + 3600.0, path[1].unixUtc, 0.0)
        assertEquals(sunPosition(path[13].unixUtc, vienna), path[13].position)
    }

    @Test
    fun magneticDeclination() {
        // The WMM2025 test value at height 0 km: 2027.5, 13 S, 59 W: -17.49 degrees.
        val t = 1798761600.0 + 0.5 * 365 * day
        val d = magneticDeclinationDeg(Observer(-13.0, -59.0), t)
        assertEquals(-17.49, d, 0.006)
    }

    @Test
    fun projectAndUnproject() {
        val view = View(deviceToEnu = northUpright)
        val center = project(view, Horizontal(0.0, 0.0))
        assertTrue(center.valid)
        assertEquals(500.0, center.point.x, 1e-9)
        assertEquals(500.0, center.point.y, 1e-9)

        val right = project(view, Horizontal(10.0, 0.0))
        assertEquals(500.0 + view.camera.fx * tan(10 * PI / 180), right.point.x, 1e-9)
        val back = unproject(view, right.point)
        assertEquals(10.0, back.azimuthDeg, 1e-9)
        assertEquals(0.0, back.altitudeDeg, 1e-9)

        assertFalse(project(view, Horizontal(180.0, 0.0)).valid)
    }

    @Test
    fun screenRotationAndCamera() {
        // The same scene in landscape, with the device turned counterclockwise (ROTATION_90):
        // device +x points up, device +y points west.
        val landscape =
            quaternionFromAxes(
                x = doubleArrayOf(0.0, 0.0, 1.0),
                y = doubleArrayOf(-1.0, 0.0, 0.0),
                z = doubleArrayOf(0.0, -1.0, 0.0),
            )
        val camera = cameraFromFov(1920.0, 1080.0, 70.0)
        assertEquals(960.0 / tan(35 * PI / 180), camera.fx, 1e-9)
        val view = View(landscape, ScreenRotation.ROTATION_90, camera = camera)
        val p = project(view, Horizontal(10.0, 5.0))
        assertTrue(p.valid)
        assertTrue(p.point.x > 960.0)
        assertTrue(p.point.y < 540.0)
    }

    @Test
    fun linesAndHorizon() {
        val view = View(deviceToEnu = northUpright)
        val horizon = horizonLine(view)
        assertEquals(1, horizon.count)
        for (i in 0 until horizon.size(0)) {
            assertEquals(500.0, horizon.y(0, i), 1e-6)
        }

        val path = sunPath(vienna, dayStart, dayStart + day)
        val packed = DirectionLine.fromPath(path)
        assertEquals(path.size, packed.size)
        assertEquals(path[100].position, packed[100])
        val flat = projectLine(View(deviceToEnu = northUpright, headingOffsetDeg = 90.0), packed)
        val list = projectPath(View(deviceToEnu = northUpright, headingOffsetDeg = 90.0), path)
        assertEquals(list, flat.toList())
    }

    @Test
    fun search() {
        val t = dayStart + 10 * 3600.0
        val direction = sunPosition(t, vienna)
        val match = findTime(vienna, direction, dayStart, dayStart + day)
        assertEquals(t, match.unixUtc, 1.0)
        assertTrue(match.separationDeg < 1e-3)

        val year = 1767222000.0 // 2026-01-01T00:00 CET
        val may1 = year + 120 * day + 10 * 3600
        val dates = findDates(vienna, sunPosition(may1, vienna), year, year + 365 * day)
        assertEquals(listOf(120, 222), dates.map { ((it.unixUtc - year) / day).toInt() })
    }

    @Test
    fun headingFilter() {
        val e = headingUpdate(HeadingEstimate(10.0, 4.0), HeadingMeasurement(20.0, 4.0))
        assertEquals(15.0, e.offsetDeg, 1e-12)
        assertEquals(2.0, e.varianceDeg2, 1e-12)
        assertEquals(5.0, headingPredict(HeadingEstimate(0.0, 4.0), 60.0).varianceDeg2, 1e-12)

        // The center of the screen shows the azimuth 0 of the frame; truly it is 70.
        val view = View(deviceToEnu = northUpright)
        val m = headingFromDirection(view, ScreenPoint(500.0, 500.0), 70.0, 0.5)
        assertEquals(70.0, m.offsetDeg, 1e-9)

        // A field to the north of the frame (device -z) and down (device -y): the offset is the
        // declination.
        val t = 1767225600.0
        val mag = headingFromMagnetometer(northUpright, Vector3(0.0, -40.0, -20.0), vienna, t)
        assertEquals(magneticDeclinationDeg(vienna, t), mag.offsetDeg, 1e-9)
    }

    @Test
    fun headingFilterWithBias() {
        var f = headingFilterStart(Vector3(1.0, 2.0, 3.0), 0.5)
        assertEquals(UNKNOWN_HEADING_VARIANCE_DEG2, f.varianceDeg2, 0.0)
        assertEquals(HEADING_FILTER_SIZE * HEADING_FILTER_SIZE, f.covariance.size)
        assertEquals(2.0, f.biasUt.y, 0.0)
        // The field of headingFilter, plus the bias: the offset is near the declination.
        val t = 1767225600.0
        f = headingFilterPredict(f, 1.0)
        f = headingFilterUpdateMagnetometer(f, northUpright, Vector3(1.0, -38.0, -17.0), vienna, t)
        assertEquals(magneticDeclinationDeg(vienna, t), f.offsetDeg, 5.0)
        f = headingFilterUpdate(f, HeadingMeasurement(10.0, 0.01))
        assertEquals(10.0, f.offsetDeg, 0.1)
        assertEquals(f.offsetDeg, f.estimate.offsetDeg, 0.0)
        assertThrows(IllegalArgumentException::class.java) {
            headingFilterStart(Vector3(), 0.0)
        }
    }

    @Test
    fun moonAndAzimuthTimes() {
        val t = 1767225600.0
        val airless = moonPosition(t, vienna, MoonOptions(refraction = false))
        assertTrue(moonPosition(t, vienna).altitudeDeg >= airless.altitudeDeg)
        assertTrue(moonIlluminatedFraction(1767441600.0) > 0.99)

        val ae = sunPosition(1782039420.0, vienna, SunOptions(model = SolarModel.ASTRONOMY_ENGINE))
        assertEquals(sunPosition(1782039420.0, vienna).altitudeDeg, ae.altitudeDeg, 0.03)

        val at = dayStart + 9.5 * 3600
        val crossings =
            findAzimuthTimes(vienna, sunPosition(at, vienna).azimuthDeg, dayStart, dayStart + day)
        assertEquals(1, crossings.size)
        assertEquals(at, crossings[0].unixUtc, 1.0)
    }

    @Test
    fun readElevationGeotiff() {
        val bytes =
            InstrumentationRegistry.getInstrumentation()
                .context
                .assets
                .open("float_tiles_deflate_p3.tif")
                .use { it.readBytes() }
        val grid = readElevationGeotiff(bytes)
        assertEquals(7, grid.rows)
        assertEquals(5, grid.columns)
        assertEquals(35, grid.heightsM.size)
        assertEquals(47.75, grid.northLatitudeDeg, 1e-9)
        assertEquals(1000.25f, grid.heightsM[0], 1e-3f)
        assertEquals(4, readElevationGeotiff(bytes, level = 1).rows)
        val prefix = elevationGeotiffPrefixSize(bytes, 1)
        assertTrue(prefix > 0 && prefix <= bytes.size)
        assertEquals(4, readElevationGeotiff(bytes.copyOf(prefix.toInt()), level = 1).rows)
        assertEquals(0L, elevationGeotiffPrefixSize(bytes.copyOf(16), 0))
        val e =
            assertThrows(IllegalArgumentException::class.java) {
                readElevationGeotiff(byteArrayOf(0x49, 0x49, 0x2a, 0))
            }
        assertTrue(e.message!!.contains("GeoTIFF"))
    }

    @Test
    fun elevationTiles() {
        val tiles = elevationTiles(vienna, 20_000.0)
        assertEquals(ElevationTile(48, 16, 0.0), tiles[0])
        assertTrue(tiles.zipWithNext().all { (a, b) -> a.distanceM <= b.distanceM })
        assertTrue(tiles.all { it.distanceM < 20_000.0 })
    }

    @Test
    fun horizonProfileOverFlatGround() {
        // A flat grid of 150 m around Vienna, with a step of 1/120 degree.
        val rows = 61
        val columns = 91
        val grid =
            ElevationGrid(
                northLatitudeDeg = vienna.latitudeDeg + 0.25,
                westLongitudeDeg = vienna.longitudeDeg - 0.375,
                latitudeStepDeg = 1.0 / 120,
                longitudeStepDeg = 1.0 / 120,
                rows = rows,
                columns = columns,
                heightsM = FloatArray(rows * columns) { 150f },
            )
        val profile = horizonProfile(vienna, listOf(grid), HorizonOptions(azimuthStepDeg = 10.0))
        assertEquals(36, profile.altitudeDeg.size)
        assertEquals(36, profile.distanceM.size)
        assertEquals(150.0, profile.observerElevationM, 1e-3)
        assertTrue(profile.altitudeDeg.all { it > -0.1 && it < 0 })
        assertEquals(
            (profile.altitudeDeg[0] + profile.altitudeDeg[1]) / 2,
            terrainAltitudeDeg(profile, 5.0),
            1e-9,
        )

        // No data at the observer.
        grid.heightsM.fill(Float.NaN)
        val e =
            assertThrows(IllegalArgumentException::class.java) {
                horizonProfile(vienna, listOf(grid))
            }
        assertTrue(e.message!!.contains("no data at the observer"))
    }

    @Test
    fun terrainEvents() {
        val flat = HorizonProfile(1.0, DoubleArray(360), DoubleArray(360) { 1000.0 })
        val events = terrainEvents(vienna, flat, dayStart, dayStart + day)
        assertEquals(DayKind.NORMAL, events.kind)
        assertEquals(listOf(true, false), events.crossings.map { it.rising })
        val sun = sunEvents(vienna, dayStart, dayStart + day)
        assertEquals(sun.crossings[0].unixUtc, events.crossings[0].unixUtc, 60.0)

        val wall = HorizonProfile(1.0, DoubleArray(360) { 80.0 }, DoubleArray(360) { 1000.0 })
        val hidden =
            terrainEvents(
                vienna,
                wall,
                dayStart,
                dayStart + day,
                TerrainEventOptions(limbDeg = 0.0),
            )
        assertEquals(DayKind.POLAR_NIGHT, hidden.kind)
        assertTrue(hidden.crossings.isEmpty())

        // The moon: the upper limb is on the horizon at each crossing.
        val moon =
            terrainEvents(
                vienna,
                flat,
                dayStart,
                dayStart + 3 * day,
                TerrainEventOptions(body = Body.MOON),
            )
        assertTrue(moon.crossings.size in 5..7)
        val rise = moon.crossings.first { it.rising }
        assertTrue(moonPosition(rise.unixUtc + 2, vienna).altitudeDeg + 0.2667 > 0)
        assertTrue(moonPosition(rise.unixUtc - 2, vienna).altitudeDeg + 0.2667 < 0)

        assertThrows(IllegalArgumentException::class.java) {
            terrainAltitudeDeg(HorizonProfile(1.0, DoubleArray(0), DoubleArray(0)), 0.0)
        }
    }

    @Test
    fun invalidArgumentsThrowIllegalArgumentException() {
        val e =
            assertThrows(IllegalArgumentException::class.java) {
                sunPosition(0.0, Observer(latitudeDeg = 91.0))
            }
        assertTrue(e.message!!.contains("latitude_deg"))
        assertThrows(IllegalArgumentException::class.java) { sunEvents(vienna, 10.0, 10.0) }
        assertThrows(IllegalArgumentException::class.java) {
            project(View(deviceToEnu = Quaternion(0.0, 0.0, 0.0, 0.0)), Horizontal())
        }
        assertThrows(IllegalArgumentException::class.java) {
            cameraFromFov(100.0, 100.0, 180.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            findDates(vienna, Horizontal(), dayStart, dayStart + 3600.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            headingUpdate(HeadingEstimate(), HeadingMeasurement(0.0, 0.0))
        }
    }

    // 2026-03-20T12:00Z.
    private val equinox = 1774008000.0

    // 2026-01-01 and 2028-01-01, 00:00 UTC.
    private val eclipseStart = 1767225600.0
    private val eclipseEnd = 1830297600.0

    @Test
    fun bodyPosition() {
        assertEquals(10, Body.entries.size)
        assertEquals(moonPosition(equinox, vienna), bodyPosition(Body.MOON, equinox, vienna))
        assertEquals(sunPosition(equinox, vienna), bodyPosition(Body.SUN, equinox, vienna))
        val airless = BodyOptions(refraction = false)
        val center = bodyPosition(Body.GALACTIC_CENTER, equinox, vienna, airless)
        val galactic =
            galacticPosition(
                GALACTIC_CENTER_LONGITUDE_DEG,
                GALACTIC_CENTER_LATITUDE_DEG,
                equinox,
                vienna,
                SkyOptions(refraction = false),
            )
        assertEquals(galactic.altitudeDeg, center.altitudeDeg, 1e-9)
        assertEquals(
            planetPosition(Planet.NEPTUNE, equinox, vienna),
            bodyPosition(Body.NEPTUNE, equinox, vienna),
        )
    }

    @Test
    fun sightAndFindAlignments() {
        val tower = Site(latitudeDeg = 48.25, longitudeDeg = 16.40, elevationM = 600.0)
        val eye = Site(latitudeDeg = 48.2082, longitudeDeg = 16.3738, elevationM = 170.0)
        val s = sight(eye, tower)
        assertTrue(s.direction.azimuthDeg > 0 && s.direction.azimuthDeg < 90)
        assertEquals(5050.0, s.distanceM, 50.0)
        assertTrue(s.direction.altitudeDeg > 0)
        val flat = sight(eye, tower, SightOptions(refractionCoefficient = 0.0))
        assertTrue(flat.direction.altitudeDeg < s.direction.altitudeDeg)

        // The moon of one time is found again at that time.
        val t0 = equinox + 3 * day
        val direction = moonPosition(t0, vienna)
        val found =
            findAlignments(
                Body.MOON,
                vienna,
                direction,
                t0 - day,
                t0 + day,
                AlignmentOptions(maxSeparationDeg = 0.1),
            )
        assertEquals(1, found.size)
        assertEquals(t0, found[0].unixUtc, 2.0)
        assertTrue(found[0].separationDeg < 0.1)
        assertEquals(direction.azimuthDeg, found[0].body.azimuthDeg, 0.1)
        assertThrows(IllegalArgumentException::class.java) { sight(eye, eye) }
    }

    @Test
    fun alignmentLineWithoutTiles() {
        val tower = Site(latitudeDeg = 47.0, longitudeDeg = 11.0, elevationM = 300.0)
        val line =
            alignmentLine(
                Body.SUN,
                tower,
                equinox,
                options = AlignmentLineOptions(maxDistanceM = 5000.0, stepM = 500.0),
            )
        assertEquals(10, line.points.size)
        assertTrue(line.spots.isEmpty())
        assertTrue(line.points.all { it.groundElevationM.isNaN() })
        assertTrue(line.points.zipWithNext().all { (a, b) -> a.distanceM < b.distanceM })
        // The landmark from a point of the line has the altitude of the body there.
        val first = line.points[0]
        val s =
            sight(
                Site(first.place.latitudeDeg, first.place.longitudeDeg, first.eyeElevationM),
                tower,
            )
        assertEquals(first.body.altitudeDeg, s.direction.altitudeDeg, 1e-6)
        assertEquals(first.distanceM, s.distanceM, 1.0)
    }

    @Test
    fun alignmentLineAndLineOfSightOverFlatGround() {
        val grid = flatGrid(northLatitudeDeg = 47.25, westLongitudeDeg = 10.75, heightM = 0f)
        val eye = Site(latitudeDeg = 47.0, longitudeDeg = 11.0, elevationM = 2.0)
        val tower = Site(latitudeDeg = 47.1, longitudeDeg = 11.0, elevationM = 100.0)
        val clear = lineOfSight(eye, tower, listOf(grid))
        assertTrue(clear.visible)
        assertTrue(clear.clearanceDeg > 0)

        // A wall of 500 m between the eye and the tower.
        val wall = flatGrid(northLatitudeDeg = 47.25, westLongitudeDeg = 10.75, heightM = 0f)
        val row = ((47.25 - 47.05) * 120).toInt()
        for (c in 0 until wall.columns) wall.heightsM[row * wall.columns + c] = 500f
        val blocked = lineOfSight(eye, tower, listOf(wall))
        assertFalse(blocked.visible)
        assertTrue(blocked.clearanceDeg < 0)
        assertEquals(47.05, blocked.blocking.latitudeDeg, 0.01)
        assertTrue(blocked.blockingDistanceM > 4000 && blocked.blockingDistanceM < 7000)
        assertThrows(IllegalArgumentException::class.java) { lineOfSight(eye, tower, emptyList()) }

        // The sun in the morning, low in the east: the line goes west from the tower, and the
        // flat ground has a spot.
        val morning = equinox - 4 * 3600
        val line =
            alignmentLine(
                Body.SUN,
                Site(47.0, 11.0, 100.0),
                morning,
                listOf(grid),
                AlignmentLineOptions(maxDistanceM = 20_000.0, stepM = 200.0),
            )
        assertTrue(line.points.isNotEmpty())
        val inside = line.points.filter { !it.groundElevationM.isNaN() }
        assertTrue(inside.isNotEmpty())
        assertTrue(inside.all { abs(it.groundElevationM) < 1e-3 })
        assertTrue(line.spots.isNotEmpty())
        val spot = line.spots[0]
        assertEquals(spot.groundElevationM + 1.6, spot.eyeElevationM, 0.5)
    }

    @Test
    fun darkWindows() {
        val windows = darkWindows(vienna, equinox, equinox + 5 * day)
        assertTrue(windows.isNotEmpty())
        assertTrue(windows.all { it.endUtc > it.startUtc })
        assertTrue(windows.zipWithNext().all { (a, b) -> a.endUtc <= b.startUtc })
        // A wall of 80° hides the moon, so the windows can only be longer.
        val wall = HorizonProfile(1.0, DoubleArray(360) { 80.0 }, DoubleArray(360) { 1000.0 })
        val hidden = darkWindows(vienna, equinox, equinox + 5 * day, profile = wall)
        assertTrue(
            hidden.sumOf { it.endUtc - it.startUtc } >=
                windows.sumOf { it.endUtc - it.startUtc } - 10
        )
        // The galactic center behind the wall: no window for the Milky Way.
        val milkyWay =
            darkWindows(
                vienna,
                equinox,
                equinox + 5 * day,
                DarkOptions(galacticCenter = true),
                wall,
            )
        assertTrue(milkyWay.isEmpty())
        assertThrows(IllegalArgumentException::class.java) { darkWindows(vienna, 10.0, 10.0) }
    }

    @Test
    fun solarEclipsesAndTheCentralLine() {
        val eclipses = solarEclipses(eclipseStart, eclipseEnd)
        assertEquals(4, eclipses.size)
        val total = eclipses.filter { it.kind == EclipseKind.TOTAL }
        assertEquals(2, total.size)
        assertTrue(total.all { !it.peakPlace.latitudeDeg.isNaN() && it.obscuration > 0.99 })
        assertTrue(eclipses.zipWithNext().all { (a, b) -> a.peakUtc < b.peakUtc })
        val line = eclipseCentralLine(total[1].peakUtc, 120.0)
        assertTrue(line.size > 50)
        assertTrue(line.all { it.umbraRadiusKm > 0 })
        assertEquals(120.0, line[1].unixUtc - line[0].unixUtc, 1e-6)
        assertTrue(eclipseCentralLine(total[1].peakUtc).size > line.size)
        val annular = eclipses.first { it.kind == EclipseKind.ANNULAR }
        val ring = eclipseCentralLine(annular.peakUtc, 600.0)
        assertTrue(ring.isNotEmpty() && ring.all { it.umbraRadiusKm < 0 })

        val local = localSolarEclipses(vienna, eclipseStart, eclipseEnd)
        assertTrue(local.isNotEmpty())
        assertTrue(local.all { it.kind == EclipseKind.PARTIAL })
        assertTrue(local.all { it.totalBeginUtc.isNaN() && it.totalEndUtc.isNaN() })
        assertTrue(local.all { it.partialBeginUtc < it.peakUtc && it.peakUtc < it.partialEndUtc })
        assertTrue(local.all { it.obscuration >= 0 && it.obscuration <= 1 })
        assertThrows(IllegalArgumentException::class.java) {
            eclipseCentralLine(total[1].peakUtc, 0.5)
        }
        assertThrows(IllegalArgumentException::class.java) { solarEclipses(10.0, 10.0) }
    }

    @Test
    fun lunarEclipses() {
        val eclipses = lunarEclipses(eclipseStart, eclipseEnd)
        val first = eclipses[0]
        assertEquals(EclipseKind.TOTAL, first.kind)
        assertEquals(1.0, first.obscuration, 1e-9)
        assertTrue(first.penumbralBeginUtc < first.partialBeginUtc)
        assertTrue(first.partialBeginUtc < first.totalBeginUtc)
        assertTrue(first.totalBeginUtc < first.peakUtc && first.peakUtc < first.totalEndUtc)
        assertTrue(first.partialEndUtc < first.penumbralEndUtc)
        val penumbral = eclipses.filter { it.kind == EclipseKind.PENUMBRAL }
        assertTrue(penumbral.all { it.partialBeginUtc.isNaN() && it.obscuration == 0.0 })
        assertThrows(IllegalArgumentException::class.java) {
            lunarEclipses(0.0, 200 * 365.25 * day)
        }
    }

    @Test
    fun terrainEventsOfTheNewBodies() {
        val flat = HorizonProfile(1.0, DoubleArray(360), DoubleArray(360) { 1000.0 })
        for (body in listOf(Body.GALACTIC_CENTER, Body.JUPITER)) {
            val events =
                terrainEvents(
                    vienna,
                    flat,
                    dayStart,
                    dayStart + 2 * day,
                    TerrainEventOptions(body = body, limbDeg = 0.0),
                )
            assertEquals(DayKind.NORMAL, events.kind)
            val rise = events.crossings.first { it.rising }
            assertTrue(bodyPosition(body, rise.unixUtc + 5, vienna).altitudeDeg > 0)
            assertTrue(bodyPosition(body, rise.unixUtc - 5, vienna).altitudeDeg < 0)
        }
    }

    private fun flatGrid(
        northLatitudeDeg: Double,
        westLongitudeDeg: Double,
        heightM: Float,
    ): ElevationGrid {
        // 0.5° by 0.5° with a step of 1/120 degree.
        val rows = 61
        val columns = 61
        return ElevationGrid(
            northLatitudeDeg = northLatitudeDeg,
            westLongitudeDeg = westLongitudeDeg,
            latitudeStepDeg = 1.0 / 120,
            longitudeStepDeg = 1.0 / 120,
            rows = rows,
            columns = columns,
            heightsM = FloatArray(rows * columns) { heightM },
        )
    }

    private fun quaternionFromAxes(x: DoubleArray, y: DoubleArray, z: DoubleArray): Quaternion {
        // The rotation matrix has the columns x, y, z. This form needs a positive trace or a
        // large m00, which is true for the poses of these tests.
        val m00 = x[0]
        val m11 = y[1]
        val m22 = z[2]
        val trace = m00 + m11 + m22
        if (trace > 0) {
            val s = 2 * kotlin.math.sqrt(trace + 1)
            return Quaternion(s / 4, (y[2] - z[1]) / s, (z[0] - x[2]) / s, (x[1] - y[0]) / s)
        }
        val m = maxOf(m00, m11, m22)
        return when {
            m == m00 -> {
                val s = 2 * kotlin.math.sqrt(1 + m00 - m11 - m22)
                Quaternion((y[2] - z[1]) / s, s / 4, (y[0] + x[1]) / s, (z[0] + x[2]) / s)
            }
            m == m11 -> {
                val s = 2 * kotlin.math.sqrt(1 + m11 - m00 - m22)
                Quaternion((z[0] - x[2]) / s, (y[0] + x[1]) / s, s / 4, (z[1] + y[2]) / s)
            }
            else -> {
                val s = 2 * kotlin.math.sqrt(1 + m22 - m00 - m11)
                Quaternion((x[1] - y[0]) / s, (z[0] + x[2]) / s, (z[1] + y[2]) / s, s / 4)
            }
        }.also { check(abs(it.w * it.w + it.x * it.x + it.y * it.y + it.z * it.z - 1) < 1e-9) }
    }

    @Test
    fun planetsAndTheMilkyWay() {
        val t = 1782000000.0
        for (planet in Planet.entries) {
            val seen = planetPosition(planet, t, vienna)
            val airless = planetPosition(planet, t, vienna, SkyOptions(refraction = false))
            assertEquals(airless.azimuthDeg, seen.azimuthDeg, 0.0)
            assertTrue(seen.altitudeDeg > airless.altitudeDeg)
        }
        assertTrue(planetMagnitude(Planet.VENUS, t) < -3.5)
        val plane = galacticPlane(t, vienna, 10.0)
        assertEquals(36, plane.size)
        val center =
            galacticPosition(GALACTIC_CENTER_LONGITUDE_DEG, GALACTIC_CENTER_LATITUDE_DEG, t, vienna)
        assertEquals(center.azimuthDeg, plane[0].azimuthDeg, 1e-9)
    }

    @Test
    fun starsAndProjectPoints() {
        val t = 1782000000.0
        // Sirius and Vega, J2000.
        val catalog =
            StarCatalog(doubleArrayOf(101.2871, 279.2346), doubleArrayOf(-16.7161, 38.7837))
        val stars = starPositions(catalog, t, vienna)
        assertEquals(4, stars.size)
        val moving =
            starPositions(
                StarCatalog(
                    catalog.raDeg,
                    catalog.decDeg,
                    doubleArrayOf(-0.553, 0.201),
                    doubleArrayOf(-1.205, 0.286),
                ),
                t,
                vienna,
            )
        assertTrue(moving[1] != stars[1])
        assertThrows(IllegalArgumentException::class.java) {
            starPositions(StarCatalog(doubleArrayOf(1.0, 2.0), doubleArrayOf(1.0)), t, vienna)
        }

        val view = View(camera = cameraFromFov(1000.0, 1000.0, 60.0))
        val points = projectPoints(view, doubleArrayOf(0.0, 0.0, 180.0, 0.0))
        assertEquals(4, points.size)
        val one = project(view, Horizontal(0.0, 0.0))
        assertEquals(one.valid, !points[0].isNaN())
        if (one.valid) assertEquals(one.point.x, points[0], 0.0)
    }
}
