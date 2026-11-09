package dev.rubenmoya.traelo.ui

import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Fecha corta ("12 nov 2026") a partir de una fecha ISO de GitHub; vacio si no se puede leer. */
fun formatShortDate(
    iso: String?,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): String {
    val instant = parseInstant(iso) ?: return ""
    return DateTimeFormatter.ofPattern("d MMM yyyy", locale).withZone(zone).format(instant)
}

/** Fecha y hora corta a partir de milisegundos. */
fun formatDateTime(
    millis: Long,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): String =
    DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", locale).withZone(zone)
        .format(Instant.ofEpochMilli(millis))

private fun parseInstant(iso: String?): Instant? {
    if (iso.isNullOrBlank()) return null
    return try {
        Instant.parse(iso)
    } catch (e: Exception) {
        try {
            java.time.OffsetDateTime.parse(iso).toInstant()
        } catch (e2: Exception) {
            null
        }
    }
}

/** Las 7 primeras letras de un sha de git. */
fun shortSha(sha: String): String = sha.take(7)

/** Problemas posibles al escribir la carpeta base. */
enum class BaseDirProblem { EMPTY, NOT_ABSOLUTE, HAS_DOTDOT }

/** Validacion sencilla de la carpeta base: ruta absoluta, sin ".." y no vacia. */
fun validateBaseDir(text: String): BaseDirProblem? {
    val clean = text.trim()
    return when {
        clean.isEmpty() -> BaseDirProblem.EMPTY
        !clean.startsWith("/") -> BaseDirProblem.NOT_ABSOLUTE
        clean.split("/").any { it == ".." } -> BaseDirProblem.HAS_DOTDOT
        else -> null
    }
}

/** Ruta normalizada sin barra final (salvo la raiz). */
fun normalizeBaseDir(text: String): String {
    val clean = text.trim().trimEnd('/')
    return if (clean.isEmpty()) "/" else File(clean).path
}
