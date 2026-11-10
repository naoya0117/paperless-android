package com.naoya0117.paperless.feature.login

import android.app.Activity
import android.net.Uri
import android.security.KeyChain
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.naoya0117.paperless.core.model.Account
import com.naoya0117.paperless.core.model.AccountTls
import com.naoya0117.paperless.core.model.TrustedCertificate
import com.naoya0117.paperless.core.network.ServerUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Signing in. The look is provisional (plain Material 3 components) until the app's design is decided.
 *
 * @param reSignInAccountId an account to sign in to again with its settings, or `null` for a new account
 * @param onSignedIn called once after signing in succeeded
 * @param onRemoveAccount for [reSignInAccountId]: gives up on it (e.g. the server is gone) and removes it
 */
@Composable
fun LoginScreen(
    reSignInAccountId: String?,
    onSignedIn: (Account) -> Unit,
    onRemoveAccount: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** Leaves without signing in (e.g. adding an account, or using another one); `null` if there is nowhere to go. */
    onCancel: (() -> Unit)? = null,
    viewModel: LoginViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(reSignInAccountId) { reSignInAccountId?.let(viewModel::signInAgain) }
    LaunchedEffect(state.signedIn) {
        state.signedIn?.let { account ->
            onSignedIn(account)
            viewModel.signedInHandled()
        }
    }
    val certificateFiles = rememberCertificatePicker(viewModel::addTrustedCertificates)
    val activity = LocalActivity.current
    LoginContent(
        state = state,
        actions =
            LoginActions(
                setServerUrl = viewModel::setServerUrl,
                setMethod = viewModel::setMethod,
                setUsername = viewModel::setUsername,
                setPassword = viewModel::setPassword,
                setToken = viewModel::setToken,
                setMfaCode = viewModel::setMfaCode,
                cancelMfa = viewModel::cancelMfa,
                setHeaders = viewModel::setHeaders,
                chooseClientCertificate = {
                    activity?.let { chooseClientCertificate(it, state, viewModel::setClientCertificate) }
                },
                clearClientCertificate = { viewModel.setClientCertificate(null) },
                addTrustedCertificate = certificateFiles,
                removeTrustedCertificate = viewModel::removeTrustedCertificate,
                submit = viewModel::submit,
                removeAccount = { reSignInAccountId?.let(onRemoveAccount) },
                cancel = onCancel,
            ),
        modifier = modifier,
    )
}

/** Everything the screen can do, so that [LoginContent] can be previewed without a view model. */
internal class LoginActions(
    val setServerUrl: (String) -> Unit = {},
    val setMethod: (LoginMethod) -> Unit = {},
    val setUsername: (String) -> Unit = {},
    val setPassword: (String) -> Unit = {},
    val setToken: (String) -> Unit = {},
    val setMfaCode: (String) -> Unit = {},
    val cancelMfa: () -> Unit = {},
    val setHeaders: (List<HeaderInput>) -> Unit = {},
    val chooseClientCertificate: () -> Unit = {},
    val clearClientCertificate: () -> Unit = {},
    val addTrustedCertificate: () -> Unit = {},
    val removeTrustedCertificate: (TrustedCertificate) -> Unit = {},
    val submit: () -> Unit = {},
    val removeAccount: () -> Unit = {},
    val cancel: (() -> Unit)? = null,
)

