package it.danielebufarini.trenify.core.network

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp

actual fun createPlatformHttpClientEngine(): HttpClientEngine = OkHttp.create()
