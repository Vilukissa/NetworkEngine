package com.calicode.networkengine

import java.util.*
import java.util.concurrent.ConcurrentHashMap

const val ZERO_CACHE = 0 // Means that CacheProvider wont hold results for the Repository

/**
 * Holds every operation's(repo) data.
 * Structure:
 * key = Repository's Class object
 * value = Data class
 */
class CacheProvider internal constructor() {
    private val dataHolder: ConcurrentHashMap<Class<out Repository<*>>, DataList> = ConcurrentHashMap()

    fun allocate(repoClass: Class<out Repository<*>>, size: Int) {
        dataHolder[repoClass] = DataList(size)
    }

    fun deallocate(repoClass: Class<out Repository<*>>) {
        dataHolder.remove(repoClass)
    }

    fun getData(repoClass: Class<out Repository<*>>, dataId: String): Data? {
        return getRepository(repoClass).find(dataId)
    }

    fun putData(repoClass: Class<out Repository<*>>, data: Data) {
        getRepository(repoClass).add(data)
    }

    fun removeData(repoClass: Class<out Repository<*>>, dataId: String) {
        getRepository(repoClass).remove(dataId)
    }

    fun clear() {
        dataHolder.clear()
    }

    private fun getRepository(repoClass: Class<out Repository<*>>) =
            dataHolder[repoClass] ?: throw RepositoryNotFoundException()

    data class Data(val id: String, val data: Any, val isError: Boolean) // TODO: rename data -> response (Data.response)

    internal class DataList(private val sizeLimit: Int) {
        private val items = LinkedList<Data>()

        fun add(element: Data) {
            if (sizeLimit == ZERO_CACHE) return

            val previousIndex: Int = items.indexOfFirst { item -> item.id == element.id }

            if (previousIndex == -1) { // Not found
                if (items.size == sizeLimit) items.removeLast()
                items.add(element)
            } else {
                items[previousIndex] = element
            }
        }

        fun remove(dataId: String) {
            items.find { item -> item.id == dataId }
                    ?.let { foundItem -> items.remove(foundItem) }
        }

        fun find(dataId: String): Data? {
            return items.find { it.id == dataId }
        }
    }

    internal class RepositoryNotFoundException : Exception()
}
