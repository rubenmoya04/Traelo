package dev.rubenmoya.traelo.ui

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/** Un texto de pantalla sin resolver: el ViewModel lo crea y la vista lo convierte en String. */
sealed interface UiText {
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText

    data class Plural(
        @PluralsRes val id: Int,
        val count: Int,
        val args: List<Any> = emptyList(),
    ) : UiText

    data class Joined(val parts: List<UiText>, val separator: String = " ") : UiText

    companion object {
        fun of(@StringRes id: Int, vararg args: Any): UiText = Res(id, args.toList())

        fun plural(@PluralsRes id: Int, count: Int, vararg args: Any): UiText =
            Plural(id, count, if (args.isEmpty()) listOf(count) else args.toList())
    }
}

fun UiText.asString(context: Context): String = when (this) {
    is UiText.Res -> context.getString(id, *args.toTypedArray())
    is UiText.Plural -> context.resources.getQuantityString(id, count, *args.toTypedArray())
    is UiText.Joined -> parts.joinToString(separator) { it.asString(context) }
}

@Composable
fun UiText.asString(): String = asString(LocalContext.current)
