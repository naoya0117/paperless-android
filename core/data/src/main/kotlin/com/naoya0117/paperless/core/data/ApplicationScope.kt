package com.naoya0117.paperless.core.data

import javax.inject.Qualifier

/**
 * A [kotlinx.coroutines.CoroutineScope] that lives as long as the process: for work that must not stop with a
 * screen (e.g. copying a shared file while the permission to read it lasts).
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope
