package com.naoya0117.paperless

import androidx.lifecycle.SavedStateHandle
import com.naoya0117.paperless.core.data.AccountRepository
import com.naoya0117.paperless.core.data.PaperlessClientFactory
import com.naoya0117.paperless.core.datastore.AccountStore
import com.naoya0117.paperless.core.datastore.SecretCipher
import com.naoya0117.paperless.core.model.AccountTls
import com.naoya0117.paperless.core.model.AuthToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.Proxy

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AppViewModelTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val server = MockWebServer()
    private lateinit var accounts: AccountRepository

    private object XorCipher : SecretCipher {
        override fun encrypt(plain: ByteArray) = plain.map { (it.toInt() xor 0x33).toByte() }.toByteArray()

        override fun decrypt(encrypted: ByteArray) = encrypt(encrypted)
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    // The user follows the token, so that each token signs in to another account.
                    val user = request.headers["Authorization"].orEmpty().substringAfter("Token ")
                    return MockResponse
                        .Builder()
                        .addHeader("Content-Type", "application/json")
                        .addHeader("X-Api-Version", "10")
                        .body("""{"user": {"id": ${user.length}, "username": "$user"}, "settings": {}}""")
                        .build()
                }
            }
        server.start()
        accounts =
            AccountRepository(
                AccountStore.create(folder.root.resolve("accounts.json"), XorCipher),
                PaperlessClientFactory(OkHttpClient.Builder().proxy(Proxy.NO_PROXY).build()),
                { error("no client certificates in this test") },
                {},
            )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        server.close()
    }

    private suspend fun signIn(user: String) =
        accounts.signInWithToken(server.url("/").toString(), AuthToken(user), tls = AccountTls())

    private suspend fun <T> Flow<T>.await(predicate: (T) -> Boolean): T =
        withContext(Dispatchers.Default.limitedParallelism(1)) { withTimeout(10_000) { first(predicate) } }

    @Test
    fun `adding an account shows a login that can be cancelled, and signing in ends it`() =
        runTest {
            val alice = signIn("alice")
            val viewModel = AppViewModel(accounts, SavedStateHandle())
            viewModel.screen.await { it is AppScreen.Home }

            viewModel.addAccount()
            val first = viewModel.screen.await { it is AppScreen.AddAccount } as AppScreen.AddAccount
            viewModel.addAccountDone()
            assertEquals(alice.id, (viewModel.screen.await { it is AppScreen.Home } as AppScreen.Home).account.id)

            // A new attempt gets a fresh form.
            viewModel.addAccount()
            val second = viewModel.screen.await { it is AppScreen.AddAccount } as AppScreen.AddAccount
            assertTrue(second.attempt > first.attempt)
            val bob = signIn("bob")
            viewModel.addAccountDone()
            assertEquals(bob.id, (viewModel.screen.await { it is AppScreen.Home } as AppScreen.Home).account.id)
            assertEquals(2, viewModel.accountList.await { it.size == 2 }.size)
        }

    @Test
    fun `switching accounts changes the signed-in screen`() =
        runTest {
            val alice = signIn("alice")
            signIn("bob")
            val viewModel = AppViewModel(accounts, SavedStateHandle())
            viewModel.screen.await { it is AppScreen.Home && it.account.username == "bob" }

            viewModel.switchTo(alice.id)

            viewModel.screen.await { it is AppScreen.Home && it.account.id == alice.id }
        }

    @Test
    fun `adding an account survives process death`() =
        runTest {
            signIn("alice")
            val savedState = SavedStateHandle()
            AppViewModel(accounts, savedState).addAccount()

            val restored = AppViewModel(accounts, savedState)

            restored.screen.await { it is AppScreen.AddAccount }
        }
}
