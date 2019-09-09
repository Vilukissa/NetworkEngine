package com.calicode.networkengine

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
fun createEngine(repositoryApiPairs: List<Pair<Class<out Repository<*>>, Class<*>>>,
                   networkManager: NetworkManager): NetworkEngine {
    val repoInstanceList = HashMap<Class<out Repository<*>>, Repository<*>>()
    val cacheProvider = CacheProvider()
    try {
        repositoryApiPairs.forEach { repoApiPair ->
            val repoClass = repoApiPair.first
            val apiClass = repoApiPair.second
            val apiForRepo = networkManager.createApi(apiClass)
            val repository = repoClass.getDeclaredConstructor(apiClass).newInstance(apiForRepo)
            repoInstanceList[repoClass] = repository
            cacheProvider.allocate(repoClass, repository.cacheSize)
        }
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