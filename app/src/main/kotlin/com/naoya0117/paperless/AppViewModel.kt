package com.naoya0117.paperless

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import com.naoya0117.paperless.core.data.AccountRepository
import com.naoya0117.paperless.core.model.Account
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
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
        /** An account to use instead (when the active one must sign in again); `null` if there is none. */
        val otherAccountId: String? = null,
    ) : AppScreen

    data class Home(
        val account: Account,
        /**
         * Signing in to another account is open over the signed-in screens (which stay as they are, e.g. if it
         * is cancelled); distinguishes each attempt. `null` if not adding an account.
         */
        val addingAccount: Int? = null,
    ) : AppScreen
}

@HiltViewModel
class AppViewModel
    @Inject
    constructor(
        private val accounts: AccountRepository,
        private val savedState: SavedStateHandle,
    ) : ViewModel() {
        /** The attempt of adding an account, if one is open (kept across process death). */
        private val adding = savedState.getStateFlow<Int?>(ADDING_KEY, null)

        val screen: StateFlow<AppScreen> =
            combine(accounts.state, adding) { state, attempt ->
                val active = state.activeAccount
                when {
                    active == null -> AppScreen.Login(reSignInAccountId = null)
                    active.needsSignIn ->
                        AppScreen.Login(
                            reSignInAccountId = active.id,
                            otherAccountId = state.accounts.firstOrNull { it.id != active.id && !it.needsSignIn }?.id,
                        )
                    else -> AppScreen.Home(active, attempt)
                }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), AppScreen.Loading)

        /** Every account on this device, e.g. to switch between them. */
        val accountList: StateFlow<List<Account>> =
            accounts.state
                .map { it.accounts }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

        /**
         * The login form of the account being added: a store of its own per attempt (each starts empty), cleared
         * by the screen once the form is gone. Not saved: after process death the form starts empty again.
         */
        private var addAccountStore = ViewModelStore()

        fun addAccountViewModelStore(): ViewModelStore = addAccountStore

        init {
            // Signed out or must sign in again meanwhile: adding ends (it would come back after the next login).
            viewModelScope.launch {
                accounts.state.collect { state ->
                    val active = state.activeAccount
                    if (adding.value != null && (active == null || active.needsSignIn)) addAccountDone()
                }
            }
        }

        fun switchTo(accountId: String) {
            viewModelScope.launch {
                try {
                    accounts.switchTo(accountId)
                } catch (e: CancellationException) {
                    throw e
                } catch (
                    @Suppress("TooGenericExceptionCaught") _: Exception,
                ) {
                    // Removed meanwhile (the list follows) or not storable: the current account stays.
                }
            }
        }

        fun addAccount() {
            if (adding.value != null) return
            val attempt = (savedState.get<Int>(LAST_ATTEMPT_KEY) ?: 0) + 1
            savedState[LAST_ATTEMPT_KEY] = attempt
            savedState[ADDING_KEY] = attempt
        }

        /**
         * Signed in to [account]: the form closes once it is the active account (not before, so that the
         * previous account's screens do not show up in between).
         */
        fun accountAdded(account: Account) {
            viewModelScope.launch {
                accounts.state.first { it.activeAccountId == account.id }
                addAccountDone()
            }
        }

        /** Cancelled (or signed in and active). */
        fun addAccountDone() {
            savedState[ADDING_KEY] = null
            // The next attempt gets a new store; the screen clears this one when the form is gone.
            addAccountStore = ViewModelStore()
        }

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

        override fun onCleared() {
            shellStore.clear()
            addAccountStore.clear()
        }

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L
            const val ADDING_KEY = "app.addingAccount"
            const val LAST_ATTEMPT_KEY = "app.lastAddAttempt"
        }
    }
