package com.calicode.networkengine

import android.util.Log
import com.calicode.networkengine.CacheProvider.Data
import io.appflate.restmock.RESTMockServer
import io.appflate.restmock.utils.RequestMatchers.pathEndsWith
import io.appflate.restmock.utils.RequestMatchers.pathStartsWith
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Tests for the whole networking pipe: Repository, CacheProvider and NetworkManager.
 */
class NetworkEngineTest {

    private var engine: NetworkEngine? = null

    @Before
    fun setUp() {
        engine = createEngine(
                listOf(TestRepository::class.java,
                        TestRepositoryWithoutCreateCallAsyncImpl::class.java,
                        TestRepositoryWithApi::class.java,
                        TestRepositoryWithQueryApi::class.java,
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

    /**
     * Test new request with custom coroutine in Repository.createCallAsync functions implementation.
     */
    @Test
    fun testRepositoryNewRequest() {
        val result: Data = runBlocking { engine!!.callRepository(TestRepository::class.java) }
        Assert.assertEquals("TEST_CALL_OK", result.data)
    }

    /**
     * Test new request with API call.
     */
    @Test
    fun testRepositoryWithApi() {
        RESTMockServer.whenGET(pathEndsWith("/test")).thenReturnFile("test.json")

        val result: Data = runBlocking { engine!!.callRepository(TestRepositoryWithApi::class.java) }
        Assert.assertEquals("json_test_ok", (result.data as TestResponse).item)
    }

    /**
     * This test will crash if cache check is not working correctly because test's repository does not have
     * implementation for creating a Deferred instance.
     */
    @Test
    fun testRepositoryCached() {
        val testResult = "THIS_IS_TEST_1"
        // Update the cache via Reflection
        engine!!::class.java.getDeclaredField("cacheProvider").apply {
            isAccessible = true
            (get(engine) as CacheProvider).putData(
                    TestRepositoryWithoutCreateCallAsyncImpl::class.java, Data(DEFAULT_DATA_ID, testResult, false))
        }

        val result: Data = runBlocking { engine!!.callRepository(TestRepositoryWithoutCreateCallAsyncImpl::class.java) }
        Assert.assertEquals(testResult, result.data)
    }

    /**
     * This test will try to verify that every first repository call (with unique ID) should be creating
     * a new network request and every call after that with same ID should get the response from cache.
     */
    @Test
    fun testResponseCache() {
        RESTMockServer.whenGET(pathStartsWith("/query"))
                .thenReturnString("{\"item\": \"query_1\"}")
                .thenReturnString("{\"item\": \"query_2\"}")

        val result: Data = runBlocking { engine!!.callRepository(TestRepositoryWithQueryApi::class.java, "QUERY_1") }
        Assert.assertEquals("query_1", (result.data as TestResponse).item)

        // This should not create a new network request, so data returned is from cache
        val cacheResult: Data = runBlocking { engine!!.callRepository(TestRepositoryWithQueryApi::class.java, "QUERY_1") }
        Assert.assertEquals("query_1", (cacheResult.data as TestResponse).item)

        // Should be creating new operation.
        // Just to make sure cache check will not return previously given results for every unique ID
        val somethingElseResult: Data = runBlocking { engine!!.callRepository(TestRepositoryWithQueryApi::class.java, "QUERY_2") }
        Assert.assertEquals("query_2", (somethingElseResult.data as TestResponse).item)
    }

    /**
     * This test will do multiple calls to same repository with same ID. This test tries to verify that it's not possible
     * to make program crash when calling repository multiple times in short period. There should be only 10
     * unique results in the resultList because every call to repository with same ID will wait for the first
     * request to complete to get the result matching that ID. So there's only one network request per for loop iteration.
     */
    @Test
    fun testRepositoryNewRequestMultipleCallers() {
        val resultList = ArrayList<Data>()
        for (i in 0..9) {
            runBlocking {
                Log.d(TAG, "runBlocking->")
                val a = GlobalScope.async { engine!!.callRepository(TestRepositoryWithoutCache::class.java, i) }
                val b = GlobalScope.async { engine!!.callRepository(TestRepositoryWithoutCache::class.java, i) }
                val c = GlobalScope.async { engine!!.callRepository(TestRepositoryWithoutCache::class.java, i) }
                val d = GlobalScope.async { engine!!.callRepository(TestRepositoryWithoutCache::class.java, i) }
                val e = GlobalScope.async { engine!!.callRepository(TestRepositoryWithoutCache::class.java, i) }
                val f = GlobalScope.async { engine!!.callRepository(TestRepositoryWithoutCache::class.java, i) }
                resultList.addAll(listOf(a.await(), b.await(), c.await(), d.await(), e.await(), f.await()))
                Log.d(TAG, "/runBlocking")
            }
        }
        Log.d(TAG, "---Assert-->")
        Assert.assertEquals(60, resultList.size)
        Assert.assertEquals(10, resultList.groupBy { it.id }.values.size)
    }

    @Test
    fun testRunningOperationsLimit() {
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

    @Suppress("DeferredIsResult")
    interface QueryApi {
        @GET("/query")
        fun query(@Query("q") q: String): Deferred<Response<TestResponse>>
    }

    class TestResponse(val item: String)

    class TestRepositoryWithoutCreateCallAsyncImpl(api: PlaceholderApi) : Repository<PlaceholderApi>(api) {
        override fun idForCall(params: Any?): String = DEFAULT_DATA_ID

        override fun createCallAsync(params: Any?): Deferred<Response<*>> {
            throw NotImplementedError("This should not happen")
        }
    }

    class TestRepository(api: PlaceholderApi) : Repository<PlaceholderApi>(api) {

        override fun idForCall(params: Any?): String = if (params is String) params else DEFAULT_DATA_ID

        // This would be the Retrofit.Call (Deferred)
        override fun createCallAsync(params: Any?): Deferred<Response<*>> = lazyAsync {
                Log.d(TAG, "${this@TestRepository::class.java.simpleName} coroutine")
                delay(3000)
                Response.success("TEST_CALL_OK")
            }
    }

    class TestRepositoryWithApi(api: TestApi) : Repository<TestApi>(api) {
        override fun idForCall(params: Any?): String = DEFAULT_DATA_ID

        override fun createCallAsync(params: Any?): Deferred<Response<*>> = api.getItem()
    }

    class TestRepositoryWithQueryApi(api: QueryApi) : Repository<QueryApi>(api) {
        override fun idForCall(params: Any?): String = "ID_$params"

        override fun createCallAsync(params: Any?): Deferred<Response<*>> = api.query(params as String)
    }

    class TestRepositoryWithoutCache(api: PlaceholderApi) : Repository<PlaceholderApi>(api, ZERO_CACHE) {
        override fun idForCall(params: Any?): String = "RESULT_ID_$params"

        override fun createCallAsync(params: Any?): Deferred<Response<*>> = lazyAsync {
            Log.d(TAG, "${this@TestRepositoryWithoutCache::class.java.simpleName} coroutine")
            delay(4000)
            Response.success("NO_CACHE_RESULT_$params")
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
