package io.kmpkt.bridge.ktor.server

import io.kmpkt.bridge.ktor.routing.HttpRoutes
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.testing.testApplication

class KtorServerTest :
    FunSpec({
        test("configure installs plugins and routes") {
            var pluginInstalled = false
            val server =
                KtorServer(
                    routes =
                        lazyOf(
                            listOf(
                                HttpRoutes {
                                    get("/ping") {
                                        call.respondText("pong")
                                    }
                                },
                            ),
                        ),
                    plugins =
                        lazyOf(
                            listOf(
                                KtorServerPlugin {
                                    pluginInstalled = true
                                },
                            ),
                        ),
                )

            testApplication {
                application { server.configure(this) }

                val response = client.get("/ping")
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldBe "pong"
                pluginInstalled shouldBe true
            }
        }
    })
