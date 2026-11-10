package com.naoya0117.paperless

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.naoya0117.paperless.core.model.Account
import com.naoya0117.paperless.feature.documents.DocumentEditScreen
import com.naoya0117.paperless.feature.documents.DocumentEditViewModel
import com.naoya0117.paperless.feature.documents.DocumentListMode
import com.naoya0117.paperless.feature.documents.DocumentListScreen
import com.naoya0117.paperless.feature.documents.DocumentListViewModel
import com.naoya0117.paperless.feature.documents.DocumentViewerScreen
import com.naoya0117.paperless.feature.documents.DocumentViewerViewModel
import com.naoya0117.paperless.feature.documents.InboxViewModel
import kotlinx.coroutines.launch

private const val DOCUMENTS_ROUTE = "documents"
private const val INBOX_ROUTE = "inbox"
private val LIST_ROUTES = listOf(DOCUMENTS_ROUTE to DocumentListMode.ALL, INBOX_ROUTE to DocumentListMode.INBOX)
private const val DOCUMENT_ROUTE = "document/{${DocumentViewerViewModel.DOCUMENT_ID_ARGUMENT}}"
private const val EDIT_ROUTE = "document/{${DocumentEditViewModel.DOCUMENT_ID_ARGUMENT}}/edit"

/**
 * The signed-in app: the document list (with search) under a top bar with the drawer (hamburger menu) and the
 * inbox button, and the screens opened from it. Bottom tabs come once there are several frequent screens.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainShell(
    account: Account,
    onSignOut: () -> Unit,
    inbox: InboxViewModel = hiltViewModel(),
) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val inboxCount by inbox.count.collectAsStateWithLifecycle()
    // The count may have changed (e.g. documents checked in the inbox) whenever the list shows again.
    LaunchedEffect(route) { if (route == DOCUMENTS_ROUTE) inbox.refresh() }
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
        // Only from the list; elsewhere the edge swipe is the system's back gesture.
        gesturesEnabled = route == DOCUMENTS_ROUTE,
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
        val listScreen = route == DOCUMENTS_ROUTE || route == INBOX_ROUTE
        Scaffold(
            // The lists share this bar; other screens (e.g. the viewer) bring their own.
            topBar = {
                when (route) {
                    DOCUMENTS_ROUTE ->
                        CenterAlignedTopAppBar(
                            title = { Text(stringResource(R.string.tab_documents)) },
                            navigationIcon = {
                                IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                    Icon(Icons.Outlined.Menu, contentDescription = stringResource(R.string.menu_open))
                                }
                            },
                            actions = {
                                if (inboxCount >
                                    0
                                ) {
                                    InboxButton(inboxCount) { navController.navigate(INBOX_ROUTE) }
                                }
                            },
                        )
                    INBOX_ROUTE ->
                        CenterAlignedTopAppBar(
                            title = { Text(stringResource(R.string.tab_inbox)) },
                            navigationIcon = {
                                IconButton(onClick = { navController.popBackStack() }) {
                                    Icon(
                                        Icons.AutoMirrored.Outlined.ArrowBack,
                                        contentDescription = stringResource(R.string.back),
                                    )
                                }
                            },
                        )
                }
            },
            // Other screens (e.g. the viewer) are a Scaffold of their own and handle the system bars themselves.
            contentWindowInsets = if (listScreen) ScaffoldDefaults.contentWindowInsets else WindowInsets(0),
        ) { padding ->
            NavHost(
                navController = navController,
                startDestination = DOCUMENTS_ROUTE,
                modifier = Modifier.padding(padding),
            ) {
                LIST_ROUTES.forEach { (path, mode) ->
                    composable(
                        route = path,
                        arguments =
                            listOf(
                                navArgument(DocumentListViewModel.MODE_ARGUMENT) {
                                    type = NavType.StringType
                                    defaultValue = mode.name
                                },
                            ),
                    ) {
                        DocumentListScreen(
                            onOpenDocument = { navController.navigate("document/${it.id}") },
                            onRefresh = inbox::refresh,
                        )
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
                composable(
                    EDIT_ROUTE,
                    arguments =
                        listOf(
                            navArgument(DocumentEditViewModel.DOCUMENT_ID_ARGUMENT) { type = NavType.LongType },
                        ),
                ) {
                    DocumentEditScreen(onClose = { navController.popBackStack() })
                }
            }
        }
    }
}

/** Shown only when documents wait in the inbox, with their number. */
@Composable
private fun InboxButton(
    count: Int,
    onClick: () -> Unit,
) {
    val description = pluralStringResource(R.plurals.inbox_button, count, count)
    IconButton(onClick = onClick) {
        BadgedBox(badge = { Badge { Text(if (count > MAX_BADGE) "$MAX_BADGE+" else count.toString()) } }) {
            Icon(Icons.Outlined.Inbox, contentDescription = description)
        }
    }
}

private const val MAX_BADGE = 99

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
