package com.naoya0117.paperless.core.testing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * The view models of a test, and the scopes their work outlives them in, to [clear] at the end of the test before
 * `Dispatchers.resetMain()`: a coroutine resuming on another thread while Main is reset (or set by the next test)
 * fails with "Dispatchers.Main is used concurrently with setting it".
 */
class TestViewModels {
    @PublishedApi
    internal val store = ViewModelStore()

    private val scopes = mutableListOf<CoroutineScope>()

    /** Creates a view model that is cleared (`onCleared`, its scope cancelled) and awaited by [clear]. */
    inline fun <reified VM : ViewModel> create(crossinline create: () -> VM): VM =
        ViewModelProvider
            .create(store, viewModelFactory { initializer { create() } })["vm${store.keys().size}", VM::class]
            .also { track(it.viewModelScope) }

    /** A scope for work that outlives a view model (like the app's application scope), cancelled by [clear]. */
    fun applicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also(::track)

    @PublishedApi
    internal fun track(scope: CoroutineScope) {
        scopes += scope
    }

    /** Clears the view models, cancels the scopes and waits until all their coroutines are done. */
    fun clear() {
        store.clear()
        scopes.forEach { it.cancel() }
        runBlocking { withTimeout(10_000) { scopes.map { it.coroutineContext.job }.joinAll() } }
    }
}
