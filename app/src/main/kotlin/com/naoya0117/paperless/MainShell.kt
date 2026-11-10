package com.naoya0117.paperless

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.naoya0117.paperless.core.model.Account
import com.naoya0117.paperless.feature.documents.DocumentListMode
import com.naoya0117.paperless.feature.documents.DocumentListScreen
import com.naoya0117.paperless.feature.documents.DocumentListViewModel
import com.naoya0117.paperless.feature.documents.DocumentViewerScreen
import com.naoya0117.paperless.feature.documents.DocumentViewerViewModel
import kotlinx.coroutines.launch

/** The bottom tabs: only what is used most; everything else is in the drawer (hamburger menu). */
private enum class Tab(
    val mode: DocumentListMode,
    val icon: ImageVector,
    val label: Int,
) {
    DOCUMENTS(DocumentListMode.ALL, Icons.Outlined.Description, R.string.tab_documents),
    INBOX(DocumentListMode.INBOX, Icons.Outlined.Inbox, R.string.tab_inbox),
    SEARCH(DocumentListMode.SEARCH, Icons.Outlined.Search, R.string.tab_search),
    ;

    /** One destination per tab, so that each keeps its own back stack entry, view model and saved state. */
    val route: String get() = "documents/${mode.name.lowercase()}"
}

private const val DOCUMENT_ROUTE = "document/{${DocumentViewerViewModel.DOCUMENT_ID_ARGUMENT}}"
private const val EDIT_ROUTE = "document/{id}/edit"

/** The signed-in app: top bar with the drawer, bottom tabs, and the screens in between. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainShell(
    account: Account,
    onSignOut: () -> Unit,
) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val tab = Tab.entries.firstOrNull { it.route == route }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    // Back closes the drawer rather than the app.
    BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }

    var confirmSignOut by rememberSaveable { mutableStateOf(false) }
    if (confirmSignOut) {
        SignOutDialog(
            account = account,
            onConfirm = {
                confirmSignOut = false
                onSignOut()
            },
            onDismiss = { confirmSignOut = false },
        )
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        // Only from the tabs; elsewhere the edge swipe is the system's back gesture.
        gesturesEnabled = tab != null,
        drawerContent = {
            Drawer(
                account = account,
                onSignOut = {
                    scope.launch { drawerState.close() }
                    confirmSignOut = true
                },
            )
        },
    ) {
        Scaffold(
            // The tabs share this bar; other screens (e.g. the viewer) bring their own.
            topBar = {
                if (tab != null) {
                    CenterAlignedTopAppBar(
                        title = { Text(stringResource(tab.label)) },
                        navigationIcon = {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Outlined.Menu, contentDescription = stringResource(R.string.menu_open))
                            }
                        },
                    )
                }
            },
            bottomBar = { if (tab != null) TabBar(tab, navController) },
        ) { padding ->
            NavHost(
                navController = navController,
                startDestination = Tab.DOCUMENTS.route,
                modifier = Modifier.padding(padding),
            ) {
                Tab.entries.forEach { item ->
                    composable(
                        route = item.route,
                        arguments =
                            listOf(
                                navArgument(DocumentListViewModel.MODE_ARGUMENT) {
                                    type = NavType.StringType
                                    defaultValue = item.mode.name
                                },
                            ),
                    ) {
                        DocumentListScreen(onOpenDocument = { navController.navigate("document/${it.id}") })
                    }
                }
                composable(
                    DOCUMENT_ROUTE,
                    arguments =
                        listOf(
                            navArgument(DocumentViewerViewModel.DOCUMENT_ID_ARGUMENT) {
                                type =
                                    NavType.LongType
                            },
                        ),
                ) {
                    DocumentViewerScreen(
                        onBack = { navController.popBackStack() },
                        onEdit = { id -> navController.navigate("document/$id/edit") },
                    )
                }
                composable(EDIT_ROUTE, arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                    // Replaced by the editor (next issue).
                    Text(
                        "#${entry.arguments?.getLong("id")}",
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun TabBar(
    current: Tab,
    navController: NavHostController,
) {
    NavigationBar {
        Tab.entries.forEach { item ->
            NavigationBarItem(
                selected = item == current,
                onClick = {
                    navController.navigate(item.route) {
                        // The documents tab is the root; the others come back to it on Back.
                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = { Icon(item.icon, contentDescription = null) },
                label = { Text(stringResource(item.label)) },
            )
        }
    }
}

@Composable
private fun Drawer(
    account: Account,
    onSignOut: () -> Unit,
) {
    ModalDrawerSheet {
        Column(modifier = Modifier.padding(horizontal = 28.dp, vertical = 24.dp)) {
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(account.displayName ?: account.username, style = MaterialTheme.typography.titleMedium)
            Text(
                account.serverUrl,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            account.serverVersion?.let {
                Text(
                    stringResource(R.string.drawer_server_version, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider()
        NavigationDrawerItem(
            label = { Text(stringResource(R.string.home_sign_out)) },
            icon = { Icon(Icons.AutoMirrored.Outlined.Logout, contentDescription = null) },
            selected = false,
            onClick = onSignOut,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

/** Signing out also deletes what is stored for the account on this device, so ask first. */
@Composable
private fun SignOutDialog(
    account: Account,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sign_out_title)) },
        text = {
            Text(
                stringResource(R.string.sign_out_message, account.displayName ?: account.username, account.serverUrl),
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.home_sign_out)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
