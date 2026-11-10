package com.naoya0117.paperless

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.IntentCompat
import androidx.core.os.BundleCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.DEFAULT_ARGS_KEY
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.VIEW_MODEL_STORE_OWNER_KEY
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.MutableCreationExtras
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.naoya0117.paperless.feature.login.LoginScreen
import com.naoya0117.paperless.ui.PaperlessTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    /** Files shared to the app and not handled yet. */
    private val sharedFiles = mutableStateOf<List<Uri>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        sharedFiles.value =
            if (savedInstanceState != null) {
                // Recreated (e.g. rotated): only what was not handled yet.
                BundleCompat.getParcelableArrayList(savedInstanceState, PENDING_SHARED_FILES, Uri::class.java).orEmpty()
            } else if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) {
                // Reopened from the recent apps: the share in the intent was handled before.
                emptyList()
            } else {
                received(intent)
            }
        setContent {
            PaperlessTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    App(sharedFiles = sharedFiles.value, onSharedFilesHandled = { sharedFiles.value = emptyList() })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        received(intent).takeIf { it.isNotEmpty() }?.let { sharedFiles.value = it }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putParcelableArrayList(PENDING_SHARED_FILES, ArrayList(sharedFiles.value))
    }

    /** The files shared by [intent]; says so if a share has none (e.g. a link shared as text). */
    private fun received(intent: Intent): List<Uri> {
        val uris = intent.sharedUris()
        if (uris.isEmpty() && intent.action in SHARE_ACTIONS) {
            Toast.makeText(this, R.string.share_no_files, Toast.LENGTH_LONG).show()
        }
        return uris
    }

    private companion object {
        const val PENDING_SHARED_FILES = "pending_shared_files"
        val SHARE_ACTIONS = setOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)
    }
}

private fun Intent.sharedUris(): List<Uri> =
    when (action) {
        Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(this, Intent.EXTRA_STREAM, Uri::class.java))
        Intent.ACTION_SEND_MULTIPLE ->
            IntentCompat.getParcelableArrayListExtra(this, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        else -> emptyList()
    }

@Composable
private fun App(
    sharedFiles: List<Uri>,
    onSharedFilesHandled: () -> Unit,
    viewModel: AppViewModel = hiltViewModel(),
) {
    val screen by viewModel.screen.collectAsStateWithLifecycle()
    val accounts by viewModel.accountList.collectAsStateWithLifecycle()
    // Without an account the files cannot be uploaded; say so rather than dropping them silently.
    val context = LocalContext.current
    if (screen is AppScreen.Login && sharedFiles.isNotEmpty()) {
        LaunchedEffect(sharedFiles) {
            Toast.makeText(context, R.string.share_sign_in_first, Toast.LENGTH_LONG).show()
            onSharedFilesHandled()
        }
    }
    // Left the signed-in screens: their view models must stop (e.g. lists still loading).
    if (screen is AppScreen.Login) LaunchedEffect(Unit) { viewModel.releaseShell() }
    when (val current = screen) {
        AppScreen.Loading ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        // The account list drives the screen: once signed in, Home follows by itself.
        is AppScreen.Login ->
            LoginScreen(
                reSignInAccountId = current.reSignInAccountId,
                onSignedIn = {},
                onRemoveAccount = viewModel::signOut,
                onCancel = current.otherAccountId?.let { other -> { viewModel.switchTo(other) } },
                // Per account: a form left for another account does not come back for this one.
                viewModel = hiltViewModel(key = "login-${current.reSignInAccountId}"),
            )
        is AppScreen.Home ->
            // A fresh shell per account; its view models live in a store the app view model clears.
            key(current.account.id) {
                val store = viewModel.shellViewModelStore(current.account.id)
                val activity = checkNotNull(LocalActivity.current as? ComponentActivity) { "Not in an activity" }
                val owner = remember(store, activity) { ShellViewModelStoreOwner(store, activity) }
                CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                    MainShell(
                        account = current.account,
                        // Held while adding an account: handled once the screens are visible again.
                        sharedFiles = if (current.addingAccount == null) sharedFiles else emptyList(),
                        onSharedFilesHandled = onSharedFilesHandled,
                        accounts = accounts,
                        onSwitchAccount = viewModel::switchTo,
                        onAddAccount = viewModel::addAccount,
                        onSignOut = { viewModel.signOut(current.account.id) },
                    )
                }
            }
    }
    val adding = (screen as? AppScreen.Home)?.addingAccount
    if (adding != null) {
        // Over the signed-in screens, which keep their state; a form of its own, cleared when done.
        key(adding) {
            val activity = checkNotNull(LocalActivity.current as? ComponentActivity) { "Not in an activity" }
            val store = remember { viewModel.addAccountViewModelStore() }
            val owner = remember(store, activity) { ShellViewModelStoreOwner(store, activity) }
            // Cleared once the form is gone (not while it may still create view models in it).
            DisposableEffect(store) { onDispose { store.clear() } }
            // Back never reaches the screens underneath; the form's own handlers (registered later) come first.
            BackHandler {}
            Surface(Modifier.fillMaxSize()) {
                CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                    LoginScreen(
                        reSignInAccountId = null,
                        onSignedIn = viewModel::accountAdded,
                        onRemoveAccount = {},
                        onCancel = viewModel::addAccountDone,
                    )
                }
            }
        }
    }
}

/**
 * Holds the view models of the signed-in screens in [store], but creates them like the activity does: Hilt
 * view models need the activity's factory and creation extras (e.g. for their SavedStateHandle). A bare
 * [ViewModelStoreOwner] has neither, and creating a Hilt view model with it crashes. Their saved state handles
 * live in [store] too (cleared with it, so nothing carries over to another account), and they get no
 * arguments from the activity's intent.
 */
private class ShellViewModelStoreOwner(
    override val viewModelStore: ViewModelStore,
    private val activity: ComponentActivity,
) : ViewModelStoreOwner,
    HasDefaultViewModelProviderFactory {
    override val defaultViewModelProviderFactory: ViewModelProvider.Factory
        get() = activity.defaultViewModelProviderFactory

    override val defaultViewModelCreationExtras: CreationExtras
        get() =
            MutableCreationExtras(activity.defaultViewModelCreationExtras).apply {
                set(VIEW_MODEL_STORE_OWNER_KEY, this@ShellViewModelStoreOwner)
                set(DEFAULT_ARGS_KEY, Bundle())
            }
}
