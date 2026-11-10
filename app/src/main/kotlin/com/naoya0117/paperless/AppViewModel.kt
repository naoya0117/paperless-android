package com.naoya0117.paperless

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naoya0117.paperless.core.data.AccountRepository
import com.naoya0117.paperless.core.model.Account
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the app shows at the top level. */
sealed interface AppScreen {
    data object Loading : AppScreen

    /** No account yet, or [reSignInAccountId] must sign in again. */
    data class Login(
        val reSignInAccountId: String?,
    ) : AppScreen

    data class Home(
        val account: Account,
    ) : AppScreen
}

@HiltViewModel
class AppViewModel
    @Inject
    constructor(
        private val accounts: AccountRepository,
    ) : ViewModel() {
        val screen: StateFlow<AppScreen> =
            accounts.state
                .map { state ->
                    val active = state.activeAccount
                    when {
                        active == null -> AppScreen.Login(reSignInAccountId = null)
                        active.needsSignIn -> AppScreen.Login(reSignInAccountId = active.id)
                        else -> AppScreen.Home(active)
                    }
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), AppScreen.Loading)

        fun signOut(accountId: String) {
            viewModelScope.launch { accounts.signOut(accountId) }
        }

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }
