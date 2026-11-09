package dev.rubenmoya.traelo.ui

import dev.rubenmoya.traelo.core.RemoteRepo

/** La lista dividida en favoritos (arriba, en grande) y el resto. Mantiene el orden de entrada. */
data class RepoSections(val favorites: List<RemoteRepo>, val others: List<RemoteRepo>)

fun splitRepos(repos: List<RemoteRepo>, favorites: Set<String>): RepoSections {
    val (fav, rest) = repos.partition { it.ref.key in favorites }
    return RepoSections(fav, rest)
}

/** Con token de "solo repos publicos" GitHub no manda ningun privado: lo avisamos. */
fun looksPublicOnly(repos: List<RemoteRepo>): Boolean = repos.isNotEmpty() && repos.none { it.isPrivate }

/** Marca o desmarca un favorito. */
fun toggled(favorites: Set<String>, key: String): Set<String> =
    if (key in favorites) favorites - key else favorites + key
