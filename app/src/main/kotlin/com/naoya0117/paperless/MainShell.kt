package com.naoya0117.paperless

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.naoya0117.paperless.core.model.Account
import com.naoya0117.paperless.core.model.SavedView
import com.naoya0117.paperless.feature.documents.DocumentEditScreen
import com.naoya0117.paperless.feature.documents.DocumentEditViewModel
import com.naoya0117.paperless.feature.documents.DocumentListMode
import com.naoya0117.paperless.feature.documents.DocumentListScreen
import com.naoya0117.paperless.feature.documents.DocumentListViewModel
import com.naoya0117.paperless.feature.documents.DocumentViewerScreen
import com.naoya0117.paperless.feature.documents.DocumentViewerViewModel
import com.naoya0117.paperless.feature.documents.InboxViewModel
import com.naoya0117.paperless.feature.documents.TrashScreen
import com.naoya0117.paperless.feature.labels.LabelEditScreen
import com.naoya0117.paperless.feature.labels.LabelEditViewModel
import com.naoya0117.paperless.feature.labels.LabelKind
import com.naoya0117.paperless.feature.labels.LabelListScreen
import com.naoya0117.paperless.feature.labels.LabelListViewModel
import com.naoya0117.paperless.feature.uploads.EnqueueResult
import com.naoya0117.paperless.feature.uploads.UploadsScreen
import com.naoya0117.paperless.feature.uploads.UploadsViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val DOCUMENTS_ROUTE = "documents"
private const val SEARCH_ROUTE = "search"
private const val INBOX_ROUTE = "inbox"
private const val UPLOADS_ROUTE = "uploads"
private const val TRASH_ROUTE = "trash"
private const val VIEW_ROUTE = "view/{${DocumentListViewModel.VIEW_ID_ARGUMENT}}"
private const val LABELS_ROUTE = "labels/{${LabelListViewModel.KIND_ARGUMENT}}"
private const val LABEL_EDIT_ROUTE =
    "labels/{${LabelListViewModel.KIND_ARGUMENT}}/edit?${LabelEditViewModel.LABEL_ID_ARGUMENT}={${LabelEditViewModel.LABEL_ID_ARGUMENT}}"

/** The bottom tabs: only what is used most; everything else is in the drawer (hamburger menu). */
private enum class Tab(
    val route: String,
    val mode: DocumentListMode,
    val icon: ImageVector,
    val label: Int,
) {
    DOCUMENTS(DOCUMENTS_ROUTE, DocumentListMode.ALL, Icons.Outlined.Description, R.string.title_documents),
    SEARCH(SEARCH_ROUTE, DocumentListMode.SEARCH, Icons.Outlined.Search, R.string.title_search),
}

/** The document lists: one per tab, and the inbox (opened from the documents tab). */
private val LIST_ROUTES = Tab.entries.map { it.route to it.mode } + (INBOX_ROUTE to DocumentListMode.INBOX)

private val TAB_ROUTES = Tab.entries.map { it.route }.toSet()

private const val DOCUMENT_ROUTE = "document/{${DocumentViewerViewModel.DOCUMENT_ID_ARGUMENT}}"
private const val EDIT_ROUTE = "document/{${DocumentEditViewModel.DOCUMENT_ID_ARGUMENT}}/edit"

