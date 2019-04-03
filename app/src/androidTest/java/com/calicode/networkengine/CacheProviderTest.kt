package com.calicode.networkengine

import com.calicode.networkengine.CacheProvider.Data
import com.calicode.networkengine.CacheProvider.DataList
import com.calicode.networkengine.CacheProvider.RepositoryNotFoundException
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
        CacheProvider.allocate(TestRepo::class.java, 0)
        Assert.assertEquals(1, dataHolder.size)
    }

    @Test
    fun testDeallocate() {
        Assert.assertEquals(0, dataHolder.size)
        CacheProvider.allocate(TestRepo::class.java, 0)
        Assert.assertEquals(1, dataHolder.size)
        CacheProvider.deallocate(TestRepo::class.java)
        Assert.assertEquals(0, dataHolder.size)
    }

    @Test
    fun testPutAndGet() {
        // Test zero (=no cache)
        CacheProvider.allocate(TestRepo::class.java, 0)
        CacheProvider.putData(TestRepo::class.java, Data("ID", Unit))
        Assert.assertEquals(null, CacheProvider.getData(TestRepo::class.java, "ID"))

        // Test with size
        CacheProvider.allocate(TestRepo::class.java, 1)
        CacheProvider.putData(TestRepo::class.java, Data("ID2", String))
        Assert.assertEquals(String, CacheProvider.getData(TestRepo::class.java, "ID2")!!.data)
        // Test updating
        CacheProvider.putData(TestRepo::class.java, Data("ID2", Int))
        Assert.assertEquals(Int, CacheProvider.getData(TestRepo::class.java, "ID2")!!.data)
    }

    @Test
    fun testPutAndRemove() {
        CacheProvider.allocate(TestRepo::class.java, 1)
        CacheProvider.putData(TestRepo::class.java, Data("PUT_N_REMOVE", String))
        Assert.assertEquals(String, CacheProvider.getData(TestRepo::class.java, "PUT_N_REMOVE")!!.data)
        CacheProvider.removeData(TestRepo::class.java, "PUT_N_REMOVE")
        Assert.assertEquals(null, CacheProvider.getData(TestRepo::class.java, "PUT_N_REMOVE"))

        // Test to remove when there's no data to be removed. Nothing should happen.
        CacheProvider.removeData(TestRepo::class.java, "PUT_N_REMOVE")
        Assert.assertEquals(null, CacheProvider.getData(TestRepo::class.java, "PUT_N_REMOVE"))
    }

    @Test
    fun testClear() {
        CacheProvider.allocate(TestRepo::class.java, 0)
        Assert.assertEquals(1, dataHolder.size)
        CacheProvider.clear()
        Assert.assertEquals(0, dataHolder.size)
    }

    @Test
    fun testExceptions() {
        try {
            CacheProvider.putData(TestRepo::class.java, Data("", String))
            Assert.fail()
        } catch (ignored: RepositoryNotFoundException) {}

        try {
            CacheProvider.removeData(TestRepo::class.java, "")
            Assert.fail()
        } catch (ignored: RepositoryNotFoundException) {}

        try {
            CacheProvider.getData(TestRepo::class.java, "")
            Assert.fail()
        } catch (ignored: RepositoryNotFoundException) {}
    }

    private val mapField: Field = CacheProvider.javaClass.getDeclaredField("dataHolder").apply {
        isAccessible = true
    }

    private val dataHolder: ConcurrentHashMap<*, *>
        get() = mapField.get(CacheProvider) as ConcurrentHashMap<*, *>

    private fun clearMap() {
        // Create new instance
        mapField.set(CacheProvider, ConcurrentHashMap<Class<out Repository>, DataList>())
    }

    private class TestRepo(m: NetworkManager) : Repository(m) {
        override fun createCallAsync(params: Any?): Deferred<Response<*>> = GlobalScope.async {
            Response.success("")
        }
    }
}
