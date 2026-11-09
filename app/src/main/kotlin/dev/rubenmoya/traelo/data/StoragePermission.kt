package dev.rubenmoya.traelo.data

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings

/** Permiso para escribir en carpetas normales del movil (donde luego las lee Godot). */
object StoragePermission {
    /** API 26 a 29: los permisos normales de lectura y escritura. */
    val legacyPermissions: Array<String> = arrayOf(
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.WRITE_EXTERNAL_STORAGE,
    )

    val needsSettingsScreen: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    fun isGranted(context: Context): Boolean =
        if (needsSettingsScreen) {
            Environment.isExternalStorageManager()
        } else {
            legacyPermissions.all {
                context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
            }
        }

    /** Pantalla de ajustes donde se da "acceso a todos los archivos" (API 30+). */
    fun settingsIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        )

    /** Por si el movil no tiene la pantalla anterior: la lista general de apps con ese permiso. */
    fun fallbackSettingsIntent(): Intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
}
