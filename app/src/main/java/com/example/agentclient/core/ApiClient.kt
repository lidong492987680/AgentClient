package com.example.agentclient.network

import android.content.Context
import com.example.agentclient.core.Config
import com.example.agentclient.core.Logger
import com.google.gson.Gson
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * 网络请求客户端
 * 封装HTTP请求，提供重试、超时、错误处理
 */
class ApiClient private constructor(private val context: Context) {

    private val logger = Logger.getInstance(context)
    private val config = Config.getInstance(context)
    private val gson = Gson()

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(LoggingInterceptor())
        .addInterceptor(RetryInterceptor())
        .build()

    companion object {
        private const val CONTENT_TYPE_JSON = "application/json; charset=utf-8"

        @Volatile
        private var instance: ApiClient? = null

        fun getInstance(context: Context): ApiClient {
            return instance ?: synchronized(this) {
                instance ?: ApiClient(context.applicationContext).also { instance = it }
            }
        }
    }

    /**
     * API响应包装类
     */
    data class ApiResponse<T>(
        val code: Int = 0,
        val message: String = "",
        val data: T? = null
    )

    /**
     * 错误类型
     */
    sealed class ApiError : Exception() {
        data class NetworkError(override val message: String) : ApiError()
        data class ServerError(val code: Int, override val message: String) : ApiError()
        data class ClientError(val code: Int, override val message: String) : ApiError()
        data class UnknownError(override val message: String) : ApiError()
    }

    /**
     * GET请求
     */
    suspend fun <T> get(
        path: String,
        params: Map<String, String> = emptyMap(),
        clazz: Class<T>
    ): T = suspendCoroutine { cont ->
        val url = buildUrl(path, params)
        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        executeRequest(request, clazz) { result ->
            result.fold(
                onSuccess = { cont.resume(it) },
                onFailure = { cont.resumeWithException(it) }
            )
        }
    }

    /**
     * POST请求
     */
    suspend fun <T> post(
        path: String,
        body: Any,
        clazz: Class<T>
    ): T = suspendCoroutine { cont ->
        val url = buildUrl(path)
        val jsonBody = gson.toJson(body).toRequestBody(CONTENT_TYPE_JSON.toMediaType())

        val request = Request.Builder()
            .url(url)
            .post(jsonBody)
            .build()

        executeRequest(request, clazz) { result ->
            result.fold(
                onSuccess = { cont.resume(it) },
                onFailure = { cont.resumeWithException(it) }
            )
        }
    }

    /**
     * 上传文件
     */
    suspend fun uploadFile(
        path: String,
        fileBytes: ByteArray,
        fileName: String,
        additionalParams: Map<String, String> = emptyMap()
    ): Boolean = suspendCoroutine { cont ->
        val url = buildUrl(path)

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                fileName,
                fileBytes.toRequestBody("application/octet-stream".toMediaType())
            )
            .apply {
                additionalParams.forEach { (key, value) ->
                    addFormDataPart(key, value)
                }
            }
            .build()

        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                logger.error("ApiClient", "File upload failed", e)
                cont.resumeWithException(ApiError.NetworkError(e.message ?: "Unknown error"))
            }

            override fun onResponse(call: Call, response: Response) {
                cont.resume(response.isSuccessful)
            }
        })
    }

    /**
     * 执行请求
     */
    private fun <T> executeRequest(
        request: Request,
        clazz: Class<T>,
        callback: (Result<T>) -> Unit
    ) {
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                logger.error("ApiClient", "Request failed: ${request.url}", e)
                callback(Result.failure(ApiError.NetworkError(e.message ?: "Network error")))
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val body = response.body?.string() ?: ""

                    when (response.code) {
                        in 200..299 -> {
                            val result = parseResponse(body, clazz)
                            callback(Result.success(result))
                        }
                        in 400..499 -> {
                            logger.error("ApiClient", "Client error: ${response.code} - $body")
                            callback(Result.failure(
                                ApiError.ClientError(response.code, body)
                            ))
                        }
                        in 500..599 -> {
                            logger.error("ApiClient", "Server error: ${response.code} - $body")
                            callback(Result.failure(
                                ApiError.ServerError(response.code, body)
                            ))
                        }
                        else -> {
                            callback(Result.failure(
                                ApiError.UnknownError("Unexpected response code: ${response.code}")
                            ))
                        }
                    }
                } catch (e: Exception) {
                    logger.error("ApiClient", "Failed to parse response", e)
                    callback(Result.failure(ApiError.UnknownError(e.message ?: "Parse error")))
                }
            }
        })
    }

    /**
     * 解析响应
     */
    private fun <T> parseResponse(json: String, clazz: Class<T>): T {
        return if (clazz == String::class.java) {
            @Suppress("UNCHECKED_CAST")
            json as T
        } else {
            gson.fromJson(json, clazz)
        }
    }

    /**
     * 构建URL
     */
    private fun buildUrl(path: String, params: Map<String, String> = emptyMap()): String {
        val baseUrl = config.get().baseUrl.removeSuffix("/")
        val fullPath = if (path.startsWith("/")) path else "/$path"

        // ✅ 新写法：在 String 上调用 toHttpUrlOrNull()
        val httpUrl = "$baseUrl$fullPath".toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Invalid URL: $baseUrl$fullPath")

        val httpUrlBuilder = httpUrl.newBuilder()

        params.forEach { (k, v) ->
            httpUrlBuilder.addQueryParameter(k, v)
        }

        return httpUrlBuilder.build().toString()
    }

    /**
     * 日志拦截器
     */
    private inner class LoggingInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val startTime = System.currentTimeMillis()

            logger.debug("ApiClient", "→ ${request.method} ${request.url}")

            val response = chain.proceed(request)
            val duration = System.currentTimeMillis() - startTime

            logger.debug("ApiClient", "← ${response.code} ${request.url} (${duration}ms)")

            return response
        }
    }

    /**
     * 重试拦截器
     */
    private inner class RetryInterceptor : Interceptor {
        private val maxRetries = 3
        private val retryDelays = listOf(2000L, 5000L, 10000L)

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            var lastException: IOException? = null

            for (i in 0 until maxRetries) {
                try {
                    val response = chain.proceed(request)

                    // 5xx错误需要重试
                    if (response.code in 500..599 && i < maxRetries) {
                        response.close()
                        Thread.sleep(retryDelays.getOrElse(i) { 10000L })
                        logger.warn("ApiClient", "Retrying request (${i + 1}/$maxRetries): ${request.url}")
                        continue
                    }

                    return response

                } catch (e: IOException) {
                    lastException = e
                    if (i < maxRetries) {
                        Thread.sleep(retryDelays.getOrElse(i) { 10000L })
                        logger.warn("ApiClient", "Retrying request (${i + 1}/$maxRetries): ${request.url}", e)
                    }
                }
            }

            throw lastException ?: IOException("Max retries exceeded")
        }
    }
}