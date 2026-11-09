package dev.rubenmoya.traelo.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.rubenmoya.traelo.R
import dev.rubenmoya.traelo.core.CheckResult
import dev.rubenmoya.traelo.core.LocalChanges
import dev.rubenmoya.traelo.core.PlanCounts
import dev.rubenmoya.traelo.core.SyncPhase
import dev.rubenmoya.traelo.core.SyncProgress

/** Todo lo que la pantalla de un repo puede pedirle al ViewModel. */
class RepoActions(
    val back: () -> Unit,
    val pull: () -> Unit,
    val check: () -> Unit,
    val cancel: () -> Unit,
    val selectBranch: (String) -> Unit,
    val loadBranches: () -> Unit,
    val openGodot: () -> Unit,
    val startRestore: () -> Unit,
    val deleteLocal: () -> Unit,
    val pickFolder: () -> Unit,
    val confirmRestore: () -> Unit,
    val dismissRestore: () -> Unit,
    val dismissGodotMissing: () -> Unit,
    val openSettings: () -> Unit,
    val requestPermission: () -> Unit,
)

private const val MAX_COMMITS = 20

/** Lo que se ve en la tarjeta grande: una sola idea, "¿estoy al dia?". */
private fun displayStatus(d: DetailState): RepoStatus = when {
    d.checking -> RepoStatus.Checking
    d.error != null -> RepoStatus.Failed(d.error)
    d.check is CheckResult.UpToDate -> RepoStatus.UpToDate
    d.check is CheckResult.Outdated -> RepoStatus.Outdated(d.check.counts.changes)
    d.check is CheckResult.NeverSynced -> RepoStatus.NotSynced
    d.saved == null -> RepoStatus.NotSynced
    else -> RepoStatus.SyncedUnchecked
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepoScreen(
    detail: DetailState,
    godotMissing: Boolean,
    snackbarHostState: SnackbarHostState,
    actions: RepoActions,
) {
    var showLeave by remember { mutableStateOf(false) }
    var showBranches by remember { mutableStateOf(false) }
    val running = detail.isRunning

    BackHandler(enabled = running) { showLeave = true }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { if (running) showLeave = true else actions.back() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                detail.repo.ref.name,
                                style = MaterialTheme.typography.titleLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            if (detail.repo.isPrivate) {
                                Icon(
                                    Icons.Filled.Lock,
                                    contentDescription = stringResource(R.string.repo_private),
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                        Text(
                            detail.repo.ref.owner,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            BranchSelector(
                detail = detail,
                enabled = !running,
                onClick = {
                    actions.loadBranches()
                    showBranches = true
                },
            )
            StatusCard(detail, actions)
            if (running) {
                ProgressCard(detail.sync as SyncUi.Running, actions.cancel)
            } else {
                PrimaryActions(detail, actions)
            }
            detail.notice?.let { if (!running) NoticeCard(it) }
            if (detail.check is CheckResult.Outdated && !running) {
                ChangesSection(detail.check)
            }
            AdvancedSection(detail, actions)
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showBranches) {
        BranchSheet(
            detail = detail,
            onSelect = {
                showBranches = false
                actions.selectBranch(it)
            },
            onRetry = actions.loadBranches,
            onDismiss = { showBranches = false },
        )
    }

    if (showLeave) {
        AlertDialog(
            onDismissRequest = { showLeave = false },
            title = { Text(stringResource(R.string.leave_title)) },
            text = { Text(stringResource(R.string.leave_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showLeave = false
                    actions.cancel()
                    actions.back()
                }) { Text(stringResource(R.string.leave_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showLeave = false }) { Text(stringResource(R.string.leave_stay)) }
            },
        )
    }

    detail.pendingRestore?.let { changes ->
        RestoreDialog(changes, actions.confirmRestore, actions.dismissRestore)
    }

    if (godotMissing) {
        AlertDialog(
            onDismissRequest = actions.dismissGodotMissing,
            title = { Text(stringResource(R.string.godot_missing_title)) },
            text = { Text(stringResource(R.string.godot_missing_body)) },
            confirmButton = {
                TextButton(onClick = actions.dismissGodotMissing) { Text(stringResource(R.string.ok)) }
            },
        )
    }
}

// ------------------------------------------------------------------ rama

@Composable
private fun BranchSelector(detail: DetailState, enabled: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.branch_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(detail.branch, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.branch_change))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BranchSheet(
    detail: DetailState,
    onSelect: (String) -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text(
                stringResource(R.string.branch_sheet_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            when {
                detail.branchesLoading -> Row(
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                    horizontalArrangement = Arrangement.Center,
                ) { CircularProgressIndicator() }

                detail.branchesError != null -> Column(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(detail.branchesError.text.asString(), style = MaterialTheme.typography.bodyLarge)
                    BigButton(text = stringResource(R.string.retry), onClick = onRetry, tonal = true, height = 52.dp)
                }

                else -> {
                    val branches = detail.branches.orEmpty()
                    if (branches.isEmpty()) {
                        Text(
                            stringResource(R.string.branch_none),
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        branches.forEach { branch ->
                            val tags = buildList {
                                if (branch.name == detail.repo.defaultBranch) add(stringResource(R.string.branch_tag_default))
                                if (branch.name == detail.saved?.branch) add(stringResource(R.string.branch_tag_synced))
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelect(branch.name) }
                                    .padding(horizontal = 24.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(branch.name, style = MaterialTheme.typography.titleMedium)
                                    if (tags.isNotEmpty()) {
                                        Text(
                                            tags.joinToString(", "),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                if (branch.name == detail.branch) {
                                    Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.branch_selected))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ estado

@Composable
private fun StatusCard(detail: DetailState, actions: RepoActions) {
    val status = displayStatus(detail)
    val look = statusLook(status)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = look.container, contentColor = look.content),
    ) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                StatusIcon(status, size = 36.dp)
                Text(statusHeadline(status), style = MaterialTheme.typography.headlineMedium)
            }
            Text(statusBody(detail, status), style = MaterialTheme.typography.bodyLarge)
            if (status is RepoStatus.Failed) {
                ErrorButtons(status.error, actions)
            }
        }
    }
}

@Composable
private fun ErrorButtons(error: ErrorInfo, actions: RepoActions) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when (error.action) {
            ErrorAction.GO_TO_SETTINGS ->
                TextButton(onClick = actions.openSettings) { Text(stringResource(R.string.go_to_settings)) }

            ErrorAction.GRANT_PERMISSION ->
                TextButton(onClick = actions.requestPermission) { Text(stringResource(R.string.permission_banner_button)) }

            null -> Unit
        }
        TextButton(onClick = actions.check) { Text(stringResource(R.string.retry)) }
    }
}

@Composable
private fun statusHeadline(status: RepoStatus): String = when (status) {
    RepoStatus.UpToDate -> stringResource(R.string.status_up_to_date)
    is RepoStatus.Outdated -> stringResource(R.string.status_outdated_headline)
    RepoStatus.NotSynced -> stringResource(R.string.status_not_synced)
    RepoStatus.Checking -> stringResource(R.string.status_checking)
    RepoStatus.SyncedUnchecked -> stringResource(R.string.status_synced_unchecked)
    is RepoStatus.Failed -> stringResource(R.string.status_error)
}

@Composable
private fun statusBody(detail: DetailState, status: RepoStatus): String = when (status) {
    RepoStatus.UpToDate -> stringResource(R.string.status_body_up_to_date, detail.branch)
    is RepoStatus.Outdated -> {
        val check = detail.check as? CheckResult.Outdated
        val counts = check?.counts
        if (counts != null) countsText(counts) else ""
    }

    RepoStatus.NotSynced -> {
        val files = (detail.check as? CheckResult.NeverSynced)?.files
        if (files != null) {
            pluralStringResource(R.plurals.status_body_not_synced_files, files, detail.branch, files)
        } else {
            stringResource(R.string.status_body_not_synced)
        }
    }

    RepoStatus.Checking -> stringResource(R.string.status_body_checking)
    RepoStatus.SyncedUnchecked -> stringResource(R.string.status_body_unchecked)
    is RepoStatus.Failed -> status.error.text.asString()
}

@Composable
private fun countsText(counts: PlanCounts): String {
    val parts = buildList {
        if (counts.added > 0) add(pluralStringResource(R.plurals.counts_added, counts.added, counts.added))
        if (counts.modified > 0) add(pluralStringResource(R.plurals.counts_modified, counts.modified, counts.modified))
        if (counts.removed > 0) add(pluralStringResource(R.plurals.counts_removed, counts.removed, counts.removed))
    }
    return if (parts.isEmpty()) stringResource(R.string.counts_none) else parts.joinToString(", ")
}

// ------------------------------------------------------------------ acciones

@Composable
private fun PrimaryActions(detail: DetailState, actions: RepoActions) {
    val saved = detail.saved
    val branchChanged = saved != null && detail.branch != saved.branch
    val upToDate = detail.check is CheckResult.UpToDate && !branchChanged
    val busy = detail.checking || detail.findingLocal
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BigButton(
            text = when {
                saved == null -> stringResource(R.string.action_pull_first)
                branchChanged -> stringResource(R.string.action_pull_branch, detail.branch)
                else -> stringResource(R.string.action_pull)
            },
            onClick = actions.pull,
            enabled = !busy,
            tonal = upToDate,
            height = 68.dp,
        )
        BigButton(
            text = stringResource(R.string.action_open_godot),
            onClick = actions.openGodot,
            enabled = saved != null,
            tonal = !upToDate,
            outlined = upToDate,
            height = 60.dp,
        )
        projectNote(detail)?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun projectNote(detail: DetailState): String? {
    val project = detail.project
    return when {
        detail.saved == null || !detail.projectChecked -> null
        project == null -> stringResource(R.string.project_not_found)
        project.found > 1 -> stringResource(
            R.string.project_multiple,
            project.found,
            project.relativePath.ifEmpty { stringResource(R.string.project_root) },
        )

        project.relativePath.isNotEmpty() -> stringResource(R.string.project_in_subfolder, project.relativePath)
        else -> null
    }
}

@Composable
private fun ProgressCard(running: SyncUi.Running, onCancel: () -> Unit) {
    val progress = running.progress
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(progressLabel(progress), style = MaterialTheme.typography.titleMedium)
            val fraction = progressFraction(progress)
            if (fraction != null) {
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().height(10.dp))
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(10.dp))
            }
            progress?.path?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                stringResource(R.string.progress_keep_open),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            BigButton(text = stringResource(R.string.action_cancel), onClick = onCancel, outlined = true, height = 56.dp)
        }
    }
}

/** Fraccion de 0 a 1 si se sabe cuanto falta; null si no (barra indeterminada). */
private fun progressFraction(progress: SyncProgress?): Float? {
    if (progress == null || progress.total <= 0) return null
    val countable = progress.phase == SyncPhase.DOWNLOADING_FILES || progress.phase == SyncPhase.EXTRACTING
    return if (countable) (progress.done.toFloat() / progress.total).coerceIn(0f, 1f) else null
}

@Composable
private fun progressLabel(progress: SyncProgress?): String = when (progress?.phase) {
    null, SyncPhase.CHECKING -> stringResource(R.string.progress_preparing)
    SyncPhase.DOWNLOADING_ZIP -> stringResource(R.string.progress_zip)
    SyncPhase.EXTRACTING ->
        if (progress.total > 0) {
            stringResource(R.string.progress_extracting, progress.done, progress.total)
        } else {
            stringResource(R.string.progress_extracting_unknown)
        }

    SyncPhase.DOWNLOADING_FILES -> pluralStringResource(
        R.plurals.progress_files,
        progress.total,
        progress.done,
        progress.total,
    )

    SyncPhase.REMOVING -> stringResource(R.string.progress_removing)
    SyncPhase.SAVING -> stringResource(R.string.progress_saving)
}

@Composable
private fun NoticeCard(text: UiText) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Text(
            text.asString(),
            modifier = Modifier.padding(20.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

// ------------------------------------------------------------------ que ha cambiado

@Composable
private fun ChangesSection(check: CheckResult.Outdated) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.changes_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        val commits = check.commits
        when {
            commits == null -> SoftCard { Text(stringResource(R.string.changes_unknown), style = MaterialTheme.typography.bodyLarge) }
            commits.isEmpty() -> SoftCard { Text(stringResource(R.string.changes_none), style = MaterialTheme.typography.bodyLarge) }
            else -> SoftCard {
                val shown = commits.take(MAX_COMMITS)
                shown.forEachIndexed { index, commit ->
                    if (index > 0) HorizontalDivider(Modifier.padding(vertical = 12.dp))
                    Text(commit.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        stringResource(R.string.commit_meta, commit.author, formatShortDate(commit.date)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (commits.size > MAX_COMMITS) {
                    HorizontalDivider(Modifier.padding(vertical = 12.dp))
                    Text(
                        pluralStringResource(R.plurals.changes_more, commits.size - MAX_COMMITS, commits.size - MAX_COMMITS),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SoftCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) { content() }
    }
}

// ------------------------------------------------------------------ avanzado

@Composable
private fun AdvancedSection(detail: DetailState, actions: RepoActions) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val running = detail.isRunning
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 4.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.advanced_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Icon(
                if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = stringResource(if (expanded) R.string.collapse else R.string.expand),
            )
        }
        if (expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AdvancedAction(
                    label = stringResource(R.string.advanced_check),
                    help = stringResource(R.string.advanced_check_help),
                    onClick = actions.check,
                    enabled = !running && !detail.checking,
                )
                AdvancedAction(
                    label = stringResource(R.string.advanced_pull),
                    help = stringResource(R.string.advanced_pull_help),
                    onClick = actions.pull,
                    enabled = !running && !detail.checking,
                )
                AdvancedAction(
                    label = stringResource(R.string.advanced_restore),
                    help = stringResource(R.string.advanced_restore_help),
                    onClick = actions.startRestore,
                    enabled = !running && detail.saved != null && !detail.findingLocal,
                    busy = detail.findingLocal,
                )
                if (detail.saved == null) {
                    AdvancedAction(
                        label = stringResource(R.string.advanced_pick_folder),
                        help = stringResource(R.string.advanced_pick_folder_help),
                        onClick = actions.pickFolder,
                        enabled = !running,
                    )
                }
                if (detail.saved != null) {
                    var confirmDelete by rememberSaveable { mutableStateOf(false) }
                    AdvancedAction(
                        label = stringResource(R.string.advanced_delete),
                        help = stringResource(R.string.advanced_delete_help),
                        onClick = { confirmDelete = true },
                        enabled = !running && !detail.deleting,
                        busy = detail.deleting,
                        busyText = stringResource(R.string.advanced_delete_busy),
                    )
                    if (confirmDelete) {
                        DeleteDialog(
                            dir = detail.saved.localDir,
                            onConfirm = {
                                confirmDelete = false
                                actions.deleteLocal()
                            },
                            onDismiss = { confirmDelete = false },
                        )
                    }
                }
                SoftCard {
                    val saved = detail.saved
                    InfoRow(stringResource(R.string.info_folder), saved?.localDir ?: detail.localDir)
                    Spacer(Modifier.height(10.dp))
                    InfoRow(
                        stringResource(R.string.info_last_commit),
                        saved?.let { shortSha(it.headSha) } ?: stringResource(R.string.info_none),
                    )
                    Spacer(Modifier.height(10.dp))
                    InfoRow(
                        stringResource(R.string.info_last_time),
                        saved?.let { formatDateTime(it.syncedAtMillis) } ?: stringResource(R.string.info_never),
                    )
                }
            }
        }
    }
}

@Composable
private fun AdvancedAction(
    label: String,
    help: String,
    onClick: () -> Unit,
    enabled: Boolean,
    busy: Boolean = false,
    busyText: String = stringResource(R.string.advanced_restore_busy),
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BigButton(text = label, onClick = onClick, enabled = enabled, outlined = true, height = 52.dp)
        Text(
            if (busy) busyText else help,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

// ------------------------------------------------------------------ dialogo de borrar

@Composable
private fun DeleteDialog(dir: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.delete_body), style = MaterialTheme.typography.bodyLarge)
                Text(dir, style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.delete_warning), style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.delete_confirm), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

// ------------------------------------------------------------------ dialogo de restaurar

@Composable
private fun RestoreDialog(changes: LocalChanges, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val total = changes.modified.size + changes.missing.size
    val sample = (changes.modified + changes.missing).take(5)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(pluralStringResource(R.plurals.restore_title, total, total)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResource(R.string.restore_body, changes.modified.size, changes.missing.size),
                    style = MaterialTheme.typography.bodyLarge,
                )
                sample.forEach {
                    Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (total > sample.size) {
                    Text(
                        pluralStringResource(R.plurals.changes_more, total - sample.size, total - sample.size),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(stringResource(R.string.restore_warning), style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.restore_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
