package com.naoya0117.paperless

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CenterAlignedTopAppBar
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
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
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

    val route: String get() = "documents/${mode.name}"
}

private const val DOCUMENT_ROUTE = "document/{id}"

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
    val tab =
        Tab.entries.firstOrNull {
            backStack?.arguments?.getString(DocumentListViewModel.MODE_ARGUMENT) ==
                it.mode.name
        }
    val drawerState = rememberDrawerState(androidx.compose.material3.DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    ModalNavigationDrawer(
        drawerState = drawerState,
        // Only from the tabs; elsewhere the edge swipe is the system's back gesture.
        gesturesEnabled = tab != null,
        drawerContent = {
            Drawer(account, onSignOut = {
                scope.launch { drawerState.close() }
                onSignOut()
            })
        },
    ) {
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text(stringResource(tab?.label ?: R.string.app_name)) },
                    navigationIcon = {
                        if (tab != null) {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Outlined.Menu, contentDescription = stringResource(R.string.menu_open))
                            }
                        } else if (route == DOCUMENT_ROUTE) {
                            IconButton(onClick = { navController.popBackStack() }) {
                                Icon(
                                    Icons.AutoMirrored.Outlined.ArrowBack,
                                    contentDescription = stringResource(R.string.navigate_back),
                                )
                            }
                        }
                    },
                )
            },
            bottomBar = {
                if (tab != null) {
                    NavigationBar {
                        Tab.entries.forEach { item ->
                            NavigationBarItem(
                                selected = item == tab,
                                onClick = {
                                    navController.navigate(item.route) {
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
            },
        ) { padding ->
            NavHost(
                navController = navController,
                startDestination = Tab.DOCUMENTS.route,
                modifier = Modifier.padding(padding),
            ) {
                composable(
                    route = "documents/{${DocumentListViewModel.MODE_ARGUMENT}}",
                    arguments = listOf(navArgument(DocumentListViewModel.MODE_ARGUMENT) { type = NavType.StringType }),
                ) {
                    DocumentListScreen(onOpenDocument = { document ->
                        navController.navigate("document/${document.id}")
                    })
                }
                composable(DOCUMENT_ROUTE, arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                    // Replaced by the viewer (next issue).
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