/**
 * The signed-in app: bottom tabs (documents, search) under a top bar with the drawer (hamburger menu) and, on
 * the documents tab, the inbox button; and the screens opened from them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainShell(
    account: Account,
    sharedFiles: List<Uri>,
    onSharedFilesHandled: () -> Unit,
    accounts: List<Account>,
    onSwitchAccount: (String) -> Unit,
    onAddAccount: () -> Unit,
    onSignOut: () -> Unit,
    inbox: InboxViewModel = hiltViewModel(),
    uploads: UploadsViewModel = hiltViewModel(),
    drawer: DrawerViewModel = hiltViewModel(),
) {
    val savedViews by drawer.savedViews.collectAsStateWithLifecycle()
    val allSavedViews by drawer.allSavedViews.collectAsStateWithLifecycle()
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val tab = Tab.entries.firstOrNull { it.route == route }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val inboxCount by inbox.count.collectAsStateWithLifecycle()
    // The count may have changed (e.g. documents checked in the inbox) whenever the list shows again.
    LaunchedEffect(route) { if (route == DOCUMENTS_ROUTE) inbox.refresh() }
    // Also when coming back to the app (e.g. documents were checked in the web interface meanwhile).
    LifecycleResumeEffect(route) {
        if (route == DOCUMENTS_ROUTE) inbox.refresh()
        onPauseOrDispose {}
    }
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

    val snackbar = remember { SnackbarHostState() }
    val enqueue = rememberUploadStarter(uploads)
    // Files shared to the app: queued at once (the permission to read them ends with the activity).
    LaunchedEffect(sharedFiles) {
        if (sharedFiles.isNotEmpty()) {
            enqueue(sharedFiles)
            // On a cold start the NavHost (inside the Scaffold) may not have its graph yet. Marked handled only
            // afterwards: that changes the key, which would cancel this effect.
            navController.currentBackStackEntryFlow.first()
            navController.navigate(UPLOADS_ROUTE) { launchSingleTop = true }
            onSharedFilesHandled()
        }
    }
    val resources = LocalResources.current
    LaunchedEffect(uploads) {
        uploads.enqueueResults.collect { result ->
            val message =
                when (result) {
                    is EnqueueResult.Queued ->
                        resources.getQuantityString(
                            R.plurals.uploads_queued_message,
                            result.count,
                            result.count,
                        )
                    is EnqueueResult.PartlyFailed ->
                        resources.getString(
                            R.string.uploads_partly_failed_message,
                            result.queued,
                            result.failed,
                        )
                    is EnqueueResult.Failed ->
                        resources.getQuantityString(R.plurals.uploads_failed_message, result.count, result.count)
                    EnqueueResult.NotSignedIn -> resources.getString(R.string.share_sign_in_first)
                }
            val showsUploads = navController.currentDestination?.route == UPLOADS_ROUTE
            val queuedAny = result is EnqueueResult.Queued || result is EnqueueResult.PartlyFailed
            val action = if (queuedAny && !showsUploads) resources.getString(R.string.uploads_view_action) else null
            if (snackbar.showSnackbar(message, actionLabel = action) == SnackbarResult.ActionPerformed) {
                navController.navigate(UPLOADS_ROUTE) { launchSingleTop = true }
            }
        }
    }
    val pickFiles =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> enqueue(uris) }

    ModalNavigationDrawer(
        drawerState = drawerState,
        // Only from the list; elsewhere the edge swipe is the system's back gesture.
        gesturesEnabled = tab != null,
        drawerContent = {
            Drawer(
                account = account,
                otherAccounts = accounts.filter { it.id != account.id },
                onSwitchAccount = { id ->
                    scope.launch { drawerState.close() }
                    onSwitchAccount(id)
                },
                onAddAccount = {
                    scope.launch { drawerState.close() }
                    onAddAccount()
                },
                onUploads = {
                    scope.launch { drawerState.close() }
                    if (navController.currentDestination?.route in TAB_ROUTES) navController.navigate(UPLOADS_ROUTE)
                },
                onTrash = {
                    scope.launch { drawerState.close() }
                    if (navController.currentDestination?.route in TAB_ROUTES) navController.navigate(TRASH_ROUTE)
                },
                savedViews = savedViews,
                onSavedView = { view ->
                    scope.launch { drawerState.close() }
                    // Once only: a second tap while the menu closes must not open another screen on top.
                    if (navController.currentDestination?.route in TAB_ROUTES) navController.navigate("view/${view.id}")
                },
                onLabels = { kind ->
                    scope.launch { drawerState.close() }
                    if (navController.currentDestination?.route in
                        TAB_ROUTES
                    ) {
                        navController.navigate("labels/${kind.key}")
                    }
                },
                onSignOut = {
                    scope.launch { drawerState.close() }
                    confirmSignOut = true
                },
            )
        },
    ) {
        val secondaryTitle =
            when (route) {
                INBOX_ROUTE -> R.string.title_inbox
                UPLOADS_ROUTE -> R.string.drawer_uploads
                VIEW_ROUTE -> R.string.drawer_views
                else -> null
            }
        // A saved view is titled with its name.
        val viewName =
            if (route == VIEW_ROUTE) {
                val id = backStack?.arguments?.getLong(DocumentListViewModel.VIEW_ID_ARGUMENT)
                allSavedViews.firstOrNull { it.id == id }?.name
            } else {
                null
            }
        val listScreen = tab != null || secondaryTitle != null
        Scaffold(
            // The lists share this bar; other screens (e.g. the viewer) bring their own.
            topBar = {
                when (route) {
                    DOCUMENTS_ROUTE, SEARCH_ROUTE ->
                        CenterAlignedTopAppBar(
                            title = { Text(stringResource(checkNotNull(tab).label)) },
                            navigationIcon = {
                                IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                    Icon(Icons.Outlined.Menu, contentDescription = stringResource(R.string.menu_open))
                                }
                            },
                            actions = {
                                // The inbox belongs to the documents tab.
                                if (route == DOCUMENTS_ROUTE && inboxCount > 0) {
                                    InboxButton(inboxCount) {
                                        navController.navigate(INBOX_ROUTE) {
                                            launchSingleTop =
                                                true
                                        }
                                    }
                                }
                            },
                        )
                    INBOX_ROUTE, UPLOADS_ROUTE, VIEW_ROUTE ->
                        CenterAlignedTopAppBar(
                            title = {
                                Text(
                                    viewName ?: stringResource(checkNotNull(secondaryTitle)),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            navigationIcon = {
                                // navigateUp never leaves the start destination (e.g. on a double tap).
                                IconButton(onClick = { navController.navigateUp() }) {
                                    Icon(
                                        Icons.AutoMirrored.Outlined.ArrowBack,
                                        contentDescription = stringResource(R.string.back),
                                    )
                                }
                            },
                        )
                }
            },
            bottomBar = { if (tab != null) TabBar(tab, navController) },
            snackbarHost = { SnackbarHost(snackbar) },
            floatingActionButton = {
                if (route == DOCUMENTS_ROUTE) {
                    FloatingActionButton(onClick = { pickFiles.launch(UPLOAD_MIME_TYPES) }) {
                        Icon(Icons.Outlined.UploadFile, contentDescription = stringResource(R.string.upload_files))
                    }
                }
            },
            // Other screens (e.g. the viewer) are a Scaffold of their own and handle the system bars themselves.
            contentWindowInsets = if (listScreen) ScaffoldDefaults.contentWindowInsets else WindowInsets(0),
        ) { padding ->
            NavHost(
                navController = navController,
                startDestination = DOCUMENTS_ROUTE,
                modifier = Modifier.padding(padding),
                // One animation for both back button and back gesture (predictive back plays the pop transitions):
                // screens slide in from the right and back out to it. Between tabs they fade.
                enterTransition = {
                    if (initialState.destination.route in TAB_ROUTES && targetState.destination.route in TAB_ROUTES) {
                        fadeIn()
                    } else {
                        slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start)
                    }
                },
                exitTransition = {
                    if (initialState.destination.route in TAB_ROUTES && targetState.destination.route in TAB_ROUTES) {
                        fadeOut()
                    } else {
                        slideOutOfContainer(
                            AnimatedContentTransitionScope.SlideDirection.Start,
                            targetOffset = { it / 4 },
                        ) +
                            fadeOut()
                    }
                },
                popEnterTransition = {
                    slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.End, initialOffset = { it / 4 }) +
                        fadeIn()
                },
                popExitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End) },
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
                            // The inbox count is shown on the documents tab only.
                            onRefresh = { if (path == DOCUMENTS_ROUTE) inbox.refresh(force = true) },
                        )
                    }
                }
                composable(
                    VIEW_ROUTE,
                    arguments =
                        listOf(
                            navArgument(DocumentListViewModel.MODE_ARGUMENT) {
                                type = NavType.StringType
                                defaultValue = DocumentListMode.VIEW.name
                            },
                            navArgument(DocumentListViewModel.VIEW_ID_ARGUMENT) { type = NavType.LongType },
                        ),
                ) {
                    DocumentListScreen(onOpenDocument = { navController.navigate("document/${it.id}") })
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
                composable(
                    LABELS_ROUTE,
                    arguments = listOf(navArgument(LabelListViewModel.KIND_ARGUMENT) { type = NavType.StringType }),
                ) { entry ->
                    LabelListScreen(
                        // Once only (e.g. not again when tapped twice during the exit animation).
                        onBack = dropUnlessResumed { navController.navigateUp() },
                        onOpen = { kind, id ->
                            if (entry.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                                navController.navigate("labels/${kind.key}/edit?id=${id ?: NEW_LABEL}")
                            }
                        },
                    )
                }
                composable(
                    LABEL_EDIT_ROUTE,
                    arguments =
                        listOf(
                            navArgument(LabelListViewModel.KIND_ARGUMENT) { type = NavType.StringType },
                            navArgument(LabelEditViewModel.LABEL_ID_ARGUMENT) {
                                type = NavType.LongType
                                defaultValue = NEW_LABEL
                            },
                        ),
                ) {
                    LabelEditScreen(onClose = dropUnlessResumed { navController.navigateUp() })
                }
                composable(TRASH_ROUTE) {
                    TrashScreen(onBack = dropUnlessResumed { navController.navigateUp() })
                }
                composable(UPLOADS_ROUTE) {
                    UploadsScreen(viewModel = uploads, onOpenDocument = { id ->
                        navController.navigate("document/$id")
                    })
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

/** Shown only when documents wait in the inbox, with their number. */
@Composable
private fun InboxButton(
    count: Int,
    onClick: () -> Unit,
) {
    val description = pluralStringResource(R.plurals.inbox_button, count, count)
    IconButton(onClick = onClick) {
        // One announcement with the number in words, not also the badge digits (the button keeps its click).
        BadgedBox(
            badge = { Badge { Text(if (count > MAX_BADGE) "$MAX_BADGE+" else count.toString()) } },
            modifier = Modifier.clearAndSetSemantics { contentDescription = description },
        ) {
            Icon(Icons.Outlined.Inbox, contentDescription = null)
        }
    }
}

