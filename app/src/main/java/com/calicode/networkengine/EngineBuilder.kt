package com.calicode.networkengine

private const val DEFAULT_RUNNING_OPERATION_LIMIT = 1

fun createEngine(repositories: List<Class<out Repository>>, networkManager: NetworkManager): NetworkEngine {
    val repoInstanceList = HashMap<Class<out Repository>, Repository>()
    try {
        repositories.forEach { repoClass ->
            val nwConstructor = repoClass.getDeclaredConstructor(NetworkManager::class.java)
            repoInstanceList[repoClass] = nwConstructor.newInstance(networkManager)
        }
    } catch (exception: Exception) {
        // TODO: debug check...?
        exception.printStackTrace()
        throw IllegalStateException("Something went wrong when trying to create ${NetworkEngine::class.java.simpleName}!")
    }
    return NetworkEngine(networkManager, repoInstanceList)
}

class NetworkManagerBuilder {
    private var baseUrl: String = ""
    private var runningOperationsLimit: Int = DEFAULT_RUNNING_OPERATION_LIMIT
    // Other stuff, maybe like timeouts?

    fun baseUrl(url: String): NetworkManagerBuilder {
        baseUrl = url
        return this
    }

    fun runningOperationsLimit(limit: Int): NetworkManagerBuilder {
        runningOperationsLimit = limit
        return this
    }

    fun build(): NetworkManager {
        if (baseUrl.isEmpty()) throw IllegalStateException("Base URL must be defined!")
        // Other mandatory fields...
        return NetworkManager(baseUrl, runningOperationsLimit)
    }
}

class NetworkEngine(private val networkManager: NetworkManager,
                    private val repositories: Map<Class<out Repository>, Repository>) {

    @Suppress("UNCHECKED_CAST")
    fun <T: Repository> getRepository(repoClass: Class<T>): T {
        return repositories[repoClass]?.let { it as T }
                ?: throw IllegalStateException("Did not find repository class' instance (${repoClass.canonicalName})")
    }

    fun cancelOperations() { TODO() }

    fun executeOperationWithoutRepository() { TODO() }
}