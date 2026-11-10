package com.naoya0117.paperless

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds

/**
 * Work run in the background once the app has started. The regular builds have none; the demo build
 * (`src/demo`) signs in to its built-in server with one.
 */
fun interface AppStartupTask {
    suspend fun run()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class AppStartupModule {
    @Multibinds
    abstract fun startupTasks(): Set<AppStartupTask>
}
