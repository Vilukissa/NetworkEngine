package com.calicode.networkengine

import android.os.Bundle
import android.support.test.runner.AndroidJUnitRunner
import io.appflate.restmock.RESTMockOptions
import io.appflate.restmock.RESTMockServerStarter
import io.appflate.restmock.android.AndroidAssetsFileParser
import io.appflate.restmock.android.AndroidLogger

@Suppress("unused")
class TestRunner : AndroidJUnitRunner() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)

        RESTMockServerStarter.startSync(AndroidAssetsFileParser(context), AndroidLogger(),
                RESTMockOptions.Builder()
                        .useHttps(false)
                        .build())
    }
}