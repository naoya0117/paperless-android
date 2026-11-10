package com.naoya0117.paperless.di

import android.content.Context
import com.naoya0117.paperless.core.data.ClientCertificateSource
import com.naoya0117.paperless.core.data.KeyChainClientCertificateSource
import com.naoya0117.paperless.core.datastore.AccountStore
import com.naoya0117.paperless.core.datastore.AndroidKeystoreCipher
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    @Provides
    @Singleton
    fun accountStore(
        @ApplicationContext context: Context,
    ): AccountStore = AccountStore.create(File(context.filesDir, "datastore/accounts.json"), AndroidKeystoreCipher())

    @Provides
    @Singleton
    fun clientCertificateSource(
        @ApplicationContext context: Context,
    ): ClientCertificateSource = KeyChainClientCertificateSource(context)
}
