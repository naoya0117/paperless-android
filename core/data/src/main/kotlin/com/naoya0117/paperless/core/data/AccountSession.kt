package com.naoya0117.paperless.core.data

import com.naoya0117.paperless.core.model.Account
import com.naoya0117.paperless.core.network.PaperlessClient

/** An account together with the client for it, taken at the same moment. */
class AccountSession internal constructor(
    val account: Account,
    val client: PaperlessClient,
    /** How often the account had been signed out when the session was taken. */
    internal val epoch: Long,
)

/** Deletes what is stored on the device for an account besides the account itself (e.g. cached data). */
fun interface AccountDataCleaner {
    suspend fun clear(accountId: String)
}

/** Thrown by operations that need a signed-in account when there is none (or it needs to sign in again). */
class NotSignedInException : IllegalStateException("No signed-in account")
