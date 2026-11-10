package com.naoya0117.paperless.core.data

import android.content.Context
import android.security.KeyChain
import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509KeyManager

/** Provides the client certificate for mutual TLS by its KeyChain alias ([com.naoya0117.paperless.core.model.AccountTls]). */
fun interface ClientCertificateSource {
    fun keyManager(alias: String): X509KeyManager
}

/**
 * Reads client certificates from the Android KeyChain. The app must have been granted access to the
 * alias, e.g. by the user picking it in `KeyChain.choosePrivateKeyAlias`.
 */
class KeyChainClientCertificateSource(
    context: Context,
) : ClientCertificateSource {
    private val context = context.applicationContext

    override fun keyManager(alias: String): X509KeyManager = KeyChainKeyManager(context, alias)
}

/**
 * Presents the certificate with [alias] if it is still available. If it was removed or access was
 * revoked, no certificate is presented and the server decides (usually by failing the handshake).
 * KeyChain calls block, which is fine on the connection threads where the handshake runs.
 */
private class KeyChainKeyManager(
    private val context: Context,
    private val alias: String,
) : X509ExtendedKeyManager() {
    override fun chooseClientAlias(
        keyType: Array<out String>?,
        issuers: Array<out Principal>?,
        socket: Socket?,
    ): String? = alias.takeIf { getPrivateKey(it) != null }

    override fun chooseEngineClientAlias(
        keyType: Array<out String>?,
        issuers: Array<out Principal>?,
        engine: SSLEngine?,
    ): String? = chooseClientAlias(keyType, issuers, null)

    override fun getClientAliases(
        keyType: String?,
        issuers: Array<out Principal>?,
    ): Array<String> = arrayOf(alias)

    override fun getCertificateChain(alias: String?): Array<X509Certificate>? =
        if (alias == this.alias) keyChain { KeyChain.getCertificateChain(context, alias) } else null

    override fun getPrivateKey(alias: String?): PrivateKey? =
        if (alias == this.alias) keyChain { KeyChain.getPrivateKey(context, alias) } else null

    override fun chooseServerAlias(
        keyType: String?,
        issuers: Array<out Principal>?,
        socket: Socket?,
    ): String? = null

    override fun getServerAliases(
        keyType: String?,
        issuers: Array<out Principal>?,
    ): Array<String>? = null

    /** `null` on any failure: KeyChainException, a revoked grant, or the key store being unavailable. */
    @Suppress("TooGenericExceptionCaught")
    private inline fun <T> keyChain(block: () -> T?): T? =
        try {
            block()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } catch (_: Exception) {
            null
        }
}
