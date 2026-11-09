package com.naoya0117.paperless.core.datastore

import com.naoya0117.paperless.core.model.Account
import com.naoya0117.paperless.core.model.ApiVersion
import com.naoya0117.paperless.core.model.AuthToken
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.GeneralSecurityException

class AccountStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    /** Reversible but not plain text, so the test can check what reaches the disk. */
    private class XorCipher(
        var broken: Boolean = false,
    ) : SecretCipher {
        override fun encrypt(plain: ByteArray) = plain.map { (it.toInt() xor 0x5a).toByte() }.toByteArray()

        override fun decrypt(encrypted: ByteArray): ByteArray {
            if (broken) throw GeneralSecurityException("key lost")
            return encrypted.map { (it.toInt() xor 0x5a).toByte() }.toByteArray()
        }
    }

    private fun account(
        id: String,
        username: String = "alice",
    ) = Account(
        id,
        "https://paperless.example.com/",
        3,
        username,
        "Alice",
        ApiVersion.V10,
        "3.4.2",
        listOf("X-Secret"),
        false,
    )

    private val credentials = AccountCredentials(AuthToken("0123456789abcdef"), listOf("X-Secret" to "s3cret"))

    private fun store(
        file: java.io.File,
        cipher: SecretCipher,
    ) = AccountStore.create(file, cipher)

    @Test
    fun `saves accounts and keeps secrets encrypted on disk`() =
        runTest {
            val file = folder.root.resolve("accounts.json")
            val store = store(file, XorCipher())

            store.save(account("a"), credentials)

            val state = store.state.first()
            assertEquals(listOf(account("a")), state.accounts)
            assertEquals("a", state.activeAccountId)
            assertEquals(credentials, store.credentials("a"))
            val onDisk = file.readText()
            assertFalse("token must not be stored in plain text", "0123456789abcdef" in onDisk)
            assertFalse("header values must not be stored in plain text", "s3cret" in onDisk)
            assertTrue("header names are not secret", "X-Secret" in onDisk)
        }

    @Test
    fun `saving the same account replaces it and the last saved becomes active`() =
        runTest {
            val store = store(folder.root.resolve("accounts.json"), XorCipher())

            store.save(account("a"), credentials)
            store.save(account("b", username = "bob"), credentials)
            store.save(account("a").copy(displayName = "Alice A."), AccountCredentials(AuthToken("newtoken")))

            val state = store.state.first()
            assertEquals(listOf("b", "a"), state.accounts.map { it.id })
            assertEquals("Alice A.", state.activeAccount?.displayName)
            assertEquals(AuthToken("newtoken"), store.credentials("a")?.token)
        }

    @Test
    fun `switches and removes accounts`() =
        runTest {
            val store = store(folder.root.resolve("accounts.json"), XorCipher())
            store.save(account("a"), credentials)
            store.save(account("b"), credentials)

            store.setActive("a")
            assertEquals("a", store.state.first().activeAccountId)

            store.remove("a")
            val state = store.state.first()
            assertEquals(listOf("b"), state.accounts.map { it.id })
            assertEquals("b", state.activeAccountId)
            assertNull(store.credentials("a"))

            store.remove("b")
            assertNull(store.state.first().activeAccount)
        }

    @Test
    fun `unknown accounts cannot become active`() =
        runTest {
            val store = store(folder.root.resolve("accounts.json"), XorCipher())

            assertTrue(runCatching { store.setActive("nope") }.exceptionOrNull() is IllegalArgumentException)
        }

    @Test
    fun `accounts whose secrets cannot be decrypted need to sign in again`() =
        runTest {
            val cipher = XorCipher()
            val store = store(folder.root.resolve("accounts.json"), cipher)
            store.save(account("a"), credentials)

            cipher.broken = true

            assertTrue(
                store.state
                    .first()
                    .accounts
                    .single()
                    .needsSignIn,
            )
            assertNull(store.credentials("a"))
        }

    @Test
    fun `a corrupt file starts over instead of failing`() =
        runTest {
            val file = folder.root.resolve("accounts.json")
            file.writeText("{not json")

            val store = store(file, XorCipher())

            assertEquals(emptyList<Account>(), store.state.first().accounts)
        }
}
