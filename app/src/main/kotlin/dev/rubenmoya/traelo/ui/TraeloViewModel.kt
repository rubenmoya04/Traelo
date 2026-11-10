package dev.rubenmoya.traelo.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.rubenmoya.traelo.R
import dev.rubenmoya.traelo.core.Branch
import dev.rubenmoya.traelo.core.CheckResult
import dev.rubenmoya.traelo.core.ConflictFile
import dev.rubenmoya.traelo.core.FileManifestStore
import dev.rubenmoya.traelo.core.HttpGitHubApi
import dev.rubenmoya.traelo.core.LocalChanges
import dev.rubenmoya.traelo.core.LocalRepoRemover
import dev.rubenmoya.traelo.core.RemoteRepo
import dev.rubenmoya.traelo.core.RemoveResult
import dev.rubenmoya.traelo.core.RepoRef
import dev.rubenmoya.traelo.core.SyncEngine
import dev.rubenmoya.traelo.core.SyncProgress
import dev.rubenmoya.traelo.core.SyncState
import dev.rubenmoya.traelo.data.RepoListCache
import dev.rubenmoya.traelo.data.RepoStateStore
import dev.rubenmoya.traelo.data.Settings
import dev.rubenmoya.traelo.data.StoragePermission
import dev.rubenmoya.traelo.data.TokenStore
import dev.rubenmoya.traelo.launcher.GodotLauncher
import dev.rubenmoya.traelo.launcher.LaunchResult
import dev.rubenmoya.traelo.launcher.ProjectFinder
import dev.rubenmoya.traelo.launcher.ProjectLocation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

sealed interface Screen {
    data object Welcome : Screen

    data object Repos : Screen

    data class Repo(val ref: RepoRef) : Screen

    data object Settings : Screen
}

data class SessionState(
    val hasToken: Boolean,
    val login: String? = null,
    val validating: Boolean = false,
    val error: ErrorInfo? = null,
)

data class ReposState(
    val loaded: Boolean = false,
    /** La lista viene de la cache del movil y todavia no se ha refrescado. */
    val fromCache: Boolean = false,
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val repos: List<RemoteRepo> = emptyList(),
    val error: ErrorInfo? = null,
)

sealed interface RepoStatus {
    data object NotSynced : RepoStatus

    /** Ya se trajo, pero todavia no se ha comprobado contra GitHub. */
    data object SyncedUnchecked : RepoStatus

    data object Checking : RepoStatus

    data object UpToDate : RepoStatus

    data class Outdated(val changes: Int) : RepoStatus

    data class Failed(val error: ErrorInfo) : RepoStatus
}

sealed interface SyncUi {
    data object Idle : SyncUi

    data class Running(val progress: SyncProgress?) : SyncUi
}

data class DetailState(
    val repo: RemoteRepo,
    /** Lo ultimo que se trajo de este repo, null si nunca. */
    val saved: SyncState?,
    /** Rama elegida (puede ser distinta de la traida). */
    val branch: String,
    val check: CheckResult? = null,
    val checking: Boolean = false,
    val error: ErrorInfo? = null,
    val branches: List<Branch>? = null,
    val branchesLoading: Boolean = false,
    val branchesError: ErrorInfo? = null,
    val sync: SyncUi = SyncUi.Idle,
    val notice: UiText? = null,
    val project: ProjectLocation? = null,
    val projectChecked: Boolean = false,
    val findingLocal: Boolean = false,
    val deleting: Boolean = false,
    /** Cambios locales encontrados, pendientes de confirmar la restauracion. */
    val pendingRestore: LocalChanges? = null,
    /** Carpeta donde se traera (o donde ya esta). */
    val localDir: String = "",
    /** Archivos del movil que se guardaron aparte en la ultima descarga. */
    val conflicts: List<ConflictFile> = emptyList(),
    val conflictDir: String? = null,
    /** "Traer y abrir" se quedo sin abrir Godot para ensenar antes los conflictos. */
    val launchPending: Boolean = false,
) {
    val isRunning: Boolean get() = sync is SyncUi.Running
}

