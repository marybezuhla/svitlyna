package ua.svitlyna.ui

import android.app.KeyguardManager
import android.os.Build
import android.view.WindowManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import ua.svitlyna.R

/**
 * App lock (from Iris Gallery's idea): opening Svitlyna asks for the fingerprint or the phone's own
 * PIN — Android checks it, Svitlyna never sees or stores a PIN. Coming back within half a minute
 * doesn't ask again. While it is on, the app is hidden in the recent-apps preview.
 */
object AppLock {
    const val GRACE_MS = 30_000L

    /*
     * Process memory only: when Android ends the process, both are gone and the app is locked again.
     * Kept here, not in the window, so a closed and reopened window (Back on Android 11, a theme
     * change) still knows when the person left.
     */
    @Volatile
    var unlocked = false

    @Volatile
    var leftAt = 0L

    fun shouldLock(lockOn: Boolean): Boolean =
        lockOn && (!unlocked || (leftAt > 0 && android.os.SystemClock.elapsedRealtime() - leftAt > GRACE_MS))

    /** The window with a prompt open: one prompt at a time (the PIN screen sends the app away and back). */
    private var askingIn: java.lang.ref.WeakReference<FragmentActivity>? = null

    fun deviceSecure(activity: FragmentActivity): Boolean =
        activity.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

    fun applyRecents(activity: FragmentActivity, on: Boolean) {
        if (Build.VERSION.SDK_INT >= 33) {
            activity.setRecentsScreenshotEnabled(!on)
        } else if (on) {
            // Android 11–12 have nothing finer: the window is marked secure.
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    /**
     * Asks for the fingerprint or PIN. If the phone has no screen lock any more (removed in Android
     * settings), the lock can't work: [onNoScreenLock] turns it off instead of locking the person out.
     */
    fun ask(activity: FragmentActivity, onNoScreenLock: () -> Unit = {}, onOk: () -> Unit) {
        if (!deviceSecure(activity)) {
            onNoScreenLock()
            return
        }
        // A prompt of a window that is gone never reports back: only a live, same window counts.
        val open = askingIn?.get()
        if (open === activity && !activity.isDestroyed) return
        askingIn = java.lang.ref.WeakReference(activity)
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    askingIn = null
                    onOk()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    // Cancelled or failed: the lock screen's button asks again.
                    askingIn = null
                }
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(activity.getString(R.string.lock_title))
            .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
            .build()
        runCatching { prompt.authenticate(info) }.onFailure { askingIn = null }
    }
}
