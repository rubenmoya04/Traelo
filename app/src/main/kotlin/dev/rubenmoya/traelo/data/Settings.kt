package dev.rubenmoya.traelo.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Environment
import java.io.File

/** Ajustes sencillos de la app (nada secreto aqui: el token va en [TokenStore]). */
class Settings(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Carpeta donde se crean los proyectos, una subcarpeta por repo. */
    var baseDir: String
        get() = prefs.getString(KEY_BASE_DIR, null) ?: defaultBaseDir()
        set(value) = prefs.edit().putString(KEY_BASE_DIR, value.trim()).apply()

    /** Abrir el proyecto directamente en el editor de Godot (experimental). */
    var launchDirect: Boolean
        get() = prefs.getBoolean(KEY_LAUNCH_DIRECT, true)
        set(value) = prefs.edit().putBoolean(KEY_LAUNCH_DIRECT, value).apply()

    /** Usuario de GitHub del token, guardado al validarlo para enseñarlo sin red. */
    var userLogin: String?
        get() = prefs.getString(KEY_USER_LOGIN, null)
        set(value) = prefs.edit().apply {
            if (value == null) remove(KEY_USER_LOGIN) else putString(KEY_USER_LOGIN, value)
        }.apply()

    /** Claves ([dev.rubenmoya.traelo.core.RepoRef.key]) de los repos favoritos. */
    var favorites: Set<String>
        get() = prefs.getStringSet(KEY_FAVORITES, null)?.toSet() ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_FAVORITES, value.toSet()).apply()

    fun resetBaseDir() {
        prefs.edit().remove(KEY_BASE_DIR).apply()
    }

    companion object {
        private const val PREFS_NAME = "traelo_settings"
        private const val KEY_BASE_DIR = "base_dir"
        private const val KEY_LAUNCH_DIRECT = "launch_direct"
        private const val KEY_USER_LOGIN = "user_login"
        private const val KEY_FAVORITES = "favorites"

        @Suppress("DEPRECATION")
        fun defaultBaseDir(): String =
            File(Environment.getExternalStorageDirectory(), "Documents/Traelo").absolutePath
    }
}
