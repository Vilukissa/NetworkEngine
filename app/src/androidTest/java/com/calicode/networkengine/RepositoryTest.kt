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
        engine = createEngine(
                listOf(TestRepository::class.java,
                        TestRepositoryWithApi::class.java,
                        TestRepositoryWithoutCache::class.java),
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
    fun testResponseCache() {
        val repo = engine!!.getRepository(TestRepository::class.java)

        // Default response from TestRepository
        val result: Data = runBlocking { repo.get() }
        Assert.assertEquals("TEST_CALL_OK", result.data)

        // Change the response
        repo.returnedResponse = "SOMETHING_ELSE"

        val cacheResult: Data = runBlocking { repo.get() }
        Assert.assertEquals("TEST_CALL_OK", cacheResult.data)

        // Fresh operation
        val somethingElseResult: Data = runBlocking { repo.get("1234") }
        Assert.assertEquals("SOMETHING_ELSE", somethingElseResult.data)
    }

    @Test
    fun testRepositoryNewRequestMultipleCallers() {
        val repo = engine!!.getRepository(TestRepositoryWithoutCache::class.java)

        val resultList = ArrayList<Data>()
        for (i in 0..9) {
            runBlocking {
                Log.d(TAG, "runBlocking->")
                val a = GlobalScope.async { repo.get() }
                val b = GlobalScope.async { repo.get() }
                val c = GlobalScope.async { repo.get() }
                val d = GlobalScope.async { repo.get() }
                val e = GlobalScope.async { repo.get() }
                val f = GlobalScope.async { repo.get() }
                resultList.addAll(listOf(a.await(), b.await(), c.await(),
                        d.await(), e.await(), f.await()))
                Log.d(TAG, "/runBlocking")
            }
        }
        Log.d(TAG, "---Assert-->")
        Assert.assertEquals(60, resultList.size)
    }

    @Test
    fun testRepositoryWithApi() {
        val repo = engine!!.getRepository(TestRepositoryWithApi::class.java)
        RESTMockServer.whenGET(pathEndsWith("/test")).thenReturnFile("test.json")

        val result: Data = runBlocking { repo.get() }
        Assert.assertEquals("json_test_ok", (result.data as TestResponse).item)
    }

    @Test
    fun testOperationCount() {
        // We need to create different engine for this test
        val tmpEngine = createEngine(
                listOf(TestRepository::class.java),
                NetworkManagerBuilder()
                        .baseUrl(RESTMockServer.getUrl())
                        .runningOperationsLimit(1) // This is the important part!
                        .build())

        val repo = tmpEngine.getRepository(TestRepository::class.java)

        val startTime = System.currentTimeMillis()
        runBlocking {
            val a = GlobalScope.async {
                repo.get("1122")
            }
            val b = GlobalScope.async {
                repo.get("2233")
            }
            listOf(a.await(), b.await())
        }
        val endTime = System.currentTimeMillis()
        // Only way to test this is to calculate the total time which
        // should be first operation + second operation, because if the
        // cache limit is 2, then both operations could run parallel
        // giving the total time something like 3 seconds. When second
        // operation needs to wait the first, the total time is then
        // near 6 seconds.
        val secondOperationTime = endTime - startTime
        Log.d(TAG, "Operations took ${secondOperationTime}ms")
        Assert.assertTrue(secondOperationTime in 5801..6199) // Give some space (400ms)
    }

    @Suppress("DeferredIsResult")
    interface TestApi {
        @GET("/test")
        fun getItem(): Deferred<Response<TestResponse>>
    }

    class TestResponse(val item: String)

    class TestRepository(networkManager: NetworkManager, cacheProvider: CacheProvider)
        : Repository(networkManager, cacheProvider) {

        var returnedResponse: String? = "TEST_CALL_OK"

        fun initCacheForTests(data: Any) {
            // Update the cache via Reflection
            this::class.java.superclass.getDeclaredField("cacheProvider").apply {
                isAccessible = true
                (get(this@TestRepository) as CacheProvider).putData(
                        this@TestRepository::class.java, Data(DEFAULT_DATA_ID, data))
            }
        }

        // This would be the Retrofit.Call (Deferred)
        override fun createCallAsync(params: Any?): Deferred<Response<*>> = lazyAsync {
                Log.d(TAG, "${this@TestRepository::class.java.simpleName} coroutine")
                delay(3000)
                Response.success(returnedResponse)
            }
    }

    class TestRepositoryWithApi(networkManager: NetworkManager, cacheProvider: CacheProvider)
        : Repository(networkManager, cacheProvider) {

        private val api: TestApi = networkManager.createApi(TestApi::class.java)

        override fun createCallAsync(params: Any?): Deferred<Response<*>> = api.getItem()
    }

    class TestRepositoryWithoutCache(networkManager: NetworkManager, cacheProvider: CacheProvider)
        : Repository(networkManager, cacheProvider, ZERO_CACHE) {

        override fun createCallAsync(params: Any?): Deferred<Response<*>> = lazyAsync {
            Log.d(TAG, "${this@TestRepositoryWithoutCache::class.java.simpleName} coroutine")
            delay(4000)
            Response.success("NO_CACHE_RESULT")
        }
    }
}
