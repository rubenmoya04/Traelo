package dev.rubenmoya.traelo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.rubenmoya.traelo.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    login: String?,
    baseDir: String,
    storageGranted: Boolean,
    launchDirect: Boolean,
    versionName: String,
    onBack: () -> Unit,
    onLogout: () -> Unit,
    onSaveBaseDir: (String) -> Unit,
    onResetBaseDir: () -> Unit,
    onRequestPermission: () -> Unit,
    onLaunchDirect: (Boolean) -> Unit,
) {
    var confirmLogout by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
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
            Section(stringResource(R.string.settings_account)) {
                Text(
                    if (login != null) stringResource(R.string.settings_logged_as, login) else stringResource(R.string.settings_logged_unknown),
                    style = MaterialTheme.typography.titleMedium,
                )
                BigButton(
                    text = stringResource(R.string.settings_logout),
                    onClick = { confirmLogout = true },
                    outlined = true,
                    height = 52.dp,
                )
            }

            Section(stringResource(R.string.settings_folder)) {
                BaseDirEditor(baseDir, onSaveBaseDir, onResetBaseDir)
            }

            Section(stringResource(R.string.settings_permission)) {
                Text(
                    stringResource(if (storageGranted) R.string.permission_granted else R.string.settings_permission_missing),
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (!storageGranted) {
                    BigButton(text = stringResource(R.string.permission_button), onClick = onRequestPermission, height = 52.dp)
                }
            }

            Section(stringResource(R.string.settings_godot)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.settings_launch_direct), style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(R.string.settings_launch_direct_help),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = launchDirect, onCheckedChange = onLaunchDirect)
                }
            }

            Text(
                stringResource(R.string.settings_version, versionName),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
            )
        }
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text(stringResource(R.string.logout_title)) },
            text = { Text(stringResource(R.string.logout_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmLogout = false
                    onLogout()
                }) { Text(stringResource(R.string.logout_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmLogout = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun BaseDirEditor(baseDir: String, onSave: (String) -> Unit, onReset: () -> Unit) {
    var text by remember(baseDir) { mutableStateOf(baseDir) }
    val problem = validateBaseDir(text)
    val changed = text.trim() != baseDir
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.settings_folder_label)) },
            isError = problem != null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            supportingText = {
                Text(
                    when (problem) {
                        BaseDirProblem.EMPTY -> stringResource(R.string.folder_error_empty)
                        BaseDirProblem.NOT_ABSOLUTE -> stringResource(R.string.folder_error_not_absolute)
                        BaseDirProblem.HAS_DOTDOT -> stringResource(R.string.folder_error_dotdot)
                        null -> stringResource(R.string.settings_folder_help)
                    },
                )
            },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            BigButton(
                text = stringResource(R.string.save),
                onClick = { onSave(text) },
                enabled = problem == null && changed,
                modifier = Modifier.weight(1f),
                height = 52.dp,
            )
            BigButton(
                text = stringResource(R.string.settings_folder_reset),
                onClick = onReset,
                tonal = true,
                modifier = Modifier.weight(1f),
                height = 52.dp,
            )
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) { content() }
        }
    }
}
