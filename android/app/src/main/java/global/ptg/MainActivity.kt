package global.ptg

import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import global.ptg.ui.App
import global.ptg.ui.LockGate
import global.ptg.ui.PtgTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Hold the splash until settings load, so the theme never flashes.
        var ready = false
        installSplashScreen().setKeepOnScreenCondition { !ready }
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val settings by container.settings.data.collectAsStateWithLifecycle(initialValue = null)
            val s = settings ?: return@setContent
            ready = true
            PtgTheme(s) { LockGate(s) { App(s) } }
        }
    }

    /**
     * Ask for the fastest refresh rate the current display offers at its current resolution:
     * 144 Hz on one phone, 120 or 90 on another, a no-op on a 60 Hz screen. Nothing here is
     * device-specific; the modes come from the display itself.
     *
     * Many OEM skins hold apps at 60 Hz unless they ask. This is a preference, not a lock: the
     * system still lowers the rate for battery saver, heat, or the user's own refresh setting,
     * and Compose draws nothing while idle, so the cost is only paid while something moves.
     */
    private fun preferHighestRefreshRate() {
        val display = display ?: return
        val current = display.mode
        val best = display.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .maxByOrNull { it.refreshRate } ?: return
        if (window.attributes.preferredDisplayModeId != best.modeId) {
            window.attributes = window.attributes.apply { preferredDisplayModeId = best.modeId }
        }
    }

    // Re-pick when the display changes under us: a foldable switching screens, the user changing
    // resolution, or the window moving to an external display.
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == display?.displayId) preferHighestRefreshRate()
        }
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
    }

    override fun onStart() {
        super.onStart()
        getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
        preferHighestRefreshRate()
    }

    override fun onStop() {
        getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener)
        super.onStop()
    }
}
