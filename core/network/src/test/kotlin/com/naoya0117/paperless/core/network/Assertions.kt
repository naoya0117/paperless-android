package com.naoya0117.paperless.core.network

import org.junit.Assert.fail

/** Like `assertThrows`, but for suspending code. */
internal suspend inline fun <reified T : Throwable> assertFailsWith(block: suspend () -> Unit): T {
    try {
        block()
    } catch (e: Throwable) {
        if (e is T) return e
        throw AssertionError("Expected ${T::class.simpleName} but was $e", e)
    }
    fail("Expected ${T::class.simpleName} but nothing was thrown")
    throw IllegalStateException()
}
