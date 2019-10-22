package com.calicode.networkengine

import android.util.Log

private const val DEFAULT_RUNNING_OPERATION_LIMIT = 1

/** Structure of the engine:
 *
 *                      NETWORK ENGINE
 *                            |
 *            _______________/|\_________________
 *           |                |                  |
 *      NETWORK MGR     CACHE PROVIDER      REPOSITORIES
 *           |                |                  |
 *      OPERATIONS        OPS DATA              API
 *
 *
 *
 * Usage of the engine:
 * - create API calls via repositories
 * - ....
 */
fun createEngine(repositoryList: List<Class<out Repository<*>>>,
                 networkManager: NetworkManager): NetworkEngine {
    val repoInstanceList = HashMap<Class<out Repository<*>>, Repository<*>>()
    val cacheProvider = CacheProvider()
    try {
        val logBuilder = StringBuilder("Creating repositories:")
        repositoryList.forEach { repoClass ->
            var apiClass: Class<*>? = null
            repoClass.declaredConstructors.let { constructors ->
                if (constructors.size > 1) {
                    throw IllegalStateException("Repository class should contain only one constructor")
                }
                constructors[0].parameterTypes.let { parameterTypes ->
                    if (parameterTypes.size > 1) {
                        throw IllegalStateException("Repository's constructor should contain only one parameter")
                    }
                    val parameterType = parameterTypes[0]
                    if (!parameterType.isInterface) {
                        throw IllegalStateException("Repository's constructor parameter should be interface")
                    }
                    apiClass = parameterType
                }
            }
            val apiForRepo = networkManager.createApi(apiClass!!)
            val repository = repoClass.getDeclaredConstructor(apiClass).newInstance(apiForRepo)
            logBuilder.append("\n\tClass=${repository.javaClass.simpleName}\n\tApi=${apiClass!!.simpleName}\n\t-----------")
            repoInstanceList[repoClass] = repository
            cacheProvider.allocate(repoClass, repository.cacheSize)
        }
        Log.d(TAG, logBuilder.toString())
    } catch (exception: Exception) {
        // TODO: debug check...?
        exception.printStackTrace()
        throw IllegalStateException(
                "Something went wrong when trying to create ${NetworkEngine::class.java.simpleName}!")
    }
    return NetworkEngine(networkManager, cacheProvider, repoInstanceList)
}

class NetworkManagerBuilder {
    private var baseUrl: String = ""
    private var runningOperationsLimit: Int = DEFAULT_RUNNING_OPERATION_LIMIT
    private var defaultErrorClass: Class<*>? = null
    // Other stuff, maybe like timeouts?

    fun baseUrl(url: String): NetworkManagerBuilder {
        baseUrl = url
        return this
    }

    fun runningOperationsLimit(limit: Int): NetworkManagerBuilder {
        runningOperationsLimit = limit
        return this
    }

    fun defaultErrorClass(errorClass: Class<*>): NetworkManagerBuilder {
        defaultErrorClass = errorClass
        return this
    }

    fun build(): NetworkManager {
        if (baseUrl.isEmpty()) throw IllegalStateException("Base URL must be defined!")
        // Other mandatory fields...
        return NetworkManager(baseUrl, runningOperationsLimit, defaultErrorClass)
    }
}

class NetworkEngine(private val networkManager: NetworkManager,
                      private val cacheProvider: CacheProvider,
                      private val repositories: Map<Class<out Repository<*>>, Repository<*>>) {

    @Suppress("UNCHECKED_CAST")
    fun <T: Repository<*>> getRepository(repoClass: Class<T>): T {
        return repositories[repoClass]?.let { it as T }
                ?: throw IllegalStateException(
                        "Did not find repository class' instance (${repoClass.canonicalName})")
    }

    suspend fun <T: Repository<*>> callRepository(repoClass: Class<T>, params: Any? = null): CacheProvider.Data {
        val repo = getRepository(repoClass)
        val dataId = repo.idForCall(params)
        return cacheProvider.getData(repoClass, dataId)
                ?: networkManager.execute(repoClass, dataId, repo.createCallAsync(params), cacheProvider,
                        repo.errorClass(), repo.resultEditor())
    }

    fun cancelOperations() { TODO() }

    // TODO: use public with networkManager and cacheProvider or just publish
    // the functions that are allowed to be called anywhere?
}