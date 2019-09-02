package com.calicode.networkengine

import com.calicode.networkengine.CacheProvider.*
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import java.lang.reflect.Field
import java.util.concurrent.ConcurrentHashMap

class CacheProviderTest {

    private var cacheProvider = CacheProvider()

    @Before
    fun setUp() {
        clearMap()
    }

    @After
    fun tearDown() {
        clearMap()
    }

    @Test
    fun testAllocate() {
        Assert.assertEquals(0, dataHolder.size)
        cacheProvider.allocate(TestRepo::class.java, 0)
        Assert.assertEquals(1, dataHolder.size)
    }

    @Test
    fun testDeallocate() {
        Assert.assertEquals(0, dataHolder.size)
        cacheProvider.allocate(TestRepo::class.java, 0)
        Assert.assertEquals(1, dataHolder.size)
        cacheProvider.deallocate(TestRepo::class.java)
        Assert.assertEquals(0, dataHolder.size)
    }

    @Test
    fun testPutAndGet() {
        // Test zero (=no cache)
        cacheProvider.allocate(TestRepo::class.java, 0)
        cacheProvider.putData(TestRepo::class.java, Data("ID", Unit))
        Assert.assertEquals(null, cacheProvider.getData(TestRepo::class.java, "ID"))

        // Test with size
        cacheProvider.allocate(TestRepo::class.java, 1)
        cacheProvider.putData(TestRepo::class.java, Data("ID2", String))
        Assert.assertEquals(String, cacheProvider.getData(TestRepo::class.java, "ID2")!!.data)
        // Test updating
        cacheProvider.putData(TestRepo::class.java, Data("ID2", Int))
        Assert.assertEquals(Int, cacheProvider.getData(TestRepo::class.java, "ID2")!!.data)
    }

    @Test
    fun testPutAndRemove() {
        cacheProvider.allocate(TestRepo::class.java, 1)
        cacheProvider.putData(TestRepo::class.java, Data("PUT_N_REMOVE", String))
        Assert.assertEquals(String, cacheProvider.getData(TestRepo::class.java, "PUT_N_REMOVE")!!.data)
        cacheProvider.removeData(TestRepo::class.java, "PUT_N_REMOVE")
        Assert.assertEquals(null, cacheProvider.getData(TestRepo::class.java, "PUT_N_REMOVE"))

        // Test to remove when there's no data to be removed. Nothing should happen.
        cacheProvider.removeData(TestRepo::class.java, "PUT_N_REMOVE")
        Assert.assertEquals(null, cacheProvider.getData(TestRepo::class.java, "PUT_N_REMOVE"))
    }

    @Test
    fun testClear() {
        cacheProvider.allocate(TestRepo::class.java, 0)
        Assert.assertEquals(1, dataHolder.size)
        cacheProvider.clear()
        Assert.assertEquals(0, dataHolder.size)
    }

    @Test
    fun testExceptions() {
        try {
            cacheProvider.putData(TestRepo::class.java, Data("", String))
            Assert.fail()
        } catch (ignored: RepositoryNotFoundException) {}

        try {
            cacheProvider.removeData(TestRepo::class.java, "")
            Assert.fail()
        } catch (ignored: RepositoryNotFoundException) {}

        try {
            cacheProvider.getData(TestRepo::class.java, "")
            Assert.fail()
        } catch (ignored: RepositoryNotFoundException) {}
    }

    private val mapField: Field = CacheProvider::class.java.getDeclaredField("dataHolder").apply {
        isAccessible = true
    }

    private val dataHolder: ConcurrentHashMap<*, *>
        get() = mapField.get(cacheProvider) as ConcurrentHashMap<*, *>

    private fun clearMap() {
        // Create new instance
        mapField.set(cacheProvider, ConcurrentHashMap<Class<out Repository>, DataList>())
    }

    private class TestRepo(m: NetworkManager, c: CacheProvider) : Repository(m, c) {
        override fun createCallAsync(params: Any?): Deferred<Response<*>> = GlobalScope.async {
            Response.success("")
        }
    }
}
