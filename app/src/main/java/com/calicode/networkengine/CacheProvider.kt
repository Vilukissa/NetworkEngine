package com.calicode.networkengine

import java.util.*
import java.util.concurrent.ConcurrentHashMap

const val ZERO_CACHE = 0 // Means that CacheProvider wont hold results for the Repository

/**
 * Holds every operation's(repo) data.
 * Structure:
 * key = Repository class (name)
 * TODO: ---
 */
object CacheProvider {
    private val dataHolder: ConcurrentHashMap<Class<out Repository>, DataList> = ConcurrentHashMap()

    fun allocate(repoClass: Class<out Repository>, size: Int) {
        dataHolder[repoClass] = DataList(size)
    }

    fun getData(repoClass: Class<out Repository>, dataId: String): Data? {
        // TODO: repo not allocated exception...
        return dataHolder[repoClass]?.find(dataId)
    }

    fun putData(repoClass: Class<out Repository>, data: Data) {
        // TODO: repo not allocated exception...
        dataHolder[repoClass]?.add(data)
    }

    fun removeData(repoClass: Class<out Repository>, dataId: String) {
        // TODO: repo not allocated exception...
        TODO("Not implemented")
    }

    fun clear() { TODO("Not implemented") }

    data class Data(val id: String, val data: Any)

    class DataList(private val sizeLimit: Int) {
        private val items = LinkedList<Data>()

        fun add(element: Data) {
            if (sizeLimit == ZERO_CACHE) return
            var previousIndex: Int? = null
            for ((index, value) in items.withIndex()) {
                if (value.id == element.id) {
                    previousIndex = index
                    break
                }
            }
            if (previousIndex == null) {
                if (items.size == sizeLimit) items.removeLast()
                items.add(element)
            } else {
                items[previousIndex] = element
            }
        }

        fun find(dataId: String): Data? {
            return items.find { it.id == dataId }
        }
    }
}
