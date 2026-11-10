package com.naoya0117.paperless

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
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

        private var shellAccountId: String? = null
        private var shellStore = ViewModelStore()

        /**
         * Holds the view models of the signed-in screens (e.g. the document lists) of one account. It survives
         * configuration changes (it lives in this view model) and is cleared when the account changes or the
         * app leaves its signed-in screens, so that no list keeps loading for an account that is gone.
         */
        fun shellViewModelStore(accountId: String): ViewModelStore {
            if (shellAccountId != accountId) {
                releaseShell()
                shellAccountId = accountId
            }
            return shellStore
        }

        fun releaseShell() {
            shellStore.clear()
            shellStore = ViewModelStore()
            shellAccountId = null
        }

        override fun onCleared() = shellStore.clear()

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }
