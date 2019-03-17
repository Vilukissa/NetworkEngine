package com.calicode.networkengine

import com.calicode.networkengine.CacheProvider.Data
import com.calicode.networkengine.NetworkManager.ResultEditor
import kotlinx.coroutines.Deferred
import retrofit2.Response

private const val DEFAULT_CACHE_SIZE = 5
const val DEFAULT_DATA_ID = "Default_data_ID"

abstract class Repository internal constructor(
        private val networkManager: NetworkManager,
        cacheSize: Int = DEFAULT_CACHE_SIZE) {

    init { CacheProvider.allocate(javaClass, cacheSize) }

    protected abstract fun createCallAsync(params: Any? = null): Deferred<Response<*>>

    suspend fun get(dataId: String = DEFAULT_DATA_ID,
                    params: Any? = null,
                    resultEditor: ResultEditor? = null): Data {
        return CacheProvider.getData(javaClass, dataId)
                ?: networkManager.execute(javaClass, dataId, createCallAsync(params), resultEditor)
    }
}
