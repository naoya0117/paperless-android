package com.naoya0117.paperless.feature.login

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naoya0117.paperless.core.data.AccountRepository
import com.naoya0117.paperless.core.data.TrustedCertificates
import com.naoya0117.paperless.core.model.Account
import com.naoya0117.paperless.core.model.AccountTls
import com.naoya0117.paperless.core.model.AuthToken
import com.naoya0117.paperless.core.model.TrustedCertificate
import com.naoya0117.paperless.core.network.HttpHeader
import com.naoya0117.paperless.core.network.PaperlessException
import com.naoya0117.paperless.core.network.ServerUrl
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

enum class LoginMethod { PASSWORD, TOKEN }

enum class LoginStep {
    CREDENTIALS,

    /** The account has two-factor authentication: enter the code from the authenticator app. */
    MFA_CODE,
}

enum class UrlProblem {
    /** Not an http(s) URL without query or fragment. */
    INVALID,
}

/** Why signing in failed; the screen turns it into a message. */
enum class LoginError {
    /** [LoginState.errorDetail] may hold the server's explanation. */
    INVALID_CREDENTIALS,
    INVALID_MFA_CODE,
    INVALID_TOKEN,

    /**
     * 401 while signing in with a password: Paperless itself answers 400 for wrong credentials, so this comes
     * from a proxy in front of it (check the custom headers).
     */
    PROXY_AUTHENTICATION,

    /** 403, often from a proxy in front of the server. */
    FORBIDDEN,

    /** Too many attempts; see [LoginState.retryAfterSeconds]. */
    RATE_LIMITED,

    /** 5xx, e.g. the server is down behind its reverse proxy. */
    SERVER_ERROR,

    /** The host name could not be resolved. */
    UNKNOWN_HOST,

    /** No connection (refused, timed out, offline). */
    CONNECTION_FAILED,

    /** The certificate is not trusted or the host name does not match; trusting a certificate may help. */
    TLS,

    /** The server answered with a redirect, e.g. http -> https or a login page; see [LoginState.redirectTarget]. */
    REDIRECTED,

    /** Not a Paperless-ngx server, or one too old or too new (API version). */
    UNSUPPORTED_SERVER,

    /** The header at [LoginState.invalidHeaderIndex] cannot be sent (empty name, bad characters or reserved). */
    INVALID_HEADER,

    /** The certificate data could not be read (when adding a trusted certificate). */
    INVALID_CERTIFICATE,

    OTHER,
}

/** An extra request header, e.g. for an authenticating proxy in front of the server. */
data class HeaderInput(
    val name: String,
    val value: String,
) {
    override fun toString(): String = "HeaderInput($name: ***)"
}

data class LoginState(
    val serverUrl: String = "",
    val method: LoginMethod = LoginMethod.PASSWORD,
    val username: String = "",
    val password: String = "",
    val token: String = "",
    val mfaCode: String = "",
    val step: LoginStep = LoginStep.CREDENTIALS,
    val headers: List<HeaderInput> = emptyList(),
    val tls: AccountTls = AccountTls(),
    /** Set once the URL was typed and is not valid. */
    val urlProblem: UrlProblem? = null,
    /** The URL uses plain HTTP: credentials and documents travel unencrypted. Allowed, but warn. */
    val cleartext: Boolean = false,
    /** Signing in; input is ignored meanwhile, so the screen should disable its fields. */
    val inProgress: Boolean = false,
    val error: LoginError? = null,
    /** A message from the server explaining [error] (in the server's language), if it sent one. */
    val errorDetail: String? = null,
    /** Where the server redirected to, for [LoginError.REDIRECTED]. */
    val redirectTarget: String? = null,
    /** For [LoginError.RATE_LIMITED], if the server said. */
    val retryAfterSeconds: Long? = null,
    /** For [LoginError.INVALID_HEADER]: the position in [headers]. */
    val invalidHeaderIndex: Int? = null,
    /** The account being signed in to again, if any (see [LoginViewModel.signInAgain]). */
    val reSignInAccountId: String? = null,
    /** Set when signing in succeeded; the screen leaves and calls [LoginViewModel.signedInHandled]. */
    val signedIn: Account? = null,
) {
    val canSubmit: Boolean
        get() =
            !inProgress &&
                signedIn == null &&
                serverUrl.isNotBlank() &&
                urlProblem == null &&
                when {
                    step == LoginStep.MFA_CODE -> mfaCode.isNotBlank()
                    method == LoginMethod.PASSWORD -> username.isNotBlank() && password.isNotEmpty()
                    else -> token.isNotBlank()
                }

    /** Secrets are left out, so that the state can be logged safely. */
    override fun toString(): String =
        listOf(
            "serverUrl=$serverUrl",
            "method=$method",
            "username=$username",
            "password=${mask(password)}",
            "token=${mask(token)}",
            "mfaCode=${mask(mfaCode)}",
            "step=$step",
            "headers=${headers.map { it.name }}",
            "tls=$tls",
            "urlProblem=$urlProblem",
            "cleartext=$cleartext",
            "inProgress=$inProgress",
            "error=$error",
            "errorDetail=$errorDetail",
            "redirectTarget=$redirectTarget",
            "retryAfterSeconds=$retryAfterSeconds",
            "invalidHeaderIndex=$invalidHeaderIndex",
            "reSignInAccountId=$reSignInAccountId",
            "signedIn=${signedIn?.id}",
        ).joinToString(prefix = "LoginState(", postfix = ")")

    private fun mask(value: String) = if (value.isEmpty()) "" else "***"
}

