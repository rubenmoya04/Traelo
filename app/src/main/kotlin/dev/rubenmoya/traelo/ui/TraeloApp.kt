package dev.rubenmoya.traelo.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.rubenmoya.traelo.data.StoragePermission

private const val TOKEN_URL = "https://github.com/settings/personal-access-tokens/new"

@Composable
fun TraeloApp(vm: TraeloViewModel) {
    val context = LocalContext.current
    val stack by vm.stack.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
    val storageGranted by vm.storageGranted.collectAsStateWithLifecycle()
    val repos by vm.repos.collectAsStateWithLifecycle()
    val syncStates by vm.syncStates.collectAsStateWithLifecycle()
    val checks by vm.checks.collectAsStateWithLifecycle()
    val detail by vm.detail.collectAsStateWithLifecycle()
    val baseDir by vm.baseDir.collectAsStateWithLifecycle()
    val launchDirect by vm.launchDirect.collectAsStateWithLifecycle()
    val godotMissing by vm.godotMissing.collectAsStateWithLifecycle()
    val keepScreenOn by vm.keepScreenOn.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    // Al volver a la app se vuelve a mirar el permiso (el usuario viene de los ajustes del sistema).
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshPermission() }

    val legacyLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vm.refreshPermission()
    }
    val requestStorage: () -> Unit = {
        if (StoragePermission.needsSettingsScreen) {
            openStorageSettings(context)
        } else {
            legacyLauncher.launch(StoragePermission.legacyPermissions)
        }
    }

    // Pantalla encendida mientras se descarga.
    DisposableEffect(keepScreenOn) {
        val window = context.findActivity()?.window
        if (keepScreenOn) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    LaunchedEffect(Unit) {
        vm.messages.collect { snackbar.showSnackbar(it.asString(context)) }
    }

    BackHandler(enabled = stack.size > 1) { vm.back() }

    Surface(color = MaterialTheme.colorScheme.background) {
        when (val screen = stack.last()) {
            Screen.Welcome -> WelcomeScreen(
                session = session,
                storageGranted = storageGranted,
                onValidate = vm::validateToken,
                onRequestPermission = requestStorage,
                onOpenGitHub = { openUrl(context, TOKEN_URL) },
                onFinish = vm::finishWelcome,
            )

            Screen.Repos -> {
                LaunchedEffect(Unit) { vm.ensureReposLoaded() }
                ReposScreen(
                    state = repos,
                    syncStates = syncStates,
                    checks = checks,
                    storageGranted = storageGranted,
                    snackbarHostState = snackbar,
                    onRefresh = vm::refresh,
                    onRetry = vm::retryRepos,
                    onOpenRepo = { vm.openRepo(it.ref) },
                    onOpenSettings = vm::openSettings,
                    onRequestPermission = requestStorage,
                )
            }

            is Screen.Repo -> detail?.let { d ->
                RepoScreen(
                    detail = d,
                    godotMissing = godotMissing,
                    snackbarHostState = snackbar,
                    actions = RepoActions(
                        back = vm::back,
                        pull = { vm.pull() },
                        check = vm::runCheck,
                        cancel = vm::cancelPull,
                        selectBranch = vm::selectBranch,
                        loadBranches = vm::loadBranches,
                        openGodot = vm::openInGodot,
                        startRestore = vm::startRestore,
                        confirmRestore = vm::confirmRestore,
                        dismissRestore = vm::dismissRestore,
                        dismissGodotMissing = vm::dismissGodotMissing,
                        openSettings = vm::openSettings,
                        requestPermission = requestStorage,
                    ),
                )
            }

            Screen.Settings -> SettingsScreen(
                login = session.login,
                baseDir = baseDir,
                storageGranted = storageGranted,
                launchDirect = launchDirect,
                versionName = remember { versionName(context) },
                onBack = vm::back,
                onLogout = vm::logout,
                onSaveBaseDir = vm::saveBaseDir,
                onResetBaseDir = vm::resetBaseDir,
                onRequestPermission = requestStorage,
                onLaunchDirect = vm::setLaunchDirect,
            )
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun openStorageSettings(context: Context) {
    try {
        context.startActivity(StoragePermission.settingsIntent(context))
    } catch (e: ActivityNotFoundException) {
        try {
            context.startActivity(StoragePermission.fallbackSettingsIntent())
        } catch (e2: ActivityNotFoundException) {
            // Un movil sin esa pantalla de ajustes: no hay nada mas que hacer desde aqui.
        }
    }
}

private fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: ActivityNotFoundException) {
        // Sin navegador: el texto de ayuda ya explica la ruta a mano.
    }
}

@Suppress("DEPRECATION")
private fun versionName(context: Context): String =
    try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    } catch (e: Exception) {
        "?"
    }
