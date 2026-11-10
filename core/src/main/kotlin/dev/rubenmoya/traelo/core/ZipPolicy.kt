package dev.rubenmoya.traelo.core

import kotlin.math.ceil

/** Decide si una tanda se baja con el zip de la rama o archivo a archivo. */
fun interface ZipPolicy {
    /**
     * [files] y [bytes] son lo que hay que bajar; [zipBytes] lo que pesa la rama entera (el zip
     * siempre trae todo, aunque luego solo se saque lo necesario).
     */
    fun useZip(files: Int, bytes: Long, zipBytes: Long): Boolean

    companion object {
        /** Umbral fijo por numero de archivos (sirve para los tests). */
        fun atLeast(files: Int) = ZipPolicy { count, _, _ -> count >= files }

        val NEVER = ZipPolicy { _, _, _ -> false }

        val DEFAULT: ZipPolicy = CostModelZipPolicy()
    }
}

/**
 * Compara el tiempo estimado de las dos formas. Archivo a archivo cuesta una ida y vuelta por cada
 * tanda de [parallelism] peticiones mas los bytes que hacen falta; el zip cuesta lo que tarda
 * GitHub en empezar a mandarlo mas los bytes de toda la rama. Solo se elige el zip si gana con
 * margen ([zipMargin]), porque archivo a archivo se puede retomar mejor si se corta.
 *
 * Por encima de [maxFileRequests] archivos siempre zip: GitHub corta a quien pasa de unas 900
 * peticiones por minuto (limite secundario), y bajar mil archivos sueltos se acercaria.
 *
 * Los valores por defecto salen del benchmark (`./gradlew :core:benchmark -PbenchArgs="120 8 crossover"`)
 * con un repo de Godot de 3000 archivos: el zip solo compensa en la primera descarga o cuando
 * cambia casi todo.
 */
class CostModelZipPolicy(
    private val requestMs: Double = 150.0,
    private val bytesPerSecond: Double = 4.0 * 1024 * 1024,
    private val zipStartMs: Double = 2_000.0,
    private val parallelism: Int = 24,
    private val minFiles: Int = 300,
    private val maxFileRequests: Int = 700,
    private val zipMargin: Double = 0.8,
) : ZipPolicy {
    fun filesMs(files: Int, bytes: Long): Double =
        ceil(files / parallelism.toDouble()) * requestMs + bytes * 1000.0 / bytesPerSecond

    fun zipMs(zipBytes: Long): Double = zipStartMs + requestMs + zipBytes * 1000.0 / bytesPerSecond

    override fun useZip(files: Int, bytes: Long, zipBytes: Long): Boolean =
        files > maxFileRequests || (files >= minFiles && zipMs(zipBytes) < filesMs(files, bytes) * zipMargin)
}
