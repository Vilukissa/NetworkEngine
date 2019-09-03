package com.calicode.networkengine

import com.calicode.networkengine.NetworkManager.ResultEditor
import kotlinx.coroutines.Deferred
import retrofit2.Response

private const val DEFAULT_CACHE_SIZE = 5
const val DEFAULT_DATA_ID = "Default_data_ID"

abstract class Repository<T>(protected val api: T,
                             val cacheSize: Int = DEFAULT_CACHE_SIZE) {
    abstract fun idForCall(params: Any?): String
    abstract fun createCallAsync(params: Any?): Deferred<Response<*>>
    open fun resultEditor(): ResultEditor? = null
}
