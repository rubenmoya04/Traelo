package dev.rubenmoya.traelo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.rubenmoya.traelo.R

/** Colores de una pastilla o tarjeta de estado. */
data class StatusLook(val container: Color, val content: Color)

@Composable
fun statusLook(status: RepoStatus): StatusLook {
    val colors = LocalStatusColors.current
    val scheme = MaterialTheme.colorScheme
    return when (status) {
        RepoStatus.UpToDate -> StatusLook(colors.okContainer, colors.onOk)
        is RepoStatus.Outdated -> StatusLook(colors.warnContainer, colors.onWarn)
        is RepoStatus.Failed -> StatusLook(scheme.errorContainer, scheme.onErrorContainer)
        RepoStatus.NotSynced, RepoStatus.SyncedUnchecked, RepoStatus.Checking ->
            StatusLook(scheme.surfaceVariant, scheme.onSurfaceVariant)
    }
}

@Composable
fun statusLabel(status: RepoStatus): String = when (status) {
    RepoStatus.NotSynced -> stringResource(R.string.status_not_synced)
    RepoStatus.SyncedUnchecked -> stringResource(R.string.status_synced_unchecked)
    RepoStatus.Checking -> stringResource(R.string.status_checking)
    RepoStatus.UpToDate -> stringResource(R.string.status_up_to_date)
    is RepoStatus.Outdated -> pluralStringResource(R.plurals.status_outdated, status.changes, status.changes)
    is RepoStatus.Failed -> stringResource(R.string.status_error)
}

/** Pastilla de estado de un repo: de un vistazo, "¿estoy al dia?". */
@Composable
fun StatusPill(status: RepoStatus, modifier: Modifier = Modifier) {
    val look = statusLook(status)
    Surface(modifier = modifier, shape = CircleShape, color = look.container, contentColor = look.content) {
        Row(
            modifier = Modifier.padding(start = 10.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            StatusIcon(status, size = 16.dp)
            Text(statusLabel(status), style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
fun StatusIcon(status: RepoStatus, size: Dp, modifier: Modifier = Modifier) {
    when (status) {
        RepoStatus.Checking -> CircularProgressIndicator(
            modifier = modifier.size(size),
            strokeWidth = if (size > 20.dp) 4.dp else 2.dp,
        )

        else -> Icon(statusImage(status), contentDescription = null, modifier = modifier.size(size))
    }
}

private fun statusImage(status: RepoStatus): ImageVector = when (status) {
    RepoStatus.UpToDate -> Icons.Filled.CheckCircle
    is RepoStatus.Outdated, is RepoStatus.Failed -> Icons.Filled.Warning
    else -> Icons.Filled.Info
}

/** Boton grande para el pulgar. [tonal] para las acciones secundarias. */
@Composable
fun BigButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tonal: Boolean = false,
    outlined: Boolean = false,
    height: Dp = 60.dp,
) {
    val shape = RoundedCornerShape(20.dp)
    val sizing = modifier
        .fillMaxWidth()
        .height(height)
    val padding = PaddingValues(horizontal = 20.dp)
    when {
        outlined -> OutlinedButton(onClick, sizing, enabled, shape, contentPadding = padding) { ButtonText(text) }
        tonal -> FilledTonalButton(onClick, sizing, enabled, shape, contentPadding = padding) { ButtonText(text) }
        else -> Button(onClick, sizing, enabled, shape, contentPadding = padding) { ButtonText(text) }
    }
}

@Composable
private fun ButtonText(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
}

/** Pantalla de mensaje centrado: vacio, error, sin conexion. */
@Composable
fun MessageState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.height(4.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(
            body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        actions()
    }
}
