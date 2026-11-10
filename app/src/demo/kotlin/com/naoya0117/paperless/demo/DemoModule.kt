package com.naoya0117.paperless.demo

import android.content.Context
import com.naoya0117.paperless.AppStartupTask
import com.naoya0117.paperless.core.data.AccountRepository
import com.naoya0117.paperless.core.data.PaperlessClientFactory
import com.naoya0117.paperless.core.model.AccountTls
import com.naoya0117.paperless.core.model.AuthToken
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import javax.inject.Singleton

/**
 * The demo build: every client talks to [DemoServer] instead of the network, and the app signs in to it by
 * itself when there is no account (after signing out, signing in with any server and credentials works too).
 */
@Module
@InstallIn(SingletonComponent::class)
object DemoModule {
    /** Shown as the account's server. `.invalid` never resolves, so nothing could reach the network anyway. */
    const val DEMO_SERVER_URL = "https://demo.paperless-android.invalid"

    @Provides
    @Singleton
    fun demoServer(
        @ApplicationContext context: Context,
    ): DemoServer = DemoServer(DemoData.load(context), DemoFiles())

    @Provides
    @Singleton
    fun clientFactory(server: DemoServer): PaperlessClientFactory =
        PaperlessClientFactory(OkHttpClient.Builder().addInterceptor(server).build())

    @Provides
    @IntoSet
    fun signIn(accounts: AccountRepository): AppStartupTask =
        AppStartupTask {
            if (accounts.state
                    .first()
                    .accounts
                    .isEmpty()
            ) {
                accounts.signInWithToken(DEMO_SERVER_URL, AuthToken(DemoServer.DEMO_TOKEN), tls = AccountTls())
            }
        }
}
