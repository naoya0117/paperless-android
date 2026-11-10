package com.naoya0117.paperless

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
        is AppScreen.Home -> MainShell(current.account, onSignOut = { viewModel.signOut(current.account.id) })
    }
}
