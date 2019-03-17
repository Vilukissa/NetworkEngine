package com.calicode.networkengine

import android.util.Log
import com.calicode.networkengine.CacheProvider.Data
import io.appflate.restmock.RESTMockServer
import io.appflate.restmock.utils.RequestMatchers.pathEndsWith
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import retrofit2.http.GET

/**
 * Tests for the whole networking pipe: Repository, CacheProvider and NetworkManager.
 */
class RepositoryTest {

    private var engine: NetworkEngine? = null

    @Before
    fun setUp() {
        engine = createEngine(listOf(TestRepository::class.java, TestRepositoryWithApi::class.java),
                NetworkManagerBuilder()
                        .baseUrl(RESTMockServer.getUrl())
                        .runningOperationsLimit(5)
                        .build())
    }

    @After
    fun tearDown() {
        engine = null
    }

    // Seems like something can alter the execution of tests and every test will pass then,
    // so this is here only for to check if tests can fail. (Problems occur if @Rule is used... Not sure why.)
    @Test
    fun failingTest() {
        Assert.assertNotNull(null)
    }

    @Test
    fun testRepositoryCached() {
        val testResult = "THIS_IS_TEST_1"
        val repo = engine!!.getRepository(TestRepository::class.java)
        repo.initCacheForTests(testResult)

        val result: Data = runBlocking { repo.get() }
        Assert.assertEquals(testResult, result.data)
    }

    @Test
    fun testRepositoryNewRequest() {
        val repo = engine!!.getRepository(TestRepository::class.java)

        val result: Data = runBlocking { repo.get() }
        Assert.assertEquals("TEST_CALL_OK", result.data)
    }

    @Test
    fun testRepositoryNewRequestMultipleCallers() {
        val repo = engine!!.getRepository(TestRepository::class.java)

        val resultList = ArrayList<Data>()
        runBlocking {
            val a = GlobalScope.async { repo.get() }
            val b = GlobalScope.async { repo.get() }
            val c = GlobalScope.async { repo.get() }
            resultList.addAll(listOf(a.await(), b.await(), c.await()))
        }
        Assert.assertEquals(3, resultList.size)
    }

    @Test
    fun testRepositoryWithApi() {
        val repo = engine!!.getRepository(TestRepositoryWithApi::class.java)
        RESTMockServer.whenGET(pathEndsWith("/test")).thenReturnFile("test.json")

        val result: Data = runBlocking { repo.get() }
        Assert.assertEquals("json_test_ok", (result.data as TestResponse).item)
    }

    @Suppress("DeferredIsResult")
    interface TestApi {
        @GET("/test")
        fun getItem(): Deferred<Response<TestResponse>>
    }

    class TestResponse(val item: String)

    class TestRepository(networkManager: NetworkManager) : Repository(networkManager) {

        fun initCacheForTests(data: Any) {
            CacheProvider.putData(this.javaClass, Data(DEFAULT_DATA_ID, data))
        }

        // This would be the Retrofit.Call (Deferred)
        override fun createCallAsync(params: Any?): Deferred<Response<*>> = GlobalScope.async {
                Log.d(TAG, "Test operation coroutine")
                delay(5000)
                Response.success("TEST_CALL_OK")
            }
    }

    class TestRepositoryWithApi(networkManager: NetworkManager) : Repository(networkManager) {

        private val api: TestApi = networkManager.createApi(TestApi::class.java)

        override fun createCallAsync(params: Any?): Deferred<Response<*>> = api.getItem()
    }
}