@Composable
internal fun LoginContent(
    state: LoginState,
    actions: LoginActions,
    modifier: Modifier = Modifier,
) {
    val enabled = !state.inProgress
    // Back from the code entry goes to the credentials rather than closing the app.
    BackHandler(enabled = state.step == LoginStep.MFA_CODE && enabled, onBack = actions.cancelMfa)
    val cancel = actions.cancel
    // While signing in, Back waits (rather than leaving the app).
    // Not once signed in: the app switches to the account in a moment.
    BackHandler(enabled = cancel != null && state.step == LoginStep.CREDENTIALS && state.signedIn == null) {
        if (enabled) cancel?.invoke()
    }
    if (state.signedIn != null) {
        // Signed in: the app switches to its next screen in a moment.
        val description = stringResource(R.string.login_in_progress)
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.semantics { contentDescription = description })
        }
        return
    }
    val submitOnDone = KeyboardActions(onDone = { if (state.canSubmit) actions.submit() })
    Column(
        modifier =
            modifier
                // Includes the keyboard (IME) insets.
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
                .widthIn(max = 560.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val title = if (state.reSignInAccountId != null) R.string.login_title_again else R.string.login_title
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f),
            )
            if (cancel != null) {
                IconButton(onClick = cancel, enabled = enabled) {
                    Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.login_cancel))
                }
            }
        }
        if (state.step == LoginStep.MFA_CODE) {
            MfaStep(state, actions, enabled, submitOnDone)
        } else {
            CredentialsStep(state, actions, enabled, submitOnDone)
        }
        state.error?.let { ErrorText(state) }
        Button(onClick = actions.submit, enabled = state.canSubmit, modifier = Modifier.fillMaxWidth()) {
            if (state.inProgress) {
                val description = stringResource(R.string.login_in_progress)
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp).semantics { contentDescription = description },
                    strokeWidth = 2.dp,
                )
            } else {
                Text(stringResource(R.string.login_submit))
            }
        }
        if (state.step == LoginStep.CREDENTIALS) AdvancedSettings(state, actions, enabled)
        if (state.reSignInAccountId != null) {
            HorizontalDivider()
            TextButton(onClick = actions.removeAccount, enabled = enabled) {
                Text(stringResource(R.string.login_remove_account))
            }
        }
    }
}

