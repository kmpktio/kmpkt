package io.kmpkt.bridge.ktor.server

import io.kmpkt.bridge.ktor.routing.HttpRoutes
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.testing.testApplication

class KtorServerTest :
    FunSpec({
        test("defaults HTTP port to 8080") {
            val server = KtorServer()

            server.config.port shouldBe 8080
        }

        test("configure installs server plugins") {
            val events = mutableListOf<String>()
            val server =
                KtorServer(
                    plugins =
                        lazyOf(
                            listOf(
                                KtorServerPlugin { events += "first" },
                                KtorServerPlugin { events += "second" },
                            ),
                        ),
                )

            testApplication {
                application { server.configure(this) }
                startApplication()
            }

            events shouldBe listOf("first", "second")
        }

        test("configure mounts HTTP route contributions") {
            val server =
                KtorServer(
                    routes =
                        lazyOf(
                            listOf(
                                HttpRoutes {
                                    get("/ping") { call.respondText("pong") }
                                },
                            ),
                        ),
                )

            testApplication {
                application { server.configure(this) }

                val response = client.get("/ping")
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldBe "pong"
            }
        }

        test("start boots embedded Netty and stop shuts it down cleanly") {
            var configured = false
            var stopped = false
            val server =
                KtorServer(
                    plugins =
                        lazyOf(
                            listOf(
                                KtorServerPlugin {
                                    configured = true
                                    monitor.subscribe(ApplicationStopped) { stopped = true }
                                },
                            ),
                        ),
                )

            try {
                server.start(port = 0, wait = false)
                configured shouldBe true
            } finally {
                server.stop(gracePeriodMillis = 0, timeoutMillis = 100)
            }
            stopped shouldBe true
        }

        test("stop is a safe no-op when server was never started") {
            KtorServer().stop(gracePeriodMillis = 0, timeoutMillis = 100)
        }
    })
