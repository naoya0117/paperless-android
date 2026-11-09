package com.naoya0117.paperless.core.network.internal.http

import com.naoya0117.paperless.core.network.PaperlessException
import kotlinx.serialization.SerializationException
import okhttp3.Request
import okio.Timeout
import retrofit2.Call
import retrofit2.CallAdapter
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import java.io.IOException
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type

/**
 * Converts every failure of every Retrofit call into a [com.naoya0117.paperless.core.network.PaperlessException],
 * so API implementations cannot forget to. Applies to suspend functions returning `T` and `Response<T>`;
 * for the latter, only successful responses are returned.
 */
internal class PaperlessCallAdapterFactory : CallAdapter.Factory() {
    override fun get(
        returnType: Type,
        annotations: Array<out Annotation>,
        retrofit: Retrofit,
    ): CallAdapter<*, *>? {
        // Retrofit asks for a Call<T> adapter for suspend functions.
        if (getRawType(returnType) != Call::class.java || returnType !is ParameterizedType) return null
        val responseType = getParameterUpperBound(0, returnType)
        return object : CallAdapter<Any, Call<Any>> {
            override fun responseType(): Type = responseType

            override fun adapt(call: Call<Any>): Call<Any> = ErrorMappingCall(call, responseType)
        }
    }
}

private class ErrorMappingCall<T>(
    private val delegate: Call<T>,
    private val responseType: Type,
) : Call<T> {
    override fun enqueue(callback: Callback<T>) {
        delegate.enqueue(
            object : Callback<T> {
                override fun onResponse(
                    call: Call<T>,
                    response: Response<T>,
                ) {
                    when (val failure = response.failure()) {
                        null -> callback.onResponse(this@ErrorMappingCall, response)
                        else -> callback.onFailure(this@ErrorMappingCall, failure)
                    }
                }

                override fun onFailure(
                    call: Call<T>,
                    t: Throwable,
                ) {
                    // Also mapped when cancelled: OkHttp's call timeout cancels the call, and for a cancelled
                    // coroutine Retrofit ignores the result anyway.
                    callback.onFailure(this@ErrorMappingCall, t.toPaperlessException())
                }
            },
        )
    }

    override fun execute(): Response<T> {
        val response =
            try {
                delegate.execute()
            } catch (e: IOException) {
                throw e.toPaperlessException()
            } catch (e: SerializationException) {
                throw e.toPaperlessException()
            }
        response.failure()?.let { throw it }
        return response
    }

    /**
     * Non-2xx, or 2xx without the body the endpoint declares (e.g. a proxy answering 204), which Retrofit
     * would otherwise report as a KotlinNullPointerException.
     */
    private fun Response<T>.failure(): PaperlessException? =
        when {
            !isSuccessful -> toPaperlessException()
            body() == null && responseType != Unit::class.java ->
                PaperlessException.InvalidResponse("empty body (HTTP ${code()})")
            else -> null
        }

    override fun clone(): Call<T> = ErrorMappingCall(delegate.clone(), responseType)

    override fun isExecuted(): Boolean = delegate.isExecuted

    override fun cancel() = delegate.cancel()

    override fun isCanceled(): Boolean = delegate.isCanceled

    override fun request(): Request = delegate.request()

    override fun timeout(): Timeout = delegate.timeout()
}
