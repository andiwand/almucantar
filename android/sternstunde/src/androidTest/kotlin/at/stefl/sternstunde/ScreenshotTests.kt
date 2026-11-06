package at.stefl.sternstunde

import android.app.LocaleManager
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.LocaleList
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Takes the store screenshots (see `screenshots/README.md`). The test runs only if the
 * instrumentation argument `device` names the device (`phone` or `tablet`), so a normal
 * `connectedCheck` skips it. Start it with `bundle exec fastlane screenshots` in `android/`.
 *
 * Each screen starts [MainActivity] in the demo mode of [ScreenshotMode]. The test writes the
 * pictures to `additionalTestOutputDir/screenshots/<locale>/<device>-<screen>.png`. Gradle copies
 * this directory to the build output before it uninstalls the app, so the pictures stay.
 */
@RunWith(AndroidJUnit4::class)
class ScreenshotTests {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Before
    fun setUp() {
        assumeTrue("no device argument: this is not a screenshot run", argument("device") != null)
        // The demo mode of the status bar and the dark system bars need API 35.
        assertTrue("the screenshots need API 35 or later", Build.VERSION.SDK_INT >= 35)
        dressTheDevice()
    }

    @After
    fun tearDown() {
        if (argument("device") != null) undressTheDevice()
    }

    @Test
    fun takesTheStoreScreenshots() {
        val device = argument("device")!!
        assertTrue("no such device: $device. One of ${DEVICES.joinToString()}", device in DEVICES)
        val output =
            File(
                requireNotNull(argument("additionalTestOutputDir")) {
                    "Gradle gave no additionalTestOutputDir: start the test with fastlane or " +
                        "connectedDebugAndroidTest"
                },
                "screenshots",
            )
        val context = instrumentation.targetContext
        for (locale in locales()) {
            context.getSystemService(LocaleManager::class.java).applicationLocales =
                LocaleList.forLanguageTags(locale)
            for (screen in SCREENS) {
                val intent =
                    Intent(context, MainActivity::class.java)
                        .putExtra(ScreenshotMode.EXTRA, screen)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                // A slow emulator can restart the system UI, which ends the demo mode of the
                // status bar. So each screen enters it again.
                statusBar()
                ActivityScenario.launch<MainActivity>(intent).use { scenario ->
                    // Not the focus of the window: a popup can take the focus.
                    waitFor("the activity of $screen does not start") {
                        var shown = false
                        scenario.onActivity { shown = it.window.decorView.isLaidOut }
                        scenario.state == Lifecycle.State.RESUMED && shown
                    }
                    // The sky is calculated in the background after the first frame.
                    instrumentation.waitForIdleSync()
                    SystemClock.sleep(SETTLE_MS)
                    // A slow emulator can show "System UI isn't responding" after the restart of
                    // the system UI. The dialog is not part of the app.
                    shell("am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS")
                    instrumentation.waitForIdleSync()
                    SystemClock.sleep(DIALOG_MS)
                    save(File(File(output, locale), "$device-$screen.png"))
                }
            }
        }
    }

    /**
     * Saves a picture of the screen. A picture that is almost black is a screen that the app has
     * not drawn yet (the buttons and the stars are bright), so the test waits and takes it again.
     */
    private fun save(file: File) {
        file.parentFile!!.mkdirs()
        var bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        var tries = 1
        while (isBlack(bitmap) && tries < BLACK_TRIES) {
            bitmap.recycle()
            SystemClock.sleep(SETTLE_MS)
            bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            tries++
        }
        assertTrue("${file.name}: the screen stays black", !isBlack(bitmap))
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    /**
     * True if no pixel of a grid is brighter than a very dark gray. The night sky is nearly black,
     * so the grid is fine enough to hit the buttons.
     */
    private fun isBlack(bitmap: Bitmap): Boolean {
        for (y in 0 until bitmap.height step bitmap.height / 96) {
            for (x in 0 until bitmap.width step bitmap.width / 96) {
                val pixel = bitmap.getPixel(x, y)
                if (maxOf(pixel shr 16 and 0xff, pixel shr 8 and 0xff, pixel and 0xff) > 24) {
                    return false
                }
            }
        }
        return true
    }

    private fun waitFor(failure: String, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + FOCUS_TIMEOUT_MS
        while (!condition()) {
            assertTrue(failure, SystemClock.uptimeMillis() < end)
            SystemClock.sleep(100)
        }
    }

    private fun locales(): List<String> =
        argument("locales")?.takeIf { it.isNotBlank() }?.split("+") ?: listOf("en-US")

    private fun argument(name: String): String? =
        InstrumentationRegistry.getArguments().getString(name)

    /** Runs a shell command and reads its output to the end, so the commands run in order. */
    private fun shell(command: String) {
        val pipe = instrumentation.uiAutomation.executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(pipe).use { it.readBytes() }
    }

    private fun demo(command: String) =
        shell("am broadcast -a com.android.systemui.demo -e command $command")

    /**
     * The device as the store shows it: upright, dark (the app is dark, so the system bars get
     * light icons), with gesture navigation, and with the status bar of the demo mode (09:41, full
     * battery, no notifications).
     */
    private fun dressTheDevice() {
        standUpright()
        // Before the demo mode: both commands restart the system UI, which ends the demo mode.
        shell("cmd uimode night yes")
        shell("cmd overlay enable com.android.internal.systemui.navbar.gestural")
        SystemClock.sleep(RESTART_MS)
        // No dialogs for an app that does not respond: the system UI of a slow emulator is one.
        shell("settings put global hide_error_dialogs 1")
        shell("settings put global sysui_demo_allowed 1")
        statusBar()
    }

    /** The status bar of the demo mode: 09:41, a full battery, wifi, and no notifications. */
    private fun statusBar() {
        demo("enter")
        demo("clock -e hhmm 0941")
        demo("battery -e level 100 -e plugged false -e powersave false")
        // The extras are one bundle: the level is for the last radio, so wifi is last.
        demo("network -e mobile hide -e wifi show -e level 4")
        demo("notifications -e visible false")
        demo(
            "status -e volume hide -e bluetooth hide -e location hide -e alarm hide " +
                "-e sync hide -e tty hide -e eri hide -e mute hide -e speakerphone hide"
        )
        SystemClock.sleep(DIALOG_MS)
    }

    private fun undressTheDevice() {
        demo("exit")
        shell("settings put global hide_error_dialogs 0")
        shell("settings put system accelerometer_rotation 1")
    }

    /**
     * Turns the device upright. Rotation 0 is the natural orientation, and a tablet is landscape in
     * it, so the test tries each rotation and checks the screen.
     */
    private fun standUpright() {
        shell("settings put system accelerometer_rotation 0")
        for (rotation in 0..3) {
            shell("settings put system user_rotation $rotation")
            SystemClock.sleep(ROTATE_MS)
            val shot = instrumentation.uiAutomation.takeScreenshot() ?: continue
            val upright = shot.height > shot.width
            shot.recycle()
            if (upright) return
        }
        throw AssertionError("no rotation stands this device upright")
    }

    private companion object {
        val DEVICES = listOf("phone", "tablet")
        /** The screens, in the order of the store (see `screenshots/README.md`). */
        val SCREENS = ScreenshotMode.SCENES.keys.sorted()
        const val SETTLE_MS = 2500L
        const val RESTART_MS = 6000L
        const val ROTATE_MS = 1500L
        const val DIALOG_MS = 500L
        const val FOCUS_TIMEOUT_MS = 30_000L
        const val BLACK_TRIES = 5
    }
}
