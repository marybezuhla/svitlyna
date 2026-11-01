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

    fun ask(activity: FragmentActivity, onOk: () -> Unit) {
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onOk()
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(activity.getString(R.string.lock_title))
            .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
            .build()
        runCatching { prompt.authenticate(info) }
    }
}
