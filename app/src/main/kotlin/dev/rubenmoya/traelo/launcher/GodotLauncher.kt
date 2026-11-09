package dev.rubenmoya.traelo.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import java.io.File

sealed interface LaunchResult {
    /** Se abrio el editor con `--path` (experimental: no sabemos si el editor lo respeta). */
    data object LaunchedWithProject : LaunchResult

    /** Se abrio el editor normal, sin proyecto. */
    data object LaunchedPlain : LaunchResult

    /** Se intento abrir con proyecto, fallo, y se abrio el editor normal. */
    data object FellBackToPlain : LaunchResult

    /** No hay editor de Godot para Android instalado. */
    data object NotInstalled : LaunchResult

    /** Esta instalado pero no se pudo abrir. */
    data object Failed : LaunchResult
}

/** Abre el editor de Godot para Android (paquete que empieza por `org.godotengine.editor`). */
class GodotLauncher(context: Context) {
    private val context: Context = context.applicationContext

    /** Nombre de paquete del editor instalado, o null. Prefiere la version que no es debug. */
    fun findEditorPackage(): String? {
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val infos = if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.queryIntentActivities(
                query,
                PackageManager.ResolveInfoFlags.of(0),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.queryIntentActivities(query, 0)
        }
        return pickEditorPackage(infos.map { it.activityInfo.packageName })
    }

    /**
     * Abre Godot. Con [projectDir] y [direct], pasa `command_line_params` para que abra ese
     * proyecto; si eso lanza una excepcion, abre el editor normal.
     */
    fun launch(projectDir: File?, direct: Boolean): LaunchResult {
        val pkg = findEditorPackage() ?: return LaunchResult.NotInstalled
        val wantsProject = direct && projectDir != null
        if (wantsProject) {
            try {
                val intent = baseIntent(pkg) ?: return LaunchResult.Failed
                intent.putExtra(EXTRA_COMMAND_LINE, arrayOf("--path", projectDir!!.absolutePath, "--editor"))
                context.startActivity(intent)
                return LaunchResult.LaunchedWithProject
            } catch (e: Exception) {
                // Seguimos abajo con el lanzamiento normal.
            }
        }
        return try {
            val intent = baseIntent(pkg) ?: return LaunchResult.Failed
            context.startActivity(intent)
            if (wantsProject) LaunchResult.FellBackToPlain else LaunchResult.LaunchedPlain
        } catch (e: Exception) {
            LaunchResult.Failed
        }
    }

    private fun baseIntent(pkg: String): Intent? =
        context.packageManager.getLaunchIntentForPackage(pkg)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    companion object {
        const val EDITOR_PREFIX = "org.godotengine.editor"
        const val EXTRA_COMMAND_LINE = "command_line_params"

        /** Elige el editor entre paquetes instalados: el que no acaba en `.debug`, si hay. */
        fun pickEditorPackage(packages: List<String>): String? {
            val editors = packages.filter { it.startsWith(EDITOR_PREFIX) }.distinct().sorted()
            return editors.firstOrNull { !it.endsWith(".debug") } ?: editors.firstOrNull()
        }
    }
}