/** Estado de un repo para la lista: junta lo guardado con lo comprobado. */
fun statusOf(
    ref: RepoRef,
    syncStates: Map<String, SyncState>,
    checks: Map<String, RepoStatus>,
): RepoStatus =
    if (syncStates[ref.key] == null) RepoStatus.NotSynced else checks[ref.key] ?: RepoStatus.SyncedUnchecked

fun CheckResult.toStatus(): RepoStatus = when (this) {
    is CheckResult.UpToDate -> RepoStatus.UpToDate
    is CheckResult.Outdated -> RepoStatus.Outdated(counts.changes)
    is CheckResult.NeverSynced -> RepoStatus.NotSynced
}

class TraeloViewModel(app: Application) : AndroidViewModel(app) {
    private val tokenStore = TokenStore(app)
    private val settings = Settings(app)
    private val stateStore = RepoStateStore(File(app.filesDir, "repo_states.json"))
    private val repoCache = RepoListCache(File(app.cacheDir, "repos.json"))
    private val launcher = GodotLauncher(app)

    @Volatile
    private var tokenCache: String? = null

    private val api = HttpGitHubApi(tokenProvider = {
        tokenCache ?: tokenStore.read().also { tokenCache = it } ?: ""
    })
    private val engine = SyncEngine(api, FileManifestStore(File(app.filesDir, "manifests")))

    // Navegacion: pila simple de pantallas.
    private val _stack = MutableStateFlow<List<Screen>>(
        listOf(if (tokenStore.has()) Screen.Repos else Screen.Welcome),
    )
    val stack: StateFlow<List<Screen>> = _stack.asStateFlow()

    private val _session = MutableStateFlow(SessionState(tokenStore.has(), settings.userLogin))
    val session: StateFlow<SessionState> = _session.asStateFlow()

    private val _storageGranted = MutableStateFlow(StoragePermission.isGranted(app))
    val storageGranted: StateFlow<Boolean> = _storageGranted.asStateFlow()

    private val _repos = MutableStateFlow(initialRepos())
    val repos: StateFlow<ReposState> = _repos.asStateFlow()

    private val _syncStates = MutableStateFlow(stateStore.all())
    val syncStates: StateFlow<Map<String, SyncState>> = _syncStates.asStateFlow()

    private val _checks = MutableStateFlow<Map<String, RepoStatus>>(emptyMap())
    val checks: StateFlow<Map<String, RepoStatus>> = _checks.asStateFlow()

    private val _detail = MutableStateFlow<DetailState?>(null)
    val detail: StateFlow<DetailState?> = _detail.asStateFlow()

    private val _baseDir = MutableStateFlow(settings.baseDir)
    val baseDir: StateFlow<String> = _baseDir.asStateFlow()

    private val _launchDirect = MutableStateFlow(settings.launchDirect)
    val launchDirect: StateFlow<Boolean> = _launchDirect.asStateFlow()

    private val _favorites = MutableStateFlow(settings.favorites)
    val favorites: StateFlow<Set<String>> = _favorites.asStateFlow()

    private val _godotMissing = MutableStateFlow(false)
    val godotMissing: StateFlow<Boolean> = _godotMissing.asStateFlow()

    private val _messages = MutableSharedFlow<UiText>(extraBufferCapacity = 8)
    val messages: SharedFlow<UiText> = _messages.asSharedFlow()

