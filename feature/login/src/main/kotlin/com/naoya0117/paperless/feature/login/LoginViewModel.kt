package com.naoya0117.paperless.feature.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naoya0117.paperless.core.data.AccountRepository
import com.naoya0117.paperless.core.data.TrustedCertificates
import com.naoya0117.paperless.core.model.Account
import com.naoya0117.paperless.core.model.AccountTls
import com.naoya0117.paperless.core.model.AuthToken
import com.naoya0117.paperless.core.model.TrustedCertificate
import com.naoya0117.paperless.core.network.PaperlessException
import com.naoya0117.paperless.core.network.ServerUrl
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
    INVALID_CREDENTIALS,
    INVALID_MFA_CODE,
    INVALID_TOKEN,

    /** The host name could not be resolved. */
    UNKNOWN_HOST,

    /** No connection (refused, timed out, offline). */
    CONNECTION_FAILED,

    /** The certificate is not trusted or the host name does not match; trusting a certificate may help. */
    TLS,

    /** The server answered with a redirect, e.g. http -> https or a different path; see [LoginState.redirectTarget]. */
    REDIRECTED,

    /** Not a Paperless-ngx server, or one too old or too new (API version). */
    UNSUPPORTED_SERVER,

    /** A custom header or certificate is not valid. */
    INVALID_SETTINGS,

    /** The certificate data could not be read (when adding a trusted certificate). */
    INVALID_CERTIFICATE,

    OTHER,
}

/** An extra request header, e.g. for an authenticating proxy in front of the server. */
data class HeaderInput(
    val name: String,
    val value: String,
)

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
    val inProgress: Boolean = false,
    val error: LoginError? = null,
    /** The server's message for [LoginError.OTHER] and similar, if any. */
    val errorDetail: String? = null,
    /** Where the server redirected to, for [LoginError.REDIRECTED]. */
    val redirectTarget: String? = null,
    /** Set when signing in succeeded; the screen leaves. */
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
            "signedIn=${signedIn?.id}",
        ).joinToString(prefix = "LoginState(", postfix = ")")

    private fun mask(value: String) = if (value.isEmpty()) "" else "***"
}

