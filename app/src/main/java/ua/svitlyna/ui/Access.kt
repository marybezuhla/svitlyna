package ua.svitlyna.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.content.ContextCompat

/** How much of the gallery the app may see. */
enum class AccessLevel { NONE, SELECTED, ALL }

object Access {
    /** What to ask for: photos and videos only; on Android 14+ the person may pick just some. */
    fun permissionsToRequest(): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            Manifest.permission.ACCESS_MEDIA_LOCATION,
        )
        Build.VERSION.SDK_INT >= 33 -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.ACCESS_MEDIA_LOCATION,
        )
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.ACCESS_MEDIA_LOCATION)
    }

    fun level(context: Context): AccessLevel {
        fun has(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
        return when {
            Build.VERSION.SDK_INT >= 33 &&
                (has(Manifest.permission.READ_MEDIA_IMAGES) || has(Manifest.permission.READ_MEDIA_VIDEO)) -> AccessLevel.ALL
            Build.VERSION.SDK_INT >= 34 && has(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> AccessLevel.SELECTED
            Build.VERSION.SDK_INT < 33 && has(Manifest.permission.READ_EXTERNAL_STORAGE) -> AccessLevel.ALL
            else -> AccessLevel.NONE
        }
    }

    /** "Media management" (Android 12+): changes without a confirmation window each time. */
    fun mediaManagementAvailable() = Build.VERSION.SDK_INT >= 31

    fun canManageMedia(context: Context): Boolean =
        Build.VERSION.SDK_INT >= 31 && MediaStore.canManageMedia(context)

    fun mediaManagementSettings(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA, Uri.parse("package:${context.packageName}"))

    /** The app's page in Android settings (permissions, language). */
    fun appSettings(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))

    /** Per-app language (Android 13+). */
    fun languageSettings(context: Context): Intent? =
        if (Build.VERSION.SDK_INT >= 33) {
            Intent(Settings.ACTION_APP_LOCALE_SETTINGS, Uri.parse("package:${context.packageName}"))
        } else {
            null
        }
}
