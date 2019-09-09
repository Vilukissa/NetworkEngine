package com.calicode.networkengine

import android.util.Log
import com.calicode.networkengine.CacheProvider.Data
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.jakewharton.retrofit2.adapter.kotlin.coroutines.CoroutineCallAdapterFactory
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okhttp3.logging.HttpLoggingInterceptor
import okhttp3.logging.HttpLoggingInterceptor.Level
import retrofit2.Converter
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.jackson.JacksonConverterFactory
import java.util.*


class NetworkManager internal constructor(
        baseUrl: String,
        private val runningOperationsLimit: Int,
        private val defaultErrorClass: Class<*>? = null) {

    private val retrofit: Retrofit
    private val runningOperations = HashMap<String, Operation>()
    private val pendingOperations = ArrayList<Operation>()

    init {
        val objectMapper = ObjectMapper()
        objectMapper.registerModule(KotlinModule())
        objectMapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)

        val loggingInterceptor = HttpLoggingInterceptor()
        loggingInterceptor.level = Level.BODY

        val client = OkHttpClient.Builder()
                .addInterceptor(loggingInterceptor)
                .build()

        retrofit = Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(client)
                .addConverterFactory(JacksonConverterFactory.create(objectMapper))
                .addCallAdapterFactory(CoroutineCallAdapterFactory())
                .build()
    }

    fun <T> createApi(apiClass: Class<T>): T = retrofit.create(apiClass)

    @Suppress("UnnecessaryVariable", "LiftReturnOrAssignment")
    suspend fun execute(repositoryClass: Class<out Repository<*>>, dataId: String,
                        call: Deferred<Response<*>>,
                        cacheProvider: CacheProvider,
                        errorClass: Class<*>? = null,
                        resultEditor: ResultEditor? = null): Data {
        val operationId: String = createOperationId(repositoryClass, dataId)
        var operation: Operation? = runningOperations.withSync { ops -> ops[operationId] }
        val errorConverter: Converter<ResponseBody, Any>? = createErrorConverterIfNeeded(errorClass)
        val result: Data

        if (operation == null) {
            Log.d(TAG, "Creating new operation $operationId")
            operation = Operation(dataId, call, errorConverter)

            val shouldWait: Boolean = runningOperations.withSync { ops ->
                Log.d(TAG, "Running operations size check ${ops.size} == $runningOperationsLimit")
                if (ops.size == runningOperationsLimit) true
                else {
                    // Add running operation
                    ops[operationId] = operation
                    false
                }
            }

            if (shouldWait) {
                // Add pending operation
                pendingOperations.withSync { ops -> ops.add(operation) }
                result = operation.waitForRun()
            } else {
                result = operation.await()
            }

        } else {
            Log.d(TAG, "Operation ($operationId) already running, waiting for it to complete")
            result = operation.await()
        }

        Log.d(TAG, "Operation ($operationId) finished!")
        // Remove running operation
        runningOperations.withSync { ops -> ops.remove(operationId) }

        pendingOperations.withSync { ops ->
            val pendingOperationsCount = ops.size
            if (pendingOperationsCount > 0) {
                val pendingOperation = ops.elementAt(pendingOperationsCount - 1)
                // Remove pending operation
                ops.remove(pendingOperation)
                Log.d(TAG, "Starting pending operation ($operationId)")
                pendingOperation.isPending = false
            }
        }

        // TODO: edit result...

        cacheProvider.putData(repositoryClass, result)

        return result
    }

    /**
     * Repository's declared error class is priority 1. Second comes NetworkManager's defaultErrorClass.
     * However defaultErrorClass can be overridden in Repository level with EmptyErrorClass if error
     * is needed simply as a String (Response.errorBody().string()) instead of parsing the error
     * in any specific error class.
     */
    private fun createErrorConverterIfNeeded(repositoryErrorClass: Class<*>?): Converter<ResponseBody, Any>? {
        val errorClass: Class<*>? =
                if (repositoryErrorClass != EmptyErrorClass::class.java) repositoryErrorClass ?: defaultErrorClass
                else null
        return errorClass?.let { retrofit.responseBodyConverter<Any>(it, emptyArray()) }
    }

    private fun createOperationId(repositoryClass: Class<out Repository<*>>, dataId: String): String {
        return "${repositoryClass.canonicalName};$dataId"
    }

    private class Operation(private val dataId: String,
                            private val job: Deferred<Response<*>>,
                            private val errorConverter: Converter<ResponseBody, Any>?) {

        var isPending = false

        suspend fun await(): Data {
            Log.d(TAG, "Operation.await()")
            return handleResponse(job.await())
        }

        suspend fun waitForRun(): Data {
            Log.d(TAG, "Operation.waitForRun()")
            isPending = true
            withContext(Dispatchers.Default) {
                while (isPending) {
                    Log.d(TAG, "Delaying operation for 1 sec.")
                    delay(1000)
                }
            }
            Log.d(TAG, "Operation.waitForRun() delaying ended")
            return handleResponse(job.await())
        }

        @Suppress("LiftReturnOrAssignment")
        private fun handleResponse(response: Response<*>): Data {
            if (response.isSuccessful) {
                return Data(dataId, response.body()!!, false)
            } else {
                val errorBody = response.errorBody()!!
                if (errorConverter != null) {
                    try {
                        return Data(dataId, errorConverter.convert(errorBody), true)
                    } catch (ex: Exception) {
                        throw IllegalStateException("Could not convert error body.")
                    }
                } else {
                    return Data(dataId, errorBody.string(), true)
                }
            }
        }
    }

    // This can be used in NetworkManager.execute as errorClass if Repository does
    // not want to use default error class converter and does not declare own error class.
    // Data field (in Data object) will contain the error body as string.
    class EmptyErrorClass

    interface ResultEditor {
        // TODO: add parameters
        fun succeededResult(data: Any)
        fun failedResult(error: Any)
    }
}
