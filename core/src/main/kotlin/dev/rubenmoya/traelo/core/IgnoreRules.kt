package dev.rubenmoya.traelo.core

/**
 * Reglas del archivo `.traeloignore` de la raiz del repo: lo que Traelo no baja al movil.
 *
 * Una regla por linea. Lineas vacias y las que empiezan por `#` no cuentan.
 * - `carpeta/` ignora esa carpeta entera (desde la raiz).
 * - `*.md` ignora por nombre en cualquier carpeta (sin `/` en la regla).
 * - Un asterisco vale cualquier nombre dentro de una carpeta y dos asteriscos seguidos valen
 *   cualquier profundidad (por ejemplo, la carpeta docs seguida de asterisco punto pdf).
 * - Cualquier otra cosa es una ruta exacta desde la raiz.
 *
 * Lo ignorado no se baja y deja de estar gestionado, pero nunca se borra del movil: si ya estaba,
 * se queda donde esta.
 */
class IgnoreRules private constructor(private val rules: List<Regex>) {
    fun ignores(path: String): Boolean = path != FILE_NAME && rules.any { it.matches(path) }

    val isEmpty: Boolean get() = rules.isEmpty()

    companion object {
        const val FILE_NAME = ".traeloignore"

        val NONE = IgnoreRules(emptyList())

        fun parse(text: String): IgnoreRules =
            IgnoreRules(text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
                .map(::toRegex).toList())

        private fun toRegex(rule: String): Regex {
            val clean = rule.removePrefix("/")
            if (clean.endsWith("/")) return Regex(Regex.escape(clean) + ".*")
            val body = globToRegex(clean)
            return if ('/' in clean) Regex(body) else Regex("(.*/)?$body")
        }

        private fun globToRegex(glob: String): String {
            val out = StringBuilder()
            var i = 0
            while (i < glob.length) {
                val c = glob[i]
                when {
                    glob.startsWith("**", i) -> { out.append(".*"); i += 2; continue }
                    c == '*' -> out.append("[^/]*")
                    c == '?' -> out.append("[^/]")
                    else -> out.append(Regex.escape(c.toString()))
                }
                i++
            }
            return out.toString()
        }
    }
}