@Composable
private fun CredentialsStep(
    state: LoginState,
    actions: LoginActions,
    enabled: Boolean,
    submitOnDone: KeyboardActions,
) {
    OutlinedTextField(
        value = state.serverUrl,
        onValueChange = actions.setServerUrl,
        label = { Text(stringResource(R.string.login_server_url)) },
        placeholder = { Text(stringResource(R.string.login_server_url_hint)) },
        isError = state.urlProblem != null,
        supportingText = state.urlProblem?.let { { Text(stringResource(R.string.login_url_invalid)) } },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth(),
    )
    if (state.cleartext) {
        Text(
            text = stringResource(R.string.login_cleartext_warning),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
    }
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        LoginMethod.entries.forEachIndexed { index, method ->
            SegmentedButton(
                selected = state.method == method,
                onClick = { actions.setMethod(method) },
                enabled = enabled,
                shape = SegmentedButtonDefaults.itemShape(index, LoginMethod.entries.size),
            ) {
                val label =
                    when (method) {
                        LoginMethod.PASSWORD -> R.string.login_method_password
                        LoginMethod.TOKEN -> R.string.login_method_token
                    }
                Text(stringResource(label))
            }
        }
    }
    if (state.method == LoginMethod.PASSWORD) {
        OutlinedTextField(
            value = state.username,
            onValueChange = actions.setUsername,
            label = { Text(stringResource(R.string.login_username)) },
            singleLine = true,
            enabled = enabled,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.password,
            onValueChange = actions.setPassword,
            label = { Text(stringResource(R.string.login_password)) },
            singleLine = true,
            enabled = enabled,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = submitOnDone,
            modifier = Modifier.fillMaxWidth(),
        )
    } else {
        OutlinedTextField(
            value = state.token,
            onValueChange = actions.setToken,
            label = { Text(stringResource(R.string.login_token)) },
            supportingText = { Text(stringResource(R.string.login_token_hint)) },
            singleLine = true,
            enabled = enabled,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = submitOnDone,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun MfaStep(
    state: LoginState,
    actions: LoginActions,
    enabled: Boolean,
    submitOnDone: KeyboardActions,
) {
    Text(stringResource(R.string.login_mfa_explanation))
    OutlinedTextField(
        value = state.mfaCode,
        onValueChange = actions.setMfaCode,
        label = { Text(stringResource(R.string.login_mfa_code)) },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
        keyboardActions = submitOnDone,
        modifier = Modifier.fillMaxWidth(),
    )
    TextButton(onClick = actions.cancelMfa, enabled = enabled) { Text(stringResource(R.string.login_mfa_back)) }
}

@Composable
private fun ErrorText(state: LoginState) {
    val message =
        when (state.error) {
            LoginError.INVALID_CREDENTIALS -> stringResource(R.string.login_error_invalid_credentials)
            LoginError.INVALID_MFA_CODE -> stringResource(R.string.login_error_invalid_mfa_code)
            LoginError.INVALID_TOKEN -> stringResource(R.string.login_error_invalid_token)
            LoginError.PROXY_AUTHENTICATION -> stringResource(R.string.login_error_proxy_authentication)
            LoginError.FORBIDDEN -> stringResource(R.string.login_error_forbidden)
            LoginError.RATE_LIMITED ->
                state.retryAfterSeconds?.toInt()?.let {
                    pluralStringResource(
                        R.plurals.login_error_rate_limited_seconds,
                        it,
                        it,
                    )
                }
                    ?: stringResource(R.string.login_error_rate_limited)
            LoginError.SERVER_ERROR -> stringResource(R.string.login_error_server_error)
            LoginError.UNKNOWN_HOST -> stringResource(R.string.login_error_unknown_host)
            LoginError.CONNECTION_FAILED -> stringResource(R.string.login_error_connection_failed)
            LoginError.TLS -> stringResource(R.string.login_error_tls)
            LoginError.REDIRECTED ->
                state.redirectTarget?.let { stringResource(R.string.login_error_redirected_to, it) }
                    ?: stringResource(R.string.login_error_redirected)
            LoginError.UNSUPPORTED_SERVER -> stringResource(R.string.login_error_unsupported_server)
            LoginError.INVALID_HEADER ->
                stringResource(R.string.login_error_invalid_header, (state.invalidHeaderIndex ?: 0) + 1)
            LoginError.INVALID_CERTIFICATE -> stringResource(R.string.login_error_invalid_certificate)
            LoginError.OTHER, null -> stringResource(R.string.login_error_other)
        }
    Column {
        Text(message, color = MaterialTheme.colorScheme.error)
        state.errorDetail?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun AdvancedSettings(
    state: LoginState,
    actions: LoginActions,
    enabled: Boolean,
) {
    // Open when there is something to see, e.g. when signing in again to an account with such settings.
    val hasSettings = state.headers.isNotEmpty() || state.tls != AccountTls()
    var expanded by rememberSaveable { mutableStateOf(hasSettings) }
    // Settings may arrive later (signing in again), and a header error must be visible.
    val mustShow = hasSettings || state.error == LoginError.INVALID_HEADER
    LaunchedEffect(mustShow) { if (mustShow) expanded = true }
    HorizontalDivider()
    TextButton(onClick = { expanded = !expanded }) { Text(stringResource(R.string.login_advanced)) }
    if (!expanded) return

    Text(stringResource(R.string.login_headers), style = MaterialTheme.typography.titleSmall)
    Text(stringResource(R.string.login_headers_explanation), style = MaterialTheme.typography.bodySmall)
    state.headers.forEachIndexed { index, header ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = header.name,
                onValueChange = { name -> actions.setHeaders(state.headers.replaced(index, header.copy(name = name))) },
                label = { Text(stringResource(R.string.login_header_name)) },
                singleLine = true,
                enabled = enabled,
                isError = state.invalidHeaderIndex == index,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = header.value,
                onValueChange = { value ->
                    actions.setHeaders(state.headers.replaced(index, header.copy(value = value)))
                },
                label = { Text(stringResource(R.string.login_header_value)) },
                singleLine = true,
                enabled = enabled,
                isError = state.invalidHeaderIndex == index,
                visualTransformation = PasswordVisualTransformation(),
                // A secret: the keyboard must not learn or suggest it.
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.weight(1f),
            )
        }
        val remove = { actions.setHeaders(state.headers.filterIndexed { i, _ -> i != index }) }
        val removeDescription = stringResource(R.string.login_header_remove_description, index + 1)
        TextButton(
            onClick = remove,
            enabled = enabled,
            modifier = Modifier.semantics { contentDescription = removeDescription },
        ) {
            Text(stringResource(R.string.login_header_remove))
        }
    }
    OutlinedButton(onClick = { actions.setHeaders(state.headers + HeaderInput("", "")) }, enabled = enabled) {
        Text(stringResource(R.string.login_header_add))
    }

    Spacer(Modifier.height(8.dp))
    Text(stringResource(R.string.login_client_certificate), style = MaterialTheme.typography.titleSmall)
    Text(state.tls.clientCertificateAlias ?: stringResource(R.string.login_client_certificate_none))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = actions.chooseClientCertificate, enabled = enabled) {
            Text(stringResource(R.string.login_client_certificate_choose))
        }
        if (state.tls.clientCertificateAlias != null) {
            TextButton(onClick = actions.clearClientCertificate, enabled = enabled) {
                Text(stringResource(R.string.login_client_certificate_clear))
            }
        }
    }

    Spacer(Modifier.height(8.dp))
    Text(stringResource(R.string.login_trusted_certificates), style = MaterialTheme.typography.titleSmall)
    Text(stringResource(R.string.login_trusted_certificates_explanation), style = MaterialTheme.typography.bodySmall)
    state.tls.trustedCertificates.forEachIndexed { index, certificate ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.login_trusted_certificate_item, index + 1), modifier = Modifier.weight(1f))
            val removeDescription = stringResource(R.string.login_trusted_certificate_remove_description, index + 1)
            TextButton(
                onClick = { actions.removeTrustedCertificate(certificate) },
                enabled = enabled,
                modifier = Modifier.semantics { contentDescription = removeDescription },
            ) {
                Text(stringResource(R.string.login_header_remove))
            }
        }
    }
    OutlinedButton(onClick = actions.addTrustedCertificate, enabled = enabled) {
        Text(stringResource(R.string.login_trusted_certificate_add))
    }
}

