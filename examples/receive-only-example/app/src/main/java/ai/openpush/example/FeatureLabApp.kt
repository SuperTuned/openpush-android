package ai.openpush.example

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.EventNote
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun OpenPushFeatureLab(model: ExampleLabModel) {
    OpenPushTheme(model.appearance) {
        val snackbar = remember { SnackbarHostState() }
        val message = model.message
        LaunchedEffect(message?.id) {
            message?.let {
                snackbar.showSnackbar(it.text)
                model.consumeMessage(it.id)
            }
        }
        FeatureLabShell(model, snackbar)
        if (model.settingsOpen) {
            FeatureLabSettings(model) { model.settingsOpen = false }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FeatureLabShell(model: ExampleLabModel, snackbar: SnackbarHostState) {
    BoxWithConstraints(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
    ) {
        val expanded = maxWidth >= 720.dp
        Row(Modifier.fillMaxSize()) {
            if (expanded) {
                FeatureNavigationRail(model)
            }
            Scaffold(
                modifier = Modifier.weight(1f),
                containerColor = MaterialTheme.colorScheme.background,
                topBar = {
                    TopAppBar(
                        title = {
                            Text(
                                model.selectedDestination.label,
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                            )
                        },
                        actions = {
                            IconButton(onClick = { model.settingsOpen = true }) {
                                Icon(Icons.Outlined.Settings, contentDescription = "Feature lab settings")
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background,
                            scrolledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    )
                },
                bottomBar = {
                    if (!expanded) FeatureNavigationBar(model)
                },
                snackbarHost = { SnackbarHost(snackbar) },
            ) { padding ->
                Crossfade(
                    targetState = model.selectedDestination,
                    modifier = Modifier.padding(padding),
                    animationSpec = tween(durationMillis = 220),
                    label = "feature workspace",
                ) { destination ->
                    when (destination) {
                        FeatureDestination.STATUS -> StatusScreen(model)
                        FeatureDestination.USER -> UserScreen(model)
                        FeatureDestination.IN_APP -> InAppScreen(model)
                        FeatureDestination.EVENTS -> EventsScreen(model)
                        FeatureDestination.ACTIVITY -> ActivityScreen(model)
                    }
                }
            }
        }
    }
}

@Composable
private fun FeatureNavigationBar(model: ExampleLabModel) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 0.dp,
    ) {
        FeatureDestination.entries.forEach { destination ->
            NavigationBarItem(
                selected = model.selectedDestination == destination,
                onClick = { model.selectedDestination = destination },
                icon = { Icon(destination.icon(), contentDescription = null) },
                label = { Text(destination.label) },
            )
        }
    }
}

@Composable
private fun FeatureNavigationRail(model: ExampleLabModel) {
    NavigationRail(
        modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing),
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Spacer(Modifier.weight(1f))
        FeatureDestination.entries.forEach { destination ->
            NavigationRailItem(
                selected = model.selectedDestination == destination,
                onClick = { model.selectedDestination = destination },
                icon = { Icon(destination.icon(), contentDescription = null) },
                label = { Text(destination.label) },
            )
        }
        Spacer(Modifier.weight(1f))
    }
}

private fun FeatureDestination.icon(): ImageVector = when (this) {
    FeatureDestination.STATUS -> Icons.Outlined.Sensors
    FeatureDestination.USER -> Icons.Outlined.PersonOutline
    FeatureDestination.IN_APP -> Icons.Outlined.Campaign
    FeatureDestination.EVENTS -> Icons.Outlined.EventNote
    FeatureDestination.ACTIVITY -> Icons.Outlined.Update
}
