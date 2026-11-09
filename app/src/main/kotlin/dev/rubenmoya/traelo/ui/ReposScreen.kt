package dev.rubenmoya.traelo.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.rubenmoya.traelo.R
import dev.rubenmoya.traelo.core.RemoteRepo
import dev.rubenmoya.traelo.core.SyncState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReposScreen(
    state: ReposState,
    syncStates: Map<String, SyncState>,
    checks: Map<String, RepoStatus>,
    storageGranted: Boolean,
    snackbarHostState: SnackbarHostState,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    onOpenRepo: (RemoteRepo) -> Unit,
    onOpenSettings: () -> Unit,
    onRequestPermission: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = remember(state.repos, query) {
        val q = query.trim()
        if (q.isEmpty()) state.repos else state.repos.filter { it.ref.fullName.contains(q, ignoreCase = true) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.repos_title)) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings_title))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.repos.isNotEmpty() || query.isNotEmpty()) {
                SearchField(query, { query = it })
            }
            if (!storageGranted) {
                PermissionBanner(onRequestPermission)
            }
            PullToRefreshBox(
                isRefreshing = state.refreshing,
                onRefresh = onRefresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    when {
                        state.loading || (!state.loaded && state.error == null) -> item {
                            LoadingState()
                        }

                        state.error != null && state.repos.isEmpty() -> item {
                            ErrorState(state.error, onRetry, onOpenSettings)
                        }

                        state.repos.isEmpty() -> item {
                            MessageState(
                                icon = Icons.Filled.Search,
                                title = stringResource(R.string.repos_empty_title),
                                body = stringResource(R.string.repos_empty_body),
                                actions = {
                                    TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
                                },
                            )
                        }

                        filtered.isEmpty() -> item {
                            MessageState(
                                icon = Icons.Filled.Search,
                                title = stringResource(R.string.repos_no_results_title),
                                body = stringResource(R.string.repos_no_results_body, query.trim()),
                            )
                        }

                        else -> {
                            state.error?.let { error -> item { RefreshErrorBanner(error) } }
                            items(filtered, key = { it.ref.key }) { repo ->
                                RepoCard(
                                    repo = repo,
                                    status = statusOf(repo.ref, syncStates, checks),
                                    onClick = { onOpenRepo(repo) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
        placeholder = { Text(stringResource(R.string.search_hint)) },
        singleLine = true,
        shape = RoundedCornerShape(28.dp),
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onChange("") }) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.search_clear))
                }
            }
        },
    )
}

@Composable
private fun PermissionBanner(onRequest: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = LocalStatusColors.current.warnContainer,
            contentColor = LocalStatusColors.current.onWarn,
        ),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.permission_banner),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = onRequest) { Text(stringResource(R.string.permission_banner_button)) }
        }
    }
}

@Composable
private fun LoadingState() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 80.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        CircularProgressIndicator()
        Text(stringResource(R.string.repos_loading), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun ErrorState(error: ErrorInfo, onRetry: () -> Unit, onSettings: () -> Unit) {
    MessageState(
        icon = Icons.Filled.Warning,
        title = stringResource(if (error.offline) R.string.repos_offline_title else R.string.repos_error_title),
        body = error.text.asString(),
        actions = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (error.action == ErrorAction.GO_TO_SETTINGS) {
                    BigButton(text = stringResource(R.string.go_to_settings), onClick = onSettings, height = 56.dp)
                    BigButton(text = stringResource(R.string.retry), onClick = onRetry, tonal = true, height = 56.dp)
                } else {
                    BigButton(text = stringResource(R.string.retry), onClick = onRetry, height = 56.dp)
                }
            }
        },
    )
}

@Composable
private fun RefreshErrorBanner(error: ErrorInfo) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Text(
            stringResource(R.string.repos_refresh_failed, error.text.asString()),
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun RepoCard(repo: RemoteRepo, status: RepoStatus, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    repo.ref.name,
                    modifier = Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (repo.isPrivate) {
                    Icon(
                        Icons.Filled.Lock,
                        contentDescription = stringResource(R.string.repo_private),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            val pushed = formatShortDate(repo.pushedAt)
            Text(
                if (pushed.isEmpty()) repo.ref.owner else stringResource(R.string.repo_owner_pushed, repo.ref.owner, pushed),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            StatusPill(status)
        }
    }
}
