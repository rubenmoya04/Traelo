package dev.rubenmoya.traelo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.rubenmoya.traelo.R

@Composable
fun WelcomeScreen(
    session: SessionState,
    storageGranted: Boolean,
    onValidate: (String) -> Unit,
    onRequestPermission: () -> Unit,
    onOpenGitHub: () -> Unit,
    onFinish: () -> Unit,
) {
    // El token solo vive en memoria mientras se escribe: nada de rememberSaveable.
    var token by remember { mutableStateOf("") }
    var skippedPermission by rememberSaveable { mutableStateOf(false) }

    val tokenOk = session.hasToken
    val permissionDone = tokenOk && storageGranted
    val ready = tokenOk && (storageGranted || skippedPermission)

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.welcome_title), style = MaterialTheme.typography.displaySmall)
            Text(
                stringResource(R.string.welcome_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))

            StepCard(number = 1, title = stringResource(R.string.step1_title), done = tokenOk) {
                if (tokenOk) {
                    Text(
                        session.login?.let { stringResource(R.string.welcome_hello, it) }
                            ?: stringResource(R.string.welcome_token_saved),
                        style = MaterialTheme.typography.titleMedium,
                    )
                } else {
                    TokenStep(
                        token = token,
                        onTokenChange = { token = it },
                        session = session,
                        onValidate = { onValidate(token) },
                        onOpenGitHub = onOpenGitHub,
                    )
                }
            }

            StepCard(number = 2, title = stringResource(R.string.step2_title), done = permissionDone, enabled = tokenOk) {
                if (tokenOk) {
                    PermissionStep(
                        granted = storageGranted,
                        onRequest = onRequestPermission,
                        onSkip = { skippedPermission = true },
                        canSkip = !skippedPermission,
                    )
                }
            }

            StepCard(number = 3, title = stringResource(R.string.step3_title), done = false, enabled = ready) {
                if (ready) {
                    Text(stringResource(R.string.step3_body), style = MaterialTheme.typography.bodyLarge)
                    BigButton(text = stringResource(R.string.step3_button), onClick = onFinish)
                }
            }
        }
    }
}

@Composable
private fun TokenStep(
    token: String,
    onTokenChange: (String) -> Unit,
    session: SessionState,
    onValidate: () -> Unit,
    onOpenGitHub: () -> Unit,
) {
    var visible by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.howto_intro), style = MaterialTheme.typography.bodyLarge)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                R.string.howto_1,
                R.string.howto_2,
                R.string.howto_3,
                R.string.howto_4,
                R.string.howto_5,
            ).forEachIndexed { index, id ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${index + 1}.", style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(id), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        BigButton(
            text = stringResource(R.string.open_github),
            onClick = onOpenGitHub,
            outlined = true,
            height = 52.dp,
        )
        OutlinedTextField(
            value = token,
            onValueChange = onTokenChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.token_label)) },
            singleLine = true,
            enabled = !session.validating,
            isError = session.error != null,
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onValidate() }),
            trailingIcon = {
                TextButton(onClick = { visible = !visible }) {
                    Text(stringResource(if (visible) R.string.token_hide else R.string.token_show))
                }
            },
            supportingText = session.error?.let { error -> { Text(error.text.asString()) } },
        )
        if (session.validating) {
            Row(
                modifier = Modifier.fillMaxWidth().height(60.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                Spacer(Modifier.size(12.dp))
                Text(stringResource(R.string.token_validating), style = MaterialTheme.typography.titleMedium)
            }
        } else {
            BigButton(
                text = stringResource(R.string.token_validate),
                onClick = onValidate,
                enabled = token.isNotBlank(),
            )
        }
    }
}

@Composable
private fun PermissionStep(granted: Boolean, onRequest: () -> Unit, onSkip: () -> Unit, canSkip: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (granted) {
            Text(stringResource(R.string.permission_granted), style = MaterialTheme.typography.titleMedium)
        } else {
            Text(stringResource(R.string.permission_why), style = MaterialTheme.typography.bodyLarge)
            BigButton(text = stringResource(R.string.permission_button), onClick = onRequest)
            if (canSkip) {
                TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.permission_skip))
                }
            }
        }
    }
}

@Composable
private fun StepCard(
    number: Int,
    title: String,
    done: Boolean,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (enabled || done) {
                MaterialTheme.colorScheme.surfaceContainerHigh
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
        ),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(
                    shape = CircleShape,
                    color = if (done) LocalStatusColors.current.okContainer else MaterialTheme.colorScheme.primaryContainer,
                    contentColor = if (done) LocalStatusColors.current.onOk else MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(36.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (done) {
                            Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(20.dp))
                        } else {
                            Text("$number", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    color = if (enabled || done) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            content()
        }
    }
}
