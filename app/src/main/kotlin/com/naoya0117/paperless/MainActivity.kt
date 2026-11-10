package com.naoya0117.paperless

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.naoya0117.paperless.core.model.Account
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
        is AppScreen.Login -> LoginScreen(reSignInAccountId = current.reSignInAccountId, onSignedIn = {})
        is AppScreen.Home -> HomePlaceholder(current.account, onSignOut = { viewModel.signOut(current.account.id) })
    }
}

/** Stands in for the document list until it exists. */
@Composable
private fun HomePlaceholder(
    account: Account,
    onSignOut: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.home_signed_in_as, account.displayName ?: account.username, account.serverUrl))
        account.serverVersion?.let { Text(stringResource(R.string.home_server_version, it)) }
        OutlinedButton(onClick = onSignOut) { Text(stringResource(R.string.home_sign_out)) }
    }
}

@Preview
@Composable
private fun HomePlaceholderPreview() {
    PaperlessTheme {
        HomePlaceholder(
            Account(
                id = "a",
                serverUrl = "https://paperless.example.com/",
                userId = 3,
                username = "alice",
                displayName = "Alice",
                apiVersion = com.naoya0117.paperless.core.model.ApiVersion.V10,
                serverVersion = "3.4.2",
                customHeaderNames = emptyList(),
                needsSignIn = false,
            ),
            onSignOut = {},
        )
    }
}