/**
 * Signing in to a Paperless-ngx server, independent of how the screen looks. Non-secret input survives the
 * process being killed (e.g. while the user looks up a code in another app); passwords, tokens, codes and
 * header values do not, and neither do trusted certificates (too large for saved state).
 */
@HiltViewModel
class LoginViewModel
    @Inject
    constructor(
        private val accounts: AccountRepository,
        private val savedState: SavedStateHandle,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow(restore())
        val state: StateFlow<LoginState> = mutableState.asStateFlow()

        /** The account [signInAgain] last prepared the form for; reset by a successful sign-in. */
        private var prefilledFor: String? = null

        /**
         * Prepares signing in again to an account (e.g. one that needs to sign in again): its server, user,
         * custom headers and TLS settings are kept. Only the first call per account has an effect, so it is safe
         * to call whenever the screen is shown.
         */
        fun signInAgain(accountId: String) {
            val current = mutableState.value
            if (current.inProgress || prefilledFor == accountId) return
            // Not saved: after process death the secret header values are read again.
            prefilledFor = accountId
            mutableState.value = LoginState(reSignInAccountId = accountId)
            viewModelScope.launch {
                val account =
                    accounts.state
                        .first()
                        .accounts
                        .firstOrNull { it.id == accountId } ?: return@launch
                val headers =
                    accounts.customHeaders(accountId).orEmpty().map { (name, value) ->
                        HeaderInput(name, value)
                    }
                mutableState.update { state ->
                    if (state.reSignInAccountId != accountId) return@update state
                    prefilled(state, account, headers)
                }
                save()
            }
        }

        fun setServerUrl(value: String) =
            edit {
                if (value ==
                    it.serverUrl
                ) {
                    it
                } else {
                    it.withServerUrl(value).startOver()
                }
            }

        fun setMethod(value: LoginMethod) = edit { if (value == it.method) it else it.copy(method = value).startOver() }

        fun setUsername(value: String) =
            edit {
                if (value ==
                    it.username
                ) {
                    it
                } else {
                    it.copy(username = value).startOver()
                }
            }

        fun setPassword(value: String) =
            edit {
                if (value ==
                    it.password
                ) {
                    it
                } else {
                    it.copy(password = value).startOver()
                }
            }

        fun setToken(value: String) = edit { it.copy(token = value) }

        fun setMfaCode(value: String) = edit { it.copy(mfaCode = value.filter(Char::isDigit)) }

        /** Back from the code entry, e.g. to correct the password. */
        fun cancelMfa() = edit { it.startOver() }

        fun setHeaders(value: List<HeaderInput>) = edit { it.copy(headers = value) }

        /** The KeyChain alias chosen by the user, or `null` for no client certificate. */
        fun setClientCertificate(alias: String?) = edit { it.copy(tls = it.tls.copy(clientCertificateAlias = alias)) }

        /**
         * Adds the certificates in a PEM or DER file (parsed off the main thread); sets
         * [LoginError.INVALID_CERTIFICATE] if there are none or there would be more than [MAX_TRUSTED_CERTIFICATES].
         */
        fun addTrustedCertificates(bytes: ByteArray) {
            if (mutableState.value.inProgress) return
            viewModelScope.launch {
                val added =
                    withContext(Dispatchers.Default) {
                        try {
                            TrustedCertificates.parse(bytes)
                        } catch (_: IllegalArgumentException) {
                            null
                        }
                    }
                edit { state ->
                    val certificates = added?.let { (state.tls.trustedCertificates + it).distinct() }
                    if (certificates == null || certificates.size > MAX_TRUSTED_CERTIFICATES) {
                        state.copy(error = LoginError.INVALID_CERTIFICATE)
                    } else {
                        state.copy(tls = state.tls.copy(trustedCertificates = certificates))
                    }
                }
            }
        }

        fun removeTrustedCertificate(certificate: TrustedCertificate) =
            edit { it.copy(tls = it.tls.copy(trustedCertificates = it.tls.trustedCertificates - certificate)) }

        /** The error was shown; clear it. */
        fun dismissError() = edit { it }

        /** The screen reacted to [LoginState.signedIn]; the form starts over (e.g. to add another account). */
        fun signedInHandled() {
            if (mutableState.value.signedIn == null) return
            mutableState.value = LoginState()
            save()
        }

        /** Signs in with the current input; does nothing unless [LoginState.canSubmit]. */
        fun submit() {
            val input = mutableState.value
            if (!input.canSubmit) return
            val headers =
                validHeaders(input.headers).getOrElse { index ->
                    mutableState.value = input.copy(error = LoginError.INVALID_HEADER, invalidHeaderIndex = index)
                    return
                }
            mutableState.value = input.copy(inProgress = true).withoutError()
            viewModelScope.launch {
                val result =
                    try {
                        val account =
                            when (input.method) {
                                LoginMethod.PASSWORD ->
                                    accounts.signIn(
                                        serverUrl = input.serverUrl.trim(),
                                        username = input.username.trim(),
                                        password = input.password,
                                        mfaCode = input.mfaCode.takeIf { input.step == LoginStep.MFA_CODE },
                                        customHeaders = headers,
                                        tls = input.tls,
                                    )
                                LoginMethod.TOKEN ->
                                    accounts.signInWithToken(
                                        input.serverUrl.trim(),
                                        AuthToken(input.token.trim()),
                                        headers,
                                        input.tls,
                                    )
                            }
                        Result.success(account)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (
                        @Suppress("TooGenericExceptionCaught") e: Exception,
                    ) {
                        Result.failure(e)
                    }
                mutableState.update { current ->
                    result.fold(
                        onSuccess = { account ->
                            // Nothing of the input is needed anymore (secrets included); the screen leaves.
                            prefilledFor = null
                            LoginState(signedIn = account)
                        },
                        onFailure = { current.failed(it) },
                    )
                }
                save()
            }
        }

        /** Input is ignored while signing in; any change clears the last error. */
        private fun edit(change: (LoginState) -> LoginState) {
            mutableState.update { state -> if (state.inProgress) state else change(state.withoutError()) }
            save()
        }

        private fun LoginState.withoutError() =
            copy(
                error = null,
                errorDetail = null,
                redirectTarget = null,
                retryAfterSeconds = null,
                invalidHeaderIndex = null,
            )

        /** Changing what identifies the user leaves the code entry. */
        private fun LoginState.startOver() = copy(step = LoginStep.CREDENTIALS, mfaCode = "")

        private fun LoginState.withServerUrl(value: String): LoginState {
            val trimmed = value.trim()
            val parsed = if (trimmed.isEmpty()) null else runCatching { ServerUrl.parse(trimmed) }.getOrNull()
            return copy(
                serverUrl = value,
                urlProblem = if (trimmed.isNotEmpty() && parsed == null) UrlProblem.INVALID else null,
                cleartext = parsed?.isCleartext == true,
            )
        }

        private fun prefilled(
            state: LoginState,
            account: Account,
            headers: List<HeaderInput>,
        ) = state
            .copy(username = account.username, headers = headers, tls = account.tls)
            .withServerUrl(account.serverUrl)

        /**
         * The headers to send (rows without name and value are skipped), with surrounding whitespace removed
         * (e.g. a newline pasted with a secret).
         *
         * @return the position of the first header that cannot be sent as failure
         */
        private fun validHeaders(rows: List<HeaderInput>): HeaderResult {
            val headers = mutableListOf<Pair<String, String>>()
            rows.forEachIndexed { index, row ->
                val name = row.name.trim()
                val value = row.value.trim()
                if (name.isEmpty() && value.isEmpty()) return@forEachIndexed
                // The message of a failure may contain the value: only the position is reported.
                val valid = name.isNotEmpty() && runCatching { HttpHeader(name, value) }.isSuccess
                if (!valid) return HeaderResult.Invalid(index)
                headers += name to value
            }
            return HeaderResult.Valid(headers)
        }

        private sealed interface HeaderResult {
            class Valid(
                val headers: List<Pair<String, String>>,
            ) : HeaderResult

            class Invalid(
                val index: Int,
            ) : HeaderResult
        }

        private inline fun HeaderResult.getOrElse(onInvalid: (Int) -> Nothing): List<Pair<String, String>> =
            when (this) {
                is HeaderResult.Valid -> headers
                is HeaderResult.Invalid -> onInvalid(index)
            }

        private fun LoginState.failed(error: Throwable): LoginState {
            val base = copy(inProgress = false).withoutError()
            return when (error) {
                is PaperlessException.Auth.MfaRequired -> base.copy(step = LoginStep.MFA_CODE)
                is PaperlessException.Auth.InvalidMfaCode ->
                    base.copy(
                        error = LoginError.INVALID_MFA_CODE,
                        mfaCode = "",
                    )
                is PaperlessException.Auth.InvalidCredentials ->
                    base.startOver().copy(error = LoginError.INVALID_CREDENTIALS, errorDetail = error.serverMessage)
                is PaperlessException.Http.Unauthorized ->
                    if (method == LoginMethod.TOKEN) {
                        base.copy(error = LoginError.INVALID_TOKEN, errorDetail = error.detail)
                    } else {
                        base.startOver().copy(error = LoginError.PROXY_AUTHENTICATION, errorDetail = error.detail)
                    }
                is PaperlessException.Http.Forbidden ->
                    base.copy(
                        error = LoginError.FORBIDDEN,
                        errorDetail = error.detail,
                    )
                is PaperlessException.Http.RateLimited ->
                    base.copy(error = LoginError.RATE_LIMITED, retryAfterSeconds = error.retryAfter?.inWholeSeconds)
                is PaperlessException.Http.ServerError -> base.copy(error = LoginError.SERVER_ERROR)
                is PaperlessException.Network.UnknownHost -> base.copy(error = LoginError.UNKNOWN_HOST)
                is PaperlessException.Network.Tls -> base.copy(error = LoginError.TLS)
                is PaperlessException.Network -> base.copy(error = LoginError.CONNECTION_FAILED)
                is PaperlessException.Http.Redirected ->
                    base.copy(
                        error = LoginError.REDIRECTED,
                        redirectTarget = error.location,
                    )
                is PaperlessException.UnsupportedServerVersion,
                is PaperlessException.InvalidResponse,
                is PaperlessException.Http.NotFound,
                -> base.copy(error = LoginError.UNSUPPORTED_SERVER)
                // Developer messages (possibly with input in them) are not shown.
                else -> base.copy(error = LoginError.OTHER)
            }
        }

        /** Keeps the non-secret input across process death. */
        private fun save() {
            val state = mutableState.value
            savedState[KEY_URL] = state.serverUrl
            savedState[KEY_METHOD] = state.method.name
            savedState[KEY_USERNAME] = state.username
            savedState[KEY_HEADER_NAMES] = ArrayList(state.headers.map { it.name })
            savedState[KEY_CLIENT_CERTIFICATE] = state.tls.clientCertificateAlias
            savedState[KEY_RE_SIGN_IN] = state.reSignInAccountId
        }

        private fun restore(): LoginState {
            val url = savedState.get<String>(KEY_URL) ?: return LoginState()
            return LoginState(
                method =
                    savedState.get<String>(KEY_METHOD)?.let { name ->
                        LoginMethod.entries.firstOrNull { it.name == name }
                    }
                        ?: LoginMethod.PASSWORD,
                username = savedState.get<String>(KEY_USERNAME).orEmpty(),
                headers = savedState.get<ArrayList<String>>(KEY_HEADER_NAMES).orEmpty().map { HeaderInput(it, "") },
                tls =
                    AccountTls(
                        clientCertificateAlias = savedState.get<String>(KEY_CLIENT_CERTIFICATE),
                    ),
                reSignInAccountId = savedState.get<String>(KEY_RE_SIGN_IN),
            ).withServerUrl(url)
        }

        companion object {
            /** Certificates per account; certificate files are small (see the screen's file size limit). */
            const val MAX_TRUSTED_CERTIFICATES = 16

            private const val KEY_URL = "login.serverUrl"
            private const val KEY_METHOD = "login.method"
            private const val KEY_USERNAME = "login.username"
            private const val KEY_HEADER_NAMES = "login.headerNames"
            private const val KEY_CLIENT_CERTIFICATE = "login.clientCertificate"
            private const val KEY_RE_SIGN_IN = "login.reSignInAccountId"
        }
    }