/** Signing in to a Paperless-ngx server, independent of how the screen looks. */
@HiltViewModel
class LoginViewModel
    @Inject
    constructor(
        private val accounts: AccountRepository,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow(LoginState())
        val state: StateFlow<LoginState> = mutableState.asStateFlow()

        /** Signing in again to [account] (e.g. it needs to sign in again): keeps its server, user and TLS settings. */
        fun signInAgain(account: Account) {
            mutableState.value =
                LoginState(username = account.username, tls = account.tls).withServerUrl(account.serverUrl)
        }

        fun setServerUrl(value: String) = edit { it.withServerUrl(value) }

        fun setMethod(value: LoginMethod) = edit { it.copy(method = value, step = LoginStep.CREDENTIALS, mfaCode = "") }

        fun setUsername(value: String) = edit { it.copy(username = value) }

        fun setPassword(value: String) = edit { it.copy(password = value) }

        fun setToken(value: String) = edit { it.copy(token = value) }

        fun setMfaCode(value: String) = edit { it.copy(mfaCode = value.filter(Char::isDigit)) }

        /** Back from the code entry, e.g. to correct the password. */
        fun cancelMfa() = edit { it.copy(step = LoginStep.CREDENTIALS, mfaCode = "") }

        fun setHeaders(value: List<HeaderInput>) = edit { it.copy(headers = value) }

        /** The KeyChain alias chosen by the user, or `null` for no client certificate. */
        fun setClientCertificate(alias: String?) = edit { it.copy(tls = it.tls.copy(clientCertificateAlias = alias)) }

        /** Adds the certificates in a PEM or DER file; sets [LoginError.INVALID_CERTIFICATE] if there are none. */
        fun addTrustedCertificates(bytes: ByteArray) {
            val added =
                try {
                    TrustedCertificates.parse(bytes)
                } catch (_: IllegalArgumentException) {
                    mutableState.update { it.copy(error = LoginError.INVALID_CERTIFICATE, errorDetail = null) }
                    return
                }
            edit { state ->
                val certificates = (state.tls.trustedCertificates + added).distinct()
                state.copy(tls = state.tls.copy(trustedCertificates = certificates))
            }
        }

        fun removeTrustedCertificate(certificate: TrustedCertificate) =
            edit { it.copy(tls = it.tls.copy(trustedCertificates = it.tls.trustedCertificates - certificate)) }

        /** Signs in with the current input; does nothing unless [LoginState.canSubmit]. */
        fun submit() {
            val input = mutableState.value
            if (!input.canSubmit) return
            mutableState.value = input.copy(inProgress = true, error = null, errorDetail = null, redirectTarget = null)
            viewModelScope.launch {
                val headers = input.headers.filter { it.name.isNotBlank() }.map { it.name.trim() to it.value }
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
                        onSuccess = {
                            current.copy(
                                inProgress = false,
                                signedIn = it,
                                password = "",
                                token = "",
                                mfaCode = "",
                            )
                        },
                        onFailure = { current.failed(it) },
                    )
                }
            }
        }

        /** The error was shown; clear it. */
        fun dismissError() = edit { it }

        /** Input changes clear the last error, but not while signing in. */
        private fun edit(change: (LoginState) -> LoginState) =
            mutableState.update { state ->
                if (state.inProgress) {
                    state
                } else {
                    change(
                        state,
                    ).copy(error = null, errorDetail = null, redirectTarget = null)
                }
            }

        private fun LoginState.withServerUrl(value: String): LoginState {
            val parsed =
                value.trim().takeIf { it.isNotEmpty() }?.let {
                    runCatching {
                        ServerUrl.parse(
                            it,
                        )
                    }.getOrNull()
                }
            return copy(
                serverUrl = value,
                urlProblem = if (value.isNotBlank() && parsed == null) UrlProblem.INVALID else null,
                cleartext = parsed?.isCleartext == true,
                // Signing in to another server starts over.
                step = LoginStep.CREDENTIALS,
                mfaCode = "",
            )
        }

        private fun LoginState.failed(error: Throwable): LoginState {
            val base = copy(inProgress = false, errorDetail = null, redirectTarget = null)
            return when (error) {
                is PaperlessException.Auth.MfaRequired -> base.copy(step = LoginStep.MFA_CODE, error = null)
                is PaperlessException.Auth.InvalidMfaCode ->
                    base.copy(
                        error = LoginError.INVALID_MFA_CODE,
                        mfaCode = "",
                    )
                is PaperlessException.Auth.InvalidCredentials -> base.copy(error = LoginError.INVALID_CREDENTIALS)
                is PaperlessException.Http.Unauthorized ->
                    base.copy(
                        error =
                            if (method ==
                                LoginMethod.TOKEN
                            ) {
                                LoginError.INVALID_TOKEN
                            } else {
                                LoginError.INVALID_CREDENTIALS
                            },
                    )
                is PaperlessException.Network.UnknownHost -> base.copy(error = LoginError.UNKNOWN_HOST)
                is PaperlessException.Network.Tls -> base.copy(error = LoginError.TLS)
                is PaperlessException.Network -> base.copy(error = LoginError.CONNECTION_FAILED)
                is PaperlessException.Http.Redirected ->
                    base.copy(
                        error = LoginError.REDIRECTED,
                        redirectTarget = error.location,
                    )
                is PaperlessException.UnsupportedServerVersion, is PaperlessException.InvalidResponse,
                is PaperlessException.Http.NotFound,
                -> base.copy(error = LoginError.UNSUPPORTED_SERVER)
                is IllegalArgumentException ->
                    base.copy(
                        error = LoginError.INVALID_SETTINGS,
                        errorDetail = error.message,
                    )
                else -> base.copy(error = LoginError.OTHER, errorDetail = error.message)
            }
        }
    }