    /** Pantalla encendida mientras dura una descarga. */
    val keepScreenOn: StateFlow<Boolean> = _detail
        .map { it?.isRunning == true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private var listJob: Job? = null
    private var checkAllJob: Job? = null
    private var detailCheckJob: Job? = null
    private var syncJob: Job? = null

    init {
        if (tokenStore.has() && settings.userLogin == null) fetchLoginSilently()
    }

    /** La ultima lista guardada se ensena al momento; luego se refresca. */
    private fun initialRepos(): ReposState {
        if (!tokenStore.has()) return ReposState()
        val cached = repoCache.load() ?: return ReposState()
        return ReposState(loaded = true, fromCache = true, repos = cached)
    }

    // ---------------------------------------------------------------- navegacion

    fun back() {
        val stack = _stack.value
        if (stack.size <= 1) return
        if (stack.last() is Screen.Repo) closeDetail()
        _stack.value = stack.dropLast(1)
    }

    fun openSettings() = push(Screen.Settings)

    private fun push(screen: Screen) {
        _stack.update { it + screen }
    }

    fun finishWelcome() {
        _stack.value = listOf(Screen.Repos)
    }

    // ---------------------------------------------------------------- sesion y permiso

    fun refreshPermission() {
        _storageGranted.value = StoragePermission.isGranted(getApplication())
    }

    fun clearTokenError() {
        _session.update { it.copy(error = null) }
    }

    /** Guarda el token, lo prueba con `currentUser()` y, si no vale, lo borra. */
    fun validateToken(raw: String) {
        val clean = raw.trim()
        if (clean.isEmpty() || _session.value.validating) return
        _session.update { it.copy(validating = true, error = null) }
        viewModelScope.launch {
            try {
                tokenStore.save(clean)
                tokenCache = clean
                val login = withContext(Dispatchers.IO) { api.currentUser() }
                settings.userLogin = login
                _session.value = SessionState(hasToken = true, login = login)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                tokenStore.clear()
                tokenCache = null
                val info = if (e is IllegalStateException) {
                    ErrorInfo(UiText.of(R.string.err_token_store))
                } else {
                    describeError(e)
                }
                _session.value = SessionState(
                    hasToken = false,
                    error = if (info.action == ErrorAction.GO_TO_SETTINGS) {
                        ErrorInfo(UiText.of(R.string.err_token_rejected))
                    } else {
                        info.copy(action = null)
                    },
                )
            }
        }
    }

    private fun fetchLoginSilently() {
        viewModelScope.launch {
            try {
                val login = withContext(Dispatchers.IO) { api.currentUser() }
                settings.userLogin = login
                _session.update { it.copy(login = login) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Sin red o token malo: ya se vera al cargar los repos.
            }
        }
    }

    fun logout() {
        listJob?.cancel()
        checkAllJob?.cancel()
        detailCheckJob?.cancel()
        syncJob?.cancel()
        tokenStore.clear()
        tokenCache = null
        settings.userLogin = null
        _session.value = SessionState(hasToken = false)
        repoCache.clear()
        _repos.value = ReposState()
        _checks.value = emptyMap()
        _detail.value = null
        _stack.value = listOf(Screen.Welcome)
    }

    // ---------------------------------------------------------------- ajustes

    fun saveBaseDir(text: String) {
        val value = normalizeBaseDir(text)
        settings.baseDir = value
        _baseDir.value = value
    }

    fun resetBaseDir() {
        settings.resetBaseDir()
        _baseDir.value = settings.baseDir
    }

    fun setLaunchDirect(value: Boolean) {
        settings.launchDirect = value
        _launchDirect.value = value
    }

    // ---------------------------------------------------------------- lista de repos

    fun ensureReposLoaded() {
        val state = _repos.value
        when {
            state.loading || state.refreshing -> Unit
            !state.loaded -> loadRepos(refreshing = false)
            state.fromCache -> loadRepos(refreshing = true)
        }
    }

    /** Deslizar para refrescar: recarga la lista y comprueba los repos ya traidos. */
    fun refresh() = loadRepos(refreshing = true)

    fun retryRepos() = loadRepos(refreshing = false)

    private fun loadRepos(refreshing: Boolean) {
        if (listJob?.isActive == true) return
        _repos.update {
            it.copy(loading = !refreshing && !it.loaded, refreshing = refreshing, error = null)
        }
        listJob = viewModelScope.launch {
            try {
                val list = withContext(Dispatchers.IO) { api.listRepos() }
                    .sortedWith(compareByDescending<RemoteRepo> { it.pushedAt ?: "" })
                _repos.value = ReposState(loaded = true, repos = list)
                withContext(Dispatchers.IO) { runCatching { repoCache.save(list) } }
                checkSyncedRepos(list)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val info = describeError(e)
                _repos.update {
                    it.copy(loading = false, refreshing = false, error = info, loaded = it.loaded, fromCache = false)
                }
            }
        }
    }

    /** Comprueba solo los repos que ya se trajeron, de 4 en 4 (sin cambios GitHub responde 304). */
    private fun checkSyncedRepos(list: List<RemoteRepo>) {
        val targets = list.filter { stateStore.get(it.ref) != null && !isSyncing(it.ref) }
        if (targets.isEmpty()) return
        checkAllJob?.cancel()
        _checks.update { old -> old + targets.associate { it.ref.key to RepoStatus.Checking } }
        checkAllJob = viewModelScope.launch {
            val semaphore = Semaphore(4)
            coroutineScope {
                targets.map { repo -> async { semaphore.withPermit { checkForList(repo.ref) } } }.awaitAll()
            }
        }
    }

    private suspend fun checkForList(ref: RepoRef) {
        val saved = stateStore.get(ref)
        if (saved == null) {
            setCheck(ref, RepoStatus.NotSynced)
            return
        }
        try {
            setCheck(ref, engine.check(ref, saved.branch, saved).toStatus())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            setCheck(ref, RepoStatus.Failed(describeError(e)))
        }
    }

    private fun setCheck(ref: RepoRef, status: RepoStatus) {
        _checks.update { it + (ref.key to status) }
    }

    private fun isSyncing(ref: RepoRef): Boolean {
        val d = _detail.value
        return d != null && d.repo.ref == ref && d.isRunning
    }

    // ---------------------------------------------------------------- detalle de un repo

    fun openRepo(ref: RepoRef) {
        val remote = _repos.value.repos.firstOrNull { it.ref == ref }
            ?: RemoteRepo(ref, isPrivate = false, defaultBranch = "main", pushedAt = null)
        val saved = stateStore.get(ref)
        _detail.value = DetailState(
            repo = remote,
            saved = saved,
            branch = saved?.branch ?: remote.defaultBranch,
            localDir = saved?.localDir ?: plannedDir(ref),
        )
        push(Screen.Repo(ref))
        runCheck()
        refreshProject()
    }

    private fun plannedDir(ref: RepoRef): String = File(settings.baseDir, ref.name).path

    private fun closeDetail() {
        detailCheckJob?.cancel()
        syncJob?.cancel()
        _detail.value = null
    }

    private fun updateDetail(ref: RepoRef, change: (DetailState) -> DetailState) {
        _detail.update { d -> if (d != null && d.repo.ref == ref) change(d) else d }
    }

    /** "Solo comprobar" y comprobacion automatica al abrir o cambiar de rama. */
    fun runCheck() {
        val d = _detail.value ?: return
        if (d.isRunning) return
        val ref = d.repo.ref
        val branch = d.branch
        val saved = d.saved
        detailCheckJob?.cancel()
        updateDetail(ref) { it.copy(checking = true, error = null, notice = null) }
        detailCheckJob = viewModelScope.launch {
            try {
                val result = engine.check(ref, branch, saved)
                updateDetail(ref) { if (it.branch == branch) it.copy(check = result, checking = false) else it }
                if (saved != null && saved.branch == branch) setCheck(ref, result.toStatus())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val info = fixAction(describeError(e))
                updateDetail(ref) { if (it.branch == branch) it.copy(checking = false, error = info) else it }
                if (saved != null && saved.branch == branch) setCheck(ref, RepoStatus.Failed(info))
            }
        }
    }

    fun selectBranch(name: String) {
        val d = _detail.value ?: return
        if (d.isRunning || name == d.branch) return
        updateDetail(d.repo.ref) { it.copy(branch = name, check = null, error = null, notice = null) }
        runCheck()
    }

    fun loadBranches() {
        val d = _detail.value ?: return
        if (d.branches != null || d.branchesLoading) return
        val ref = d.repo.ref
        updateDetail(ref) { it.copy(branchesLoading = true, branchesError = null) }
        viewModelScope.launch {
            try {
                val list = withContext(Dispatchers.IO) { api.listBranches(ref) }
                updateDetail(ref) { it.copy(branches = list, branchesLoading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val info = describeError(e)
                updateDetail(ref) { it.copy(branchesLoading = false, branchesError = info) }
            }
        }
    }

    fun refreshProject() {
        val d = _detail.value ?: return
        val ref = d.repo.ref
        val dir = d.saved?.localDir
        if (dir == null) {
            updateDetail(ref) { it.copy(project = null, projectChecked = true) }
            return
        }
        viewModelScope.launch {
            val location = withContext(Dispatchers.IO) { ProjectFinder.find(File(dir)) }
            updateDetail(ref) { it.copy(project = location, projectChecked = true) }
        }
    }

    // ---------------------------------------------------------------- traer

    /**
     * Traer (o cambiar de rama y traer). Con [verifyLocal] repone lo que no coincida en el movil.
     * Con [thenOpen] abre Godot al acabar, salvo que haya archivos guardados aparte: entonces se
     * ensenan primero y Godot espera a que la persona lo pida.
     */
    fun pull(verifyLocal: Boolean = false, thenOpen: Boolean = false) {
        val d = _detail.value ?: return
        if (d.isRunning) return
        val ref = d.repo.ref
        if (!_storageGranted.value) {
            updateDetail(ref) {
                it.copy(error = ErrorInfo(UiText.of(R.string.err_no_permission), ErrorAction.GRANT_PERMISSION))
            }
            return
        }
        detailCheckJob?.cancel()
        val branch = d.branch
        val saved = d.saved
        val localDir = File(saved?.localDir ?: d.localDir)
        updateDetail(ref) {
            it.copy(
                sync = SyncUi.Running(null),
                checking = false,
                notice = null,
                error = null,
                pendingRestore = null,
                conflicts = emptyList(),
                conflictDir = null,
                launchPending = false,
            )
        }
        syncJob = viewModelScope.launch {
            val started = System.currentTimeMillis()
            try {
                val result = engine.pull(
                    ref,
                    branch,
                    localDir,
                    saved,
                    verifyLocal,
                    onConflict = { conflict -> updateDetail(ref) { it.copy(conflicts = it.conflicts + conflict) } },
                ) { progress ->
                    updateDetail(ref) { cur ->
                        if (cur.sync is SyncUi.Running) cur.copy(sync = SyncUi.Running(progress)) else cur
                    }
                }
                withContext(Dispatchers.IO) { stateStore.put(result.newState) }
                _syncStates.value = stateStore.all()
                setCheck(ref, RepoStatus.UpToDate)
                val seconds = ((System.currentTimeMillis() - started) / 1000).toInt()
                updateDetail(ref) {
                    it.copy(
                        saved = result.newState,
                        branch = result.newState.branch,
                        check = CheckResult.UpToDate(result.newState.headSha),
                        sync = SyncUi.Idle,
                        notice = pulledNotice(result.downloaded, result.removed, result.skipped.size, result.ignored, result.bytesDownloaded, seconds),
                        localDir = result.newState.localDir,
                        conflicts = result.conflicts,
                        conflictDir = result.conflictDir,
                        launchPending = thenOpen && result.conflicts.isNotEmpty(),
                    )
                }
                val project = withContext(Dispatchers.IO) { ProjectFinder.find(File(result.newState.localDir)) }
                updateDetail(ref) { it.copy(project = project, projectChecked = true) }
                if (thenOpen && result.conflicts.isEmpty()) launchGodot(project?.dir)
            } catch (e: CancellationException) {
                updateDetail(ref) { it.copy(sync = SyncUi.Idle, notice = UiText.of(R.string.notice_cancelled)) }
                throw e
            } catch (e: Exception) {
                val info = fixAction(describeError(e))
                updateDetail(ref) { it.copy(sync = SyncUi.Idle, error = info) }
            }
        }
    }

    /** Despues de ver los conflictos: abrir Godot como pidio "Traer y abrir". */
    fun openAfterConflicts() {
        updateDetail(_detail.value?.repo?.ref ?: return) { it.copy(launchPending = false) }
        openInGodot()
    }

    fun dismissConflicts() {
        updateDetail(_detail.value?.repo?.ref ?: return) {
            it.copy(conflicts = emptyList(), conflictDir = null, launchPending = false)
        }
    }

    fun cancelPull() {
        syncJob?.cancel()
    }

    /** Boton de restaurar: primero cuenta cuantos archivos del movil no coinciden. */
    fun startRestore() {
        val d = _detail.value ?: return
        val saved = d.saved ?: return
        if (d.isRunning || d.findingLocal) return
        val ref = d.repo.ref
        detailCheckJob?.cancel()
        updateDetail(ref) { it.copy(findingLocal = true, notice = null, error = null) }
        viewModelScope.launch {
            try {
                val changes = engine.findLocalChanges(saved)
                if (changes.isEmpty) {
                    updateDetail(ref) {
                        it.copy(findingLocal = false, notice = UiText.of(R.string.notice_nothing_to_restore))
                    }
                } else {
                    updateDetail(ref) { it.copy(findingLocal = false, pendingRestore = changes) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val info = fixAction(describeError(e))
                updateDetail(ref) { it.copy(findingLocal = false, error = info) }
            }
        }
    }

    // ---------------------------------------------------------------- borrar la copia del movil

    /** Borra la carpeta entera del repo en el movil. En GitHub no se toca nada. */
    fun deleteLocal() {
        val d = _detail.value ?: return
        val saved = d.saved ?: return
        if (d.isRunning || d.deleting) return
        val ref = d.repo.ref
        detailCheckJob?.cancel()
        updateDetail(ref) { it.copy(deleting = true, notice = null, error = null) }
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    LocalRepoRemover.remove(File(saved.localDir), File(settings.baseDir))
                }
                if (result == RemoveResult.Refused) {
                    updateDetail(ref) {
                        it.copy(deleting = false, error = ErrorInfo(UiText.of(R.string.err_delete_refused)))
                    }
                    return@launch
                }
                withContext(Dispatchers.IO) {
                    engine.forget(ref)
                    stateStore.remove(ref)
                }
                _syncStates.value = stateStore.all()
                _checks.update { it - ref.key }
                updateDetail(ref) {
                    it.copy(
                        deleting = false,
                        saved = null,
                        check = null,
                        project = null,
                        localDir = plannedDir(ref),
                        notice = UiText.of(R.string.notice_deleted),
                    )
                }
                runCheck()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updateDetail(ref) {
                    it.copy(deleting = false, error = ErrorInfo(UiText.of(R.string.err_delete_failed)))
                }
            }
        }
    }

    /** Carpeta elegida con el selector para un repo que aun no se ha traido: dentro va una subcarpeta con su nombre. */
    fun chooseRepoParent(path: String?) {
        val d = _detail.value ?: return
        if (d.saved != null || d.isRunning) return
        if (path == null) {
            _messages.tryEmit(UiText.of(R.string.msg_folder_unsupported))
            return
        }
        updateDetail(d.repo.ref) { it.copy(localDir = File(normalizeBaseDir(path), d.repo.ref.name).path) }
    }

    /** Carpeta base elegida con el selector, en Ajustes. */
    fun chooseBaseDir(path: String?) {
        if (path == null) {
            _messages.tryEmit(UiText.of(R.string.msg_folder_unsupported))
            return
        }
        saveBaseDir(path)
    }

    fun confirmRestore() {
        updateDetail(_detail.value?.repo?.ref ?: return) { it.copy(pendingRestore = null) }
        pull(verifyLocal = true)
    }

    fun dismissRestore() {
        updateDetail(_detail.value?.repo?.ref ?: return) { it.copy(pendingRestore = null) }
    }

    // ---------------------------------------------------------------- Godot

    fun openInGodot() {
        val d = _detail.value ?: return
        launchGodot(d.project?.dir)
    }

    private fun launchGodot(projectDir: File?) {
        when (launcher.launch(projectDir, _launchDirect.value)) {
            LaunchResult.NotInstalled -> _godotMissing.value = true
            LaunchResult.Failed -> _messages.tryEmit(UiText.of(R.string.msg_godot_failed))
            LaunchResult.FellBackToPlain -> _messages.tryEmit(UiText.of(R.string.msg_godot_fallback))
            LaunchResult.LaunchedPlain, LaunchResult.LaunchedWithProject -> Unit
        }
    }

    /** Abre en Godot un repo ya traido, sin entrar en su pantalla (botones de favoritos). */
    fun openInGodot(ref: RepoRef) {
        val dir = _syncStates.value[ref.key]?.localDir
        val project = dir?.let { ProjectFinder.find(File(it)) }
        when (launcher.launch(project?.dir, _launchDirect.value)) {
            LaunchResult.NotInstalled -> _godotMissing.value = true
            LaunchResult.Failed -> _messages.tryEmit(UiText.of(R.string.msg_godot_failed))
            LaunchResult.FellBackToPlain -> _messages.tryEmit(UiText.of(R.string.msg_godot_fallback))
            LaunchResult.LaunchedPlain, LaunchResult.LaunchedWithProject -> Unit
        }
    }

    /** Abre el repo y empieza a traerlo (botones de un favorito). Con [thenOpen] abre Godot al acabar. */
    fun openAndPull(ref: RepoRef, thenOpen: Boolean = false) {
        openRepo(ref)
        pull(thenOpen = thenOpen)
    }

    fun toggleFavorite(ref: RepoRef) {
        val next = toggled(_favorites.value, ref.key)
        settings.favorites = next
        _favorites.value = next
    }

    fun dismissGodotMissing() {
        _godotMissing.value = false
    }

    // ---------------------------------------------------------------- utilidades

    /** El permiso solo se pide si de verdad falta. */
    private fun fixAction(info: ErrorInfo): ErrorInfo =
        if (info.action == ErrorAction.GRANT_PERMISSION && _storageGranted.value) info.copy(action = null) else info

    private fun pulledNotice(downloaded: Int, removed: Int, skipped: Int, ignored: Int, bytes: Long, seconds: Int): UiText {
        if (downloaded == 0 && removed == 0) return UiText.of(R.string.notice_nothing_new)
        val parts = ArrayList<UiText>()
        parts += UiText.plural(R.plurals.notice_downloaded, downloaded)
        if (bytes > 0) parts += UiText.plural(R.plurals.notice_size, seconds, formatMegabytes(bytes), seconds)
        if (removed > 0) parts += UiText.plural(R.plurals.notice_removed, removed)
        if (skipped > 0) parts += UiText.plural(R.plurals.notice_skipped, skipped)
        if (ignored > 0) parts += UiText.plural(R.plurals.notice_ignored, ignored)
        return UiText.Joined(parts)
    }
}