private const val MAX_BADGE = 99

@Composable
private fun Drawer(
    account: Account,
    otherAccounts: List<Account>,
    onSwitchAccount: (String) -> Unit,
    onAddAccount: () -> Unit,
    onUploads: () -> Unit,
    onTrash: () -> Unit,
    savedViews: List<SavedView>,
    onSavedView: (SavedView) -> Unit,
    onLabels: (LabelKind) -> Unit,
    onSignOut: () -> Unit,
) {
    ModalDrawerSheet {
        // Scrolls on small screens (e.g. in landscape).
        Column(Modifier.verticalScroll(rememberScrollState())) {
            var accountsOpen by rememberSaveable { mutableStateOf(false) }
            val accountsState =
                stringResource(if (accountsOpen) R.string.drawer_accounts_shown else R.string.drawer_accounts_hidden)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .semantics { stateDescription = accountsState }
                        .clickable(onClickLabel = stringResource(R.string.drawer_accounts)) {
                            accountsOpen =
                                !accountsOpen
                        }.padding(start = 28.dp, end = 16.dp, top = 24.dp, bottom = 24.dp),
            ) {
                Column(Modifier.weight(1f)) {
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
                Icon(
                    if (accountsOpen) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = null,
                )
            }
            if (accountsOpen) {
                otherAccounts.forEach { other ->
                    NavigationDrawerItem(
                        label = {
                            Column {
                                Text(
                                    other.displayName ?: other.username,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    if (other.needsSignIn) {
                                        stringResource(R.string.drawer_account_sign_in, other.serverUrl)
                                    } else {
                                        other.serverUrl
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        },
                        icon = { Icon(Icons.Outlined.AccountCircle, contentDescription = null) },
                        selected = false,
                        onClick = { onSwitchAccount(other.id) },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.drawer_add_account)) },
                    icon = { Icon(Icons.Outlined.PersonAdd, contentDescription = null) },
                    selected = false,
                    onClick = onAddAccount,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
            HorizontalDivider()
            NavigationDrawerItem(
                label = { Text(stringResource(R.string.drawer_uploads)) },
                icon = { Icon(Icons.Outlined.CloudUpload, contentDescription = null) },
                selected = false,
                onClick = onUploads,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
            NavigationDrawerItem(
                label = { Text(stringResource(R.string.drawer_trash)) },
                icon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                selected = false,
                onClick = onTrash,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            HorizontalDivider()
            if (savedViews.isNotEmpty()) {
                Text(
                    stringResource(R.string.drawer_views),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 12.dp),
                )
                savedViews.forEach { view ->
                    NavigationDrawerItem(
                        label = { Text(view.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        icon = { Icon(Icons.Outlined.FilterList, contentDescription = null) },
                        selected = false,
                        onClick = { onSavedView(view) },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
                HorizontalDivider(Modifier.padding(top = 8.dp))
            }
            Text(
                stringResource(R.string.drawer_labels),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 12.dp),
            )
            LABEL_ITEMS.forEach { (kind, icon) ->
                NavigationDrawerItem(
                    label = { Text(stringResource(kind.title)) },
                    icon = { Icon(icon, contentDescription = null) },
                    selected = false,
                    onClick = { onLabels(kind) },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
            HorizontalDivider(Modifier.padding(top = 8.dp))
            NavigationDrawerItem(
                label = { Text(stringResource(R.string.home_sign_out)) },
                icon = { Icon(Icons.AutoMirrored.Outlined.Logout, contentDescription = null) },
                selected = false,
                onClick = onSignOut,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
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

/** A new label in the label editor (no ID). */
private const val NEW_LABEL = -1L

private val LABEL_ITEMS =
    listOf(
        LabelKind.TAGS to Icons.AutoMirrored.Outlined.Label,
        LabelKind.CORRESPONDENTS to Icons.Outlined.Person,
        LabelKind.DOCUMENT_TYPES to Icons.Outlined.Description,
        LabelKind.STORAGE_PATHS to Icons.Outlined.FolderOpen,
    )

/** What Paperless-ngx can consume; the same as the share targets in the manifest. */
private val UPLOAD_MIME_TYPES =
    arrayOf(
        "application/pdf",
        "image/*",
        "text/plain",
        "text/csv",
        "application/rtf",
        "application/msword",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.ms-excel",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.ms-powerpoint",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "application/vnd.oasis.opendocument.text",
        "application/vnd.oasis.opendocument.spreadsheet",
        "application/vnd.oasis.opendocument.presentation",
        "message/rfc822",
    )

private const val PREFERENCES = "ui"
private const val NOTIFICATION_PERMISSION_ASKED = "notification_permission_asked"

/**
 * Queues files for upload. Once (Android 13+) it asks for the notification shown while sending; uploads run
 * either way, so a refusal is not asked again.
 */
@Composable
private fun rememberUploadStarter(uploads: UploadsViewModel): (List<Uri>) -> Unit {
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    return remember(uploads, permission) {
        { uris ->
            if (uris.isNotEmpty()) {
                val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    !preferences.getBoolean(NOTIFICATION_PERMISSION_ASKED, false) &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    preferences.edit { putBoolean(NOTIFICATION_PERMISSION_ASKED, true) }
                    permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                uploads.enqueue(uris)
            }
        }
    }
}
