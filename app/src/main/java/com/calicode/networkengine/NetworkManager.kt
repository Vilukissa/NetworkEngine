package com.calicode.networkengine

import android.util.Log
import com.calicode.networkengine.CacheProvider.Data
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.jakewharton.retrofit2.adapter.kotlin.coroutines.CoroutineCallAdapterFactory
import kotlinx.coroutines.Deferred
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import okhttp3.logging.HttpLoggingInterceptor.Level
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.jackson.JacksonConverterFactory
import java.util.concurrent.ConcurrentHashMap

class NetworkManager internal constructor(
        baseUrl: String,
        private val runningOperationsLimit: Int) {

    private val retrofit: Retrofit
    private val runningOperations = ConcurrentHashMap<String, Operation>()
    private val pendingOperations = ConcurrentHashMap<String, Operation>()

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
    suspend fun execute(repositoryClass: Class<out Repository>, dataId: String,
                        call: Deferred<Response<*>>,
                        resultEditor: ResultEditor? = null): Data {
        val operationId = createOperationId(repositoryClass, dataId)
        var operation = runningOperations[operationId]
        val result: Data

        if (operation == null) {
            Log.d(TAG, "Creating new operation $operationId")
            operation = Operation(dataId, call)
            // TODO: check running operations count...
            // if (limit reached) ->
            // else ->
             runningOperations[operationId] = operation
             result = operation.await()
        } else {
            Log.d(TAG, "Operation ($operationId) already running, waiting for it to complete")
            result = operation.await()
        }

        Log.d(TAG, "Operation ($operationId) finished!")
        runningOperations.remove(operationId)

        // TODO: edit result...

        CacheProvider.putData(repositoryClass, result)

        return result
    }

    private fun createOperationId(repositoryClass: Class<out Repository>, dataId: String): String {
        return "${repositoryClass.canonicalName}_$dataId"
    }

    class Operation(val dataId: String, private val job: Deferred<Response<*>>) {

        suspend fun await(): Data {
            Log.d(TAG, "Operation.await()")
            return handleResponse(job.await())
        }

        private fun handleResponse(response: Response<*>): Data {
            // TODO: proper error handling...
//            if (response.isSuccessful)
            val obj = response.body()!!
            return Data(dataId, obj)
        }
    }

    interface ResultEditor {
        fun succeededResult(data: Any)
        fun failedResult(error: Any)
    }
}
