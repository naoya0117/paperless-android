package com.naoya0117.paperless

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.naoya0117.paperless.feature.login.LoginScreen
import com.naoya0117.paperless.ui.PaperlessTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PaperlessTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    App()
                }
            }
        }
    }
}

@Composable
private fun App(viewModel: AppViewModel = hiltViewModel()) {
    val screen by viewModel.screen.collectAsStateWithLifecycle()
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
            )
        is AppScreen.Home ->
            // A fresh shell per account; its view models live in a store the app view model clears.
            key(current.account.id) {
                val store = viewModel.shellViewModelStore(current.account.id)
                val activity = checkNotNull(LocalActivity.current as? ComponentActivity) { "Not in an activity" }
                val owner = remember(store, activity) { ShellViewModelStoreOwner(store, activity) }
                CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                    MainShell(current.account, onSignOut = { viewModel.signOut(current.account.id) })
                }
            }
    }
}

/**
 * Holds the view models of the signed-in screens in [store], but creates them like the activity does: Hilt
 * view models need the activity's factory and creation extras (e.g. for their SavedStateHandle). A bare
 * [ViewModelStoreOwner] has neither, and creating a Hilt view model with it crashes.
 */
private class ShellViewModelStoreOwner(
    override val viewModelStore: ViewModelStore,
    private val activity: ComponentActivity,
) : ViewModelStoreOwner,
    HasDefaultViewModelProviderFactory {
    override val defaultViewModelProviderFactory: ViewModelProvider.Factory
        get() = activity.defaultViewModelProviderFactory

    override val defaultViewModelCreationExtras: CreationExtras
        get() = activity.defaultViewModelCreationExtras
}
