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
class NetworkEngineTest {

    private var engine: NetworkEngine? = null

    @Before
    fun setUp() {
        engine = createEngine(
                listOf(TestRepository::class.java,
                        TestRepositoryWithApi::class.java,
                        TestRepositoryWithoutCache::class.java,
                        TestRepositoryWithCustomErrorClass::class.java),
                NetworkManagerBuilder()
                        .baseUrl(RESTMockServer.getUrl())
                        .runningOperationsLimit(5)
                        .build())
    }

    @After
    fun tearDown() {
        RESTMockServer.reset()
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
        // Update the cache via Reflection
        engine!!::class.java.getDeclaredField("cacheProvider").apply {
            isAccessible = true
            (get(engine) as CacheProvider).putData(
                    TestRepository::class.java, Data(DEFAULT_DATA_ID, testResult, false))
        }

        val result: Data = runBlocking { engine!!.callRepository(TestRepository::class.java) }
        Assert.assertEquals(testResult, result.data)
    }

    @Test
    fun testRepositoryNewRequest() {
        val result: Data = runBlocking { engine!!.callRepository(TestRepository::class.java) }
        Assert.assertEquals("TEST_CALL_OK", result.data)
    }

    @Test
    fun testResponseCache() {
        // Default response from TestRepository
        val result: Data = runBlocking { engine!!.callRepository(TestRepository::class.java) }
        Assert.assertEquals("TEST_CALL_OK", result.data)

        // Change the response
        engine!!.getRepository(TestRepository::class.java).returnedResponse = "SOMETHING_ELSE"

        val cacheResult: Data = runBlocking { engine!!.callRepository(TestRepository::class.java) }
        Assert.assertEquals("TEST_CALL_OK", cacheResult.data)

        // Fresh operation
        val somethingElseResult: Data = runBlocking { engine!!.callRepository(TestRepository::class.java, "1234") }
        Assert.assertEquals("SOMETHING_ELSE", somethingElseResult.data)
    }

