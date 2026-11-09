package dev.rubenmoya.traelo.ui

import dev.rubenmoya.traelo.R
import dev.rubenmoya.traelo.core.GitHubException
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Que boton extra ofrecer junto a un error. */
enum class ErrorAction { GO_TO_SETTINGS, GRANT_PERMISSION }

/** Un error ya traducido a una frase en español con qué hacer. Nunca lleva stack trace. */
data class ErrorInfo(val text: UiText, val action: ErrorAction? = null, val offline: Boolean = false)

fun defaultTimeFormat(epochSec: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epochSec * 1000))

/**
 * Traduce una excepcion a una frase para la persona. Pura: [formatTime] se inyecta para poder
 * probarla. No incluye el mensaje original de la excepcion (por si acaso), solo frases propias.
 * Las cancelaciones de corrutina no se traducen: quien llame debe relanzarlas antes.
 */
fun describeError(error: Throwable, formatTime: (Long) -> String = ::defaultTimeFormat): ErrorInfo =
    when (error) {
        is GitHubException.Unauthorized ->
            ErrorInfo(UiText.of(R.string.err_unauthorized), ErrorAction.GO_TO_SETTINGS)

        is GitHubException.NotFound -> ErrorInfo(UiText.of(R.string.err_not_found))

        is GitHubException.RateLimited -> {
            val reset = error.resetAtEpochSec
            if (reset != null) {
                ErrorInfo(UiText.of(R.string.err_rate_limited_at, formatTime(reset)))
            } else {
                ErrorInfo(UiText.of(R.string.err_rate_limited))
            }
        }

        is GitHubException.Network -> ErrorInfo(UiText.of(R.string.err_network), offline = true)

        is GitHubException.Http -> ErrorInfo(UiText.of(R.string.err_http, error.code))

        is GitHubException.Integrity -> ErrorInfo(UiText.of(R.string.err_integrity))

        is GitHubException.TooBig -> ErrorInfo(UiText.of(R.string.err_too_big))

        is IOException -> ErrorInfo(UiText.of(R.string.err_io), ErrorAction.GRANT_PERMISSION)

        else -> ErrorInfo(UiText.of(R.string.err_unknown))
    }
