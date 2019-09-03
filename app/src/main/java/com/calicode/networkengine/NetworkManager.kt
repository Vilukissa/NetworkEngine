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
import okhttp3.logging.HttpLoggingInterceptor
import okhttp3.logging.HttpLoggingInterceptor.Level
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.jackson.JacksonConverterFactory
import java.util.*

class NetworkManager internal constructor(
        baseUrl: String,
        private val runningOperationsLimit: Int) {

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
                        resultEditor: ResultEditor? = null): Data {
        val operationId: String = createOperationId(repositoryClass, dataId)
        var operation: Operation? = runningOperations.withSync { ops -> ops[operationId] }
        val result: Data

        if (operation == null) {
            Log.d(TAG, "Creating new operation $operationId")
            operation = Operation(dataId, call)

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

    private fun createOperationId(repositoryClass: Class<out Repository<*>>, dataId: String): String {
        return "${repositoryClass.canonicalName}_$dataId"
    }

    private class Operation(private val dataId: String, private val job: Deferred<Response<*>>) {

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

        private fun handleResponse(response: Response<*>): Data {
            // TODO: proper error handling...
//            if (response.isSuccessful)
            val obj = response.body()!!
            return Data(dataId, obj)
        }
    }

    interface ResultEditor {
        // TODO: add parameters
        fun succeededResult(data: Any)
        fun failedResult(error: Any)
    }
}