    @Test
    fun testRepositoryNewRequestMultipleCallers() {
        val resultList = ArrayList<Data>()
        for (i in 0..9) {
            runBlocking {
                Log.d(TAG, "runBlocking->")
                val a = GlobalScope.async { engine!!.callRepository(TestRepositoryWithoutCache::class.java) }
                val b = GlobalScope.async { engine!!.callRepository(TestRepositoryWithoutCache::class.java) }
                val c = GlobalScope.async { engine!!.callRepository(TestRepositoryWithoutCache::class.java) }
                val d = GlobalScope.async { engine!!.callRepository(TestRepositoryWithoutCache::class.java) }
                val e = GlobalScope.async { engine!!.callRepository(TestRepositoryWithoutCache::class.java) }
                val f = GlobalScope.async { engine!!.callRepository(TestRepositoryWithoutCache::class.java) }
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
        RESTMockServer.whenGET(pathEndsWith("/test")).thenReturnFile("test.json")

        val result: Data = runBlocking { engine!!.callRepository(TestRepositoryWithApi::class.java) }
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

        val startTime = System.currentTimeMillis()
        runBlocking {
            val a = GlobalScope.async {
                tmpEngine.callRepository(TestRepository::class.java, "1122")
            }
            val b = GlobalScope.async {
                tmpEngine.callRepository(TestRepository::class.java, "2233")
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

    @Test
    fun testOperationFailure() {
        RESTMockServer.whenGET(pathEndsWith("/test")).thenReturnEmpty(500) // Internal server error

        val result: Data = runBlocking { engine!!.callRepository(TestRepositoryWithApi::class.java) }
        Assert.assertTrue(result.isError)
        Assert.assertTrue(result.data is String)
        Assert.assertEquals("", result.data)
    }

    @Test
    fun testCustomErrorClass() {
        RESTMockServer.whenGET(pathEndsWith("/test")).thenReturnFile(500, "error.json")

        val result: Data = runBlocking { engine!!.callRepository(TestRepositoryWithCustomErrorClass::class.java) }
        Assert.assertTrue(result.isError)
        Assert.assertTrue(result.data is TestRepositoryWithCustomErrorClass.CustomErrorClass)
        Assert.assertEquals("User not found",
                (result.data as TestRepositoryWithCustomErrorClass.CustomErrorClass).errorMsg)
    }

    @Test
    fun testNetworkManagerWithDefaultErrorClass() {
        // We need to create different engine for this test
        val tmpEngine = createEngine(
                listOf(TestRepositoryWithApi::class.java),
                NetworkManagerBuilder()
                        .baseUrl(RESTMockServer.getUrl())
                        .defaultErrorClass(DefaultTestErrorClass::class.java)
                        .build())

        RESTMockServer.whenGET(pathEndsWith("/test")).thenReturnFile(500, "error_2.json")

        val result: Data = runBlocking { tmpEngine.callRepository(TestRepositoryWithApi::class.java) }
        Assert.assertTrue(result.isError)
        Assert.assertTrue(result.data is DefaultTestErrorClass)
        Assert.assertEquals(123, (result.data as DefaultTestErrorClass).errorId)
    }

    @Test
    fun testDefaultErrorClassOverride() {
        // We need to create different engine for this test
        val tmpEngine = createEngine(
                listOf(TestRepositoryWithCustomErrorClass::class.java),
                NetworkManagerBuilder()
                        .baseUrl(RESTMockServer.getUrl())
                        .defaultErrorClass(DefaultTestErrorClass::class.java)
                        .build())

        RESTMockServer.whenGET(pathEndsWith("/test")).thenReturnFile(500, "error.json")

        val result: Data = runBlocking { tmpEngine.callRepository(TestRepositoryWithCustomErrorClass::class.java) }
        Assert.assertTrue(result.isError)
        Assert.assertTrue(result.data is TestRepositoryWithCustomErrorClass.CustomErrorClass)
        Assert.assertEquals("User not found",
                (result.data as TestRepositoryWithCustomErrorClass.CustomErrorClass).errorMsg)
    }

    @Test
    fun testEmptyErrorClassUsage() {
        // We need to create different engine for this test
        val tmpEngine = createEngine(
                listOf(TestRepositoryWithEmptyErrorClass::class.java),
                NetworkManagerBuilder()
                        .baseUrl(RESTMockServer.getUrl())
                        .defaultErrorClass(DefaultTestErrorClass::class.java)
                        .build())

        RESTMockServer.whenGET(pathEndsWith("/test")).thenReturnString(500, "PLAIN TEXT")

        val result: Data = runBlocking { tmpEngine.callRepository(TestRepositoryWithEmptyErrorClass::class.java) }
        Assert.assertTrue(result.isError)
        Assert.assertTrue(result.data is String)
        Assert.assertEquals("PLAIN TEXT", result.data)
    }



    /* * * * * * * * * * * * * TEST CLASSES * * * * * * * * * * * * * * * * * * */

    @Suppress("DeferredIsResult")
    interface TestApi {
        @GET("/test")
        fun getItem(): Deferred<Response<TestResponse>>
    }

    class TestResponse(val item: String)

    class TestRepository(api: PlaceholderApi) : Repository<PlaceholderApi>(api) {

        var returnedResponse: String? = "TEST_CALL_OK"

        override fun idForCall(params: Any?): String = if (params is String) params else DEFAULT_DATA_ID

        // This would be the Retrofit.Call (Deferred)
        override fun createCallAsync(params: Any?): Deferred<Response<*>> = lazyAsync {
                Log.d(TAG, "${this@TestRepository::class.java.simpleName} coroutine")
                delay(3000)
                Response.success(returnedResponse)
            }
    }

    class TestRepositoryWithApi(api: TestApi) : Repository<TestApi>(api) {
        override fun idForCall(params: Any?): String = DEFAULT_DATA_ID

        override fun createCallAsync(params: Any?): Deferred<Response<*>> = api.getItem()
    }

    class TestRepositoryWithoutCache(api: PlaceholderApi) : Repository<PlaceholderApi>(api, ZERO_CACHE) {
        override fun idForCall(params: Any?): String = DEFAULT_DATA_ID

        override fun createCallAsync(params: Any?): Deferred<Response<*>> = lazyAsync {
            Log.d(TAG, "${this@TestRepositoryWithoutCache::class.java.simpleName} coroutine")
            delay(4000)
            Response.success("NO_CACHE_RESULT")
        }
    }

    class TestRepositoryWithCustomErrorClass(api: TestApi) : Repository<TestApi>(api) {
        override fun idForCall(params: Any?): String = DEFAULT_DATA_ID

        override fun createCallAsync(params: Any?): Deferred<Response<*>> = api.getItem()

        override fun errorClass(): Class<*>? = CustomErrorClass::class.java

        class CustomErrorClass(val errorMsg: String)
    }

    class TestRepositoryWithEmptyErrorClass(api: TestApi) : Repository<TestApi>(api) {
        override fun idForCall(params: Any?): String = DEFAULT_DATA_ID

        override fun createCallAsync(params: Any?): Deferred<Response<*>> = api.getItem()

        override fun errorClass(): Class<*>? = NetworkManager.EmptyErrorClass::class.java
    }

    class DefaultTestErrorClass(val errorId: Int)
}