private fun <T> List<T>.replaced(
    index: Int,
    item: T,
): List<T> = mapIndexed { i, old -> if (i == index) item else old }

/** Lets the user pick a certificate file; its bytes go to [onCertificate] (at most [MAX_CERTIFICATE_BYTES]). */
@Composable
private fun rememberCertificatePicker(onCertificate: (ByteArray) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                val bytes =
                    withContext(Dispatchers.IO) {
                        runCatching {
                            context.contentResolver.openInputStream(uri)?.use { it.readAtMost(MAX_CERTIFICATE_BYTES) }
                        }.getOrNull()
                    }
                // Unreadable files are reported like invalid ones.
                onCertificate(bytes ?: ByteArray(0))
            }
        }
    return remember(launcher) { { launcher.launch(arrayOf("*/*")) } }
}

/** Asks the system to let the user pick a client certificate from the KeyChain for the server's host. */
private fun chooseClientCertificate(
    activity: Activity,
    state: LoginState,
    onChosen: (String?) -> Unit,
) {
    val url = runCatching { ServerUrl.parse(state.serverUrl.trim()) }.getOrNull()?.httpUrl
    KeyChain.choosePrivateKeyAlias(
        activity,
        // Called on a binder thread; the view model's state is saved on the main thread.
        { alias -> if (alias != null) activity.runOnUiThread { onChosen(alias) } },
        null,
        null,
        url?.host,
        url?.port ?: -1,
        state.tls.clientCertificateAlias,
    )
}

/** Certificate files are small; anything larger is cut off and then fails to parse. */
private fun java.io.InputStream.readAtMost(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    while (out.size() < limit) {
        val read = read(buffer, 0, minOf(buffer.size, limit - out.size()))
        if (read < 0) break
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}

private const val MAX_CERTIFICATE_BYTES = 64 * 1024
