package com.naoya0117.paperless.core.network.internal.http

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

            override fun adapt(call: Call<Any>): Call<Any> = ErrorMappingCall(call)
        }
    }
}

private class ErrorMappingCall<T>(
    private val delegate: Call<T>,
) : Call<T> {
    override fun enqueue(callback: Callback<T>) {
        delegate.enqueue(
            object : Callback<T> {
                override fun onResponse(
                    call: Call<T>,
                    response: Response<T>,
                ) {
                    if (response.isSuccessful) {
                        callback.onResponse(this@ErrorMappingCall, response)
                    } else {
                        callback.onFailure(this@ErrorMappingCall, response.toPaperlessException())
                    }
                }

                override fun onFailure(
                    call: Call<T>,
                    t: Throwable,
                ) {
                    // A cancelled call belongs to a cancelled coroutine; keep the original exception.
                    callback.onFailure(this@ErrorMappingCall, if (call.isCanceled) t else t.toPaperlessException())
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
        if (!response.isSuccessful) throw response.toPaperlessException()
        return response
    }

    override fun clone(): Call<T> = ErrorMappingCall(delegate.clone())

    override fun isExecuted(): Boolean = delegate.isExecuted

    override fun cancel() = delegate.cancel()

    override fun isCanceled(): Boolean = delegate.isCanceled

    override fun request(): Request = delegate.request()

    override fun timeout(): Timeout = delegate.timeout()
}
