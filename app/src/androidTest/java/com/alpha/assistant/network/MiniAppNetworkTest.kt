package com.alpha.assistant.network

import okhttp3.Call
import android.os.CancellationSignal
import okhttp3.EventListener
import okhttp3.Protocol
import okhttp3.Response
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class MiniAppNetworkTest {
    @Test fun exactBackgroundGetDoesNotReplaceForegroundAndCannotFollowRedirects() {
        LocalServer { socket, request ->
            if (request.path == "/approved") socket.respond(302, headers = "Location: /unapproved\r\n")
            else socket.respond(200, "foreground")
        }.use { server ->
            val network = testNetwork(limits)
            val foreground = network.grant(scope)
            val backgroundScope = scope.copy(sessionId = "background", background = true)
            val background = checkNotNull(network.activate(backgroundScope))
            val request = NetworkRequest(server.url("/approved"), followRedirects = false)
            assertEquals(NetworkResult.Failed(NetworkFailure.REDIRECT_TARGET), network.execute(
                NetworkInvocation(backgroundScope, "refresh", background), request, ::unexpectedResponse
            ))
            assertEquals(NetworkResult.Delivered(200), network.execute(
                invocation(foreground), NetworkRequest(server.url("/foreground"))
            ) { assertEquals("foreground", it.body.decodeToString()) })
            assertEquals(2, server.requests.get())

            network.revoke(scope.owner).getOrThrow()
            assertEquals(NetworkResult.Revoked, network.execute(invocation(foreground), request, ::unexpectedResponse))
            assertEquals(NetworkResult.Revoked, network.execute(
                NetworkInvocation(backgroundScope, "refresh-again", background), request, ::unexpectedResponse
            ))
            assertEquals(2, server.requests.get())
        }
    }

    @Test fun noGrantSendsNothingAndGrantCoversLocalRedirects() {
        LocalServer { socket, request ->
            when (request.path) {
                "/first" -> socket.respond(302, headers = "Location: /last\r\n")
                "/malformed" -> socket.respond(
                    302, headers = "Location: ///fixture:placeholder@127.0.0.1:${socket.localPort}/last\r\n"
                )
                else -> socket.respond(200, "local")
            }
        }.use { server ->
            val network = testNetwork(limits)
            val request = NetworkRequest(server.url("/first"))
            assertEquals(NetworkResult.Denied, network.execute(invocation(null), request, ::unexpectedResponse))
            assertEquals(0, server.requests.get())
            val grant = network.grant(scope)
            assertEquals(NetworkResult.Delivered(200), network.execute(invocation(grant), request) {
                assertArrayEquals("local".toByteArray(), it.body)
            })
            assertEquals(2, server.requests.get())
            assertEquals(NetworkResult.Failed(NetworkFailure.REDIRECT_TARGET), network.execute(
                invocation(grant), NetworkRequest(server.url("/malformed")), ::unexpectedResponse
            ))
            assertEquals(3, server.requests.get())
        }
    }

    @Test fun revokeAtRegistrationAndRegrantRequireTheNewEpoch() {
        val checkpoint = Checkpoint()
        val network = testNetwork(limits, object : EventListener() {
            override fun callStart(call: Call) = checkpoint.pauseOnce()
        })
        LocalServer { socket, _ -> socket.respond(200) }.use { server ->
            val oldGrant = network.grant(scope)
            val request = NetworkRequest(server.url())
            val oldInvocation = invocation(oldGrant)
            runBlocked(checkpoint, { network.execute(oldInvocation, request, ::unexpectedResponse) }) {
                network.revoke(scope.owner)
                assertEquals(NetworkResult.Revoked, network.execute(oldInvocation, request, ::unexpectedResponse))
            }.also { assertEquals(NetworkResult.Revoked, it) }
            assertEquals(0, server.requests.get())
            val newGrant = network.grant(scope)
            assertTrue(newGrant.epoch > oldGrant.epoch)
            assertEquals(NetworkResult.Revoked, network.execute(oldInvocation, request, ::unexpectedResponse))
            assertEquals(NetworkResult.Delivered(200), network.execute(invocation(newGrant), request) {})
            assertEquals(1, server.requests.get())
        }
    }

    @Test fun revokeDuringConnectPreventsTheExchange() {
        val checkpoint = Checkpoint()
        val network = testNetwork(limits, object : EventListener() {
            override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
                checkpoint.pauseOnce()
            }
        })
        LocalServer { socket, _ -> socket.respond(200) }.use { server ->
            val grant = network.grant(scope)
            assertEquals(NetworkResult.Revoked, runBlocked(checkpoint, {
                network.execute(invocation(grant), NetworkRequest(server.url()), ::unexpectedResponse)
            }) { network.revoke(scope.owner) })
            assertEquals(0, server.requests.get())
        }
    }

    @Test fun revokeClosesTheActiveStreamAndReleasesCapacity() {
        val streamStarted = CountDownLatch(1)
        val peerClosed = CountDownLatch(1)
        val network = testNetwork(limits.copy(maxConcurrent = 1), object : EventListener() {
            override fun responseBodyStart(call: Call) = streamStarted.countDown()
        })
        LocalServer { socket, request ->
            if (request.path == "/stream") {
                socket.send("HTTP/1.1 200 OK\r\nContent-Length: 20\r\n\r\na")
                assertEquals(-1, socket.getInputStream().read())
                peerClosed.countDown()
            } else socket.respond(200)
        }.use { server ->
            val grant = network.grant(scope)
            val invocation = invocation(grant)
            onWorker({
                network.execute(invocation, NetworkRequest(server.url("/stream")), ::unexpectedResponse)
            }) { pending ->
                await(streamStarted)
                val other = scope.copy(owner = scope.owner.copy(miniAppId = "other"))
                val otherGrant = network.grant(other)
                val otherInvocation = NetworkInvocation(other, "request", otherGrant)
                assertEquals(NetworkResult.Unavailable, network.execute(
                    otherInvocation, NetworkRequest(server.url()), ::unexpectedResponse
                ))
                network.revoke(scope.owner)
                assertEquals(NetworkResult.Revoked, pending())
                await(peerClosed)
                assertEquals(NetworkResult.Delivered(200), network.execute(
                    otherInvocation, NetworkRequest(server.url())
                ) {})
            }
            assertEquals(2, server.requests.get())
        }
    }

    @Test fun revokeDuringRedirectPreventsTheNextHop() {
        val checkpoint = Checkpoint()
        val network = testNetwork(limits, object : EventListener() {
            override fun responseHeadersEnd(call: Call, response: Response) {
                if (response.code == 302) checkpoint.pauseOnce()
            }
        })
        LocalServer { socket, _ -> socket.respond(200) }.use { destination ->
            LocalServer { socket, _ ->
                socket.respond(302, headers = "Location: ${destination.url()}\r\n")
            }.use { origin ->
                val grant = network.grant(scope)
                assertEquals(NetworkResult.Revoked, runBlocked(checkpoint, {
                    network.execute(invocation(grant), NetworkRequest(origin.url()), ::unexpectedResponse)
                }) { network.revoke(scope.owner) })
                assertEquals(1, origin.requests.get())
                assertEquals(0, destination.requests.get())
            }
        }
    }

    @Test fun invalidatingARevisionBeforeDeliveryDiscardsTheCompletedResponse() {
        val checkpoint = Checkpoint()
        val network = testNetwork(limits, object : EventListener() {
            override fun callEnd(call: Call) = checkpoint.pauseOnce()
        })
        LocalServer { socket, _ -> socket.respond(200, "stale") }.use { server ->
            val grant = network.grant(scope)
            val revised = scope.copy(revisionId = "revision2", sessionId = "session2")
            val request = NetworkRequest(server.url())
            assertEquals(NetworkResult.Revoked, runBlocked(checkpoint, {
                network.execute(invocation(grant), request, ::unexpectedResponse)
            }) { network.invalidate(scope) })
            val revisedGrant = network.grant(revised)
            network.invalidate(scope)
            assertEquals(NetworkResult.Delivered(200), network.execute(
                NetworkInvocation(revised, "request", revisedGrant), request
            ) {})
        }
    }

    @Test fun grantsAndTransportCredentialsStayWithTheirOwnerAndOrigin() {
        LocalServer { socket, request ->
            assertFalse(request.headers.containsKey("authorization"))
            assertFalse(request.headers.containsKey("cookie"))
            assertFalse(request.headers.containsKey("x-app-token"))
            socket.respond(200, headers = "Set-Cookie: private=origin\r\n")
        }.use { destination ->
            LocalServer { socket, request ->
                assertEquals("Bearer test-only", request.headers["authorization"])
                assertEquals("test-only", request.headers["x-app-token"])
                socket.respond(302, headers = "Location: ${destination.url()}\r\n")
            }.use { origin ->
                val network = testNetwork(limits)
                val grant = network.grant(scope)
                val otherDatabase = scope.copy(owner = scope.owner.copy(databaseId = "other-db"))
                val otherApp = scope.copy(owner = scope.owner.copy(miniAppId = "other-app"))
                val request = NetworkRequest(origin.url(), headers = listOf(
                    NetworkHeader("Authorization", "Bearer test-only"),
                    NetworkHeader("Cookie", "private=test-only"),
                    NetworkHeader("X-App-Token", "test-only")
                ))
                for (other in listOf(otherDatabase, otherApp)) {
                    assertEquals(NetworkResult.Denied, network.execute(
                        NetworkInvocation(other, "request", grant), request, ::unexpectedResponse
                    ))
                }
                assertEquals(0, origin.requests.get())
                assertEquals(NetworkResult.Delivered(200), network.execute(invocation(grant), request) {})
                val otherGrant = network.grant(otherDatabase)
                assertEquals(NetworkResult.Delivered(200), network.execute(
                    NetworkInvocation(otherDatabase, "request", otherGrant),
                    NetworkRequest(destination.url())
                ) {})
                assertEquals(2, destination.requests.get())
            }
        }
    }

    @Test fun cancelledWriteReportsUnknownOutcomeAndDoesNotReplay() {
        val written = CountDownLatch(1)
        val peerClosed = CountDownLatch(1)
        LocalServer { socket, request ->
            assertEquals("POST", request.method)
            assertArrayEquals(byteArrayOf(1, 2), request.body)
            written.countDown()
            assertEquals(-1, socket.getInputStream().read())
            peerClosed.countDown()
        }.use { server ->
            val network = testNetwork(limits)
            val invocation = invocation(network.grant(scope))
            onWorker({
                network.execute(invocation, NetworkRequest(
                    server.url(), NetworkMethod.POST, body = byteArrayOf(1, 2)
                ), ::unexpectedResponse)
            }) { pending ->
                await(written)
                network.cancel(invocation)
                assertEquals(NetworkResult.UnknownOutcome(NetworkResult.Cancelled), pending())
                await(peerClosed)
                assertEquals(1, server.requests.get())
            }
        }
    }

    @Test fun unsafeRedirectAndRetryAfterStayAsHttpResponses() {
        LocalServer { socket, request ->
            assertEquals("POST", request.method)
            when (request.path) {
                "/redirect" -> socket.respond(307, headers = "Location: /wrong\r\n")
                "/busy" -> socket.respond(503, headers = "Retry-After: 0\r\n")
                else -> error("Unexpected write replay")
            }
        }.use { server ->
            val network = testNetwork(limits)
            val grant = network.grant(scope)
            for ((path, status) in listOf("/redirect" to 307, "/busy" to 503)) {
                assertEquals(NetworkResult.Delivered(status), network.execute(
                    invocation(grant), NetworkRequest(server.url(path), NetworkMethod.POST)
                ) { assertEquals(status, it.status) })
            }
            assertEquals(2, server.requests.get())
        }
    }

    @Test fun boundsAndValidationRejectOversizeAndMalformedInput() {
        LocalServer { socket, request ->
            when (request.path) {
                "/large" -> socket.send("HTTP/1.1 200 OK\r\nConnection: close\r\n\r\n1234")
                "/redirect" -> socket.respond(302, headers = "Location: /large\r\n")
                else -> error("Unexpected exchange")
            }
        }.use { server ->
            val network = testNetwork(limits.copy(maxRequestBytes = 2, maxResponseBytes = 3, maxRedirects = 0))
            val invocation = invocation(network.grant(scope))
            for (request in listOf(
                NetworkRequest("file:///test"),
                NetworkRequest("content://provider/test"),
                NetworkRequest(server.url().replace("http://", "http://name:secret@")),
                NetworkRequest(server.url(), headers = listOf(NetworkHeader("Bad Name", "ok"))),
                NetworkRequest(server.url(), headers = listOf(NetworkHeader("X-App", "ok\r\nSecret: a"))),
                NetworkRequest(server.url(), headers = listOf(NetworkHeader("Host", "other")))
            )) {
                assertEquals(NetworkResult.Failed(NetworkFailure.INVALID_REQUEST),
                    network.execute(invocation, request, ::unexpectedResponse))
            }
            assertEquals(NetworkResult.Failed(NetworkFailure.REQUEST_LIMIT), network.execute(
                invocation, NetworkRequest(server.url(), NetworkMethod.POST, body = ByteArray(3)),
                ::unexpectedResponse
            ))
            assertEquals(0, server.requests.get())
            assertEquals(NetworkResult.Failed(NetworkFailure.RESPONSE_LIMIT), network.execute(
                invocation, NetworkRequest(server.url("/large")), ::unexpectedResponse
            ))
            assertEquals(NetworkResult.Failed(NetworkFailure.REDIRECT_LIMIT), network.execute(
                invocation, NetworkRequest(server.url("/redirect")), ::unexpectedResponse
            ))
            assertEquals(2, server.requests.get())
        }
    }

    @Test fun timeoutClosesTheSocketAndCancellationBeforeAnExchangeIsCertain() {
        val peerClosed = CountDownLatch(1)
        LocalServer { socket, _ ->
            assertEquals(-1, socket.getInputStream().read())
            peerClosed.countDown()
        }.use { server ->
            val network = testNetwork(limits.copy(timeoutMillis = 1_000))
            assertEquals(NetworkResult.Failed(NetworkFailure.TIMEOUT), network.execute(
                invocation(network.grant(scope)), NetworkRequest(server.url()), ::unexpectedResponse
            ))
            await(peerClosed)
        }
        val checkpoint = Checkpoint()
        val network = testNetwork(limits, object : EventListener() {
            override fun callStart(call: Call) = checkpoint.pauseOnce()
        })
        LocalServer { _, _ -> error("Cancelled request reached the server") }.use { server ->
            val invocation = invocation(network.grant(scope))
            assertEquals(NetworkResult.Cancelled, runBlocked(checkpoint, {
                network.execute(invocation, NetworkRequest(server.url(), NetworkMethod.POST), ::unexpectedResponse)
            }) { network.cancel(invocation) })
            assertEquals(0, server.requests.get())
        }
    }

    // Cancellation can arrive before the worker registers its invocation.
    @Test fun cancellationBeforeRegistrationDoesNotAuthorizeOrResolve() {
        val checks = AtomicInteger()
        val network = testNetwork(limits, destinationAccess = NetworkDestinationAccess { _, _ ->
            checks.incrementAndGet()
            null
        }, dns = NetworkDns { _, _, _ -> error("A cancelled invocation reached DNS") })
        val grant = network.grant(scope)
        val cancelled = invocation(grant)
        network.cancel(cancelled)
        assertEquals(NetworkResult.Cancelled, network.execute(
            cancelled, NetworkRequest("https://fixture.test/"), ::unexpectedResponse
        ))
        assertEquals(0, checks.get())
    }

    // A trusted-looking name can resolve to a peer that the platform policy denies.
    @Test fun deniesTheActualDnsPeerBeforeTcpConnect() {
        val checks = AtomicInteger()
        val connects = AtomicInteger()
        val network = testNetwork(limits, object : EventListener() {
            override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) {
                connects.incrementAndGet()
            }
        }, NetworkDestinationAccess { target, peer ->
            checks.incrementAndGet()
            assertEquals("fixture.test", target.host)
            assertEquals("127.0.0.1", peer.address.hostAddress)
            assertEquals(target.port, peer.port)
            NetworkAccessDenial.LOCAL_NETWORK_PERMISSION
        }, NetworkDns { _, _, reply -> reply(listOf(loopback)) })
        LocalServer { _, _ -> error("A denied connection sent a request") }.use { server ->
            val request = NetworkRequest(server.url().replace("127.0.0.1", "fixture.test"), NetworkMethod.POST)
            assertEquals(NetworkResult.AccessDenied(NetworkAccessDenial.LOCAL_NETWORK_PERMISSION), network.execute(
                invocation(network.grant(scope)), request, ::unexpectedResponse
            ))
            assertEquals(1, checks.get())
            assertEquals(0, connects.get())
            assertEquals(0, server.requests.get())
        }
    }

    // A mixed DNS answer can include both an approved and a denied route.
    @Test fun aDeniedDnsCandidateDoesNotBlockAnApprovedCandidate() {
        val blocked = InetAddress.getByAddress(byteArrayOf(10, 1, 2, 3))
        val blockedChecks = AtomicInteger()
        val network = testNetwork(limits, destinationAccess = NetworkDestinationAccess { _, peer ->
            if (peer.address == blocked) {
                blockedChecks.incrementAndGet()
                NetworkAccessDenial.LOCAL_NETWORK_PERMISSION
            } else {
                assertEquals(loopback, peer.address)
                null
            }
        }, dns = NetworkDns { _, _, reply -> reply(listOf(blocked, loopback)) })
        LocalServer { socket, _ -> socket.respond(200) }.use { server ->
            assertEquals(NetworkResult.Delivered(200), network.execute(
                invocation(network.grant(scope)),
                NetworkRequest(server.url().replace("127.0.0.1", "fixture.test"))
            ) {})
            assertEquals(1, blockedChecks.get())
            assertEquals(1, server.requests.get())
        }
    }

    // A redirect can target a newly denied socket or a name that requires permission to resolve.
    @Test fun eachRedirectUsesItsOwnTargetAndSocketDecision() {
        val dnsHosts = mutableListOf<String>()
        val access = object : NetworkDestinationAccess {
            override fun checkTarget(target: NetworkTarget): NetworkAccessDenial? =
                if (target.host == "fixture.local") NetworkAccessDenial.LOCAL_NETWORK_PERMISSION else null

            override fun checkDestination(target: NetworkTarget, peer: InetSocketAddress): NetworkAccessDenial? =
                if (target.host == "denied.test") NetworkAccessDenial.LOCAL_NETWORK_PERMISSION else null
        }
        val network = testNetwork(limits, destinationAccess = access, dns = NetworkDns { host, _, reply ->
            dnsHosts += host
            reply(listOf(loopback))
        })
        LocalServer { _, _ -> error("A denied redirect reached its destination") }.use { destination ->
            LocalServer { socket, request ->
                val host = if (request.path == "/lan") "fixture.local" else "denied.test"
                val location = destination.url().replace("127.0.0.1", host)
                socket.respond(302, headers = "Location: $location\r\n")
            }.use { origin ->
                val grant = network.grant(scope)
                for (path in listOf("/lan", "/peer")) {
                    assertEquals(NetworkResult.AccessDenied(NetworkAccessDenial.LOCAL_NETWORK_PERMISSION),
                        network.execute(invocation(grant),
                            NetworkRequest(origin.url(path).replace("127.0.0.1", "origin.test")), ::unexpectedResponse)
                    )
                }
                assertEquals(listOf("origin.test", "origin.test", "denied.test"), dnsHosts)
                assertEquals(2, origin.requests.get())
                assertEquals(0, destination.requests.get())
            }
        }
    }

    @Test fun revocationReleasesDnsWithoutAnAnswer() {
        val entered = CountDownLatch(1)
        val resolver = AtomicReference<CancellationSignal>()
        val network = testNetwork(limits.copy(timeoutMillis = 30_000), dns = NetworkDns { _, signal, _ ->
            resolver.set(signal)
            entered.countDown()
        })
        val grant = network.grant(scope)
        onWorker({
            network.execute(invocation(grant), NetworkRequest("https://fixture.test/"), ::unexpectedResponse)
        }) { pending ->
            await(entered)
            network.revoke(scope.owner).getOrThrow()
            assertEquals(NetworkResult.Revoked, pending())
            assertTrue(resolver.get().isCanceled)
        }
    }

    @Test fun deadlineCancelsDnsWithoutAnAnswer() {
        val resolver = AtomicReference<CancellationSignal>()
        val network = testNetwork(limits.copy(timeoutMillis = 1_000), dns = NetworkDns { _, signal, _ ->
            resolver.set(signal)
        })
        val grant = network.grant(scope)
        onWorker({
            network.execute(invocation(grant), NetworkRequest("https://fixture.test/"), ::unexpectedResponse)
        }) { pending ->
            assertEquals(NetworkResult.Failed(NetworkFailure.TIMEOUT), pending())
            assertTrue(resolver.get().isCanceled)
        }
    }

    @Test fun revokeDuringSocketPolicyCheckPreventsConnectionAndWrite() {
        val checkpoint = Checkpoint()
        val network = testNetwork(limits, destinationAccess = NetworkDestinationAccess { _, _ ->
            checkpoint.pauseOnce()
            null
        })
        LocalServer { _, _ -> error("The revoked write reached the server") }.use { server ->
            val grant = network.grant(scope)
            assertEquals(NetworkResult.Revoked, runBlocked(checkpoint, {
                network.execute(invocation(grant), NetworkRequest(server.url(), NetworkMethod.POST), ::unexpectedResponse)
            }) { network.revoke(scope.owner).getOrThrow() })
            assertEquals(0, server.requests.get())
        }
    }

    // A permission can change after TCP connects and before the first HTTP bytes.
    @Test fun aNewDestinationDenialBeforeSendKeepsTheWriteOutcomeCertain() {
        val checks = AtomicInteger()
        val network = testNetwork(limits, destinationAccess = NetworkDestinationAccess { _, _ ->
            if (checks.incrementAndGet() == 1) null else NetworkAccessDenial.LOCAL_NETWORK_PERMISSION
        })
        LocalServer { _, _ -> error("The denied write reached the server") }.use { server ->
            assertEquals(NetworkResult.AccessDenied(NetworkAccessDenial.LOCAL_NETWORK_PERMISSION), network.execute(
                invocation(network.grant(scope)), NetworkRequest(server.url(), NetworkMethod.POST), ::unexpectedResponse
            ))
            assertEquals(2, checks.get())
            assertEquals(0, server.requests.get())
        }
    }

    @Test fun absentDestinationPolicyFailsClosed() {
        val network = MiniAppNetwork(limits, TestPersistence())
        assertEquals(NetworkResult.AccessDenied(NetworkAccessDenial.POLICY_UNAVAILABLE), network.execute(
            invocation(network.grant(scope)), NetworkRequest("https://fixture.test/"), ::unexpectedResponse
        ))
    }

    @Test fun aCapturedResponseIsAnImmutableSnapshot() {
        val headers = mutableListOf(NetworkHeader("X-Fixture", "test"))
        val bytes = byteArrayOf(7)
        val snapshot = NetworkResponse(200, headers, bytes)
        headers.clear()
        bytes[0] = 0
        snapshot.body[0] = 0
        assertEquals("test", snapshot.headers.single().value)
        assertArrayEquals(byteArrayOf(7), snapshot.body)
    }


    // A restart can lose permission, restore a revoked owner, or accept an old session token.
    @Test fun durablePermissionRestoresForTheExactOwnerWithoutRestoringAnEpoch() {
        val persistence = TestPersistence()
        val original = MiniAppNetwork(limits, persistence)
        val oldGrant = original.grant(scope)
        val restarted = MiniAppNetwork(limits, persistence)
        val restored = checkNotNull(restarted.activate(scope))
        assertSame(restored, restarted.activate(scope))
        assertNull(restarted.activate(scope.copy(owner = scope.owner.copy(databaseId = "other-db"))))
        assertNull(restarted.activate(scope.copy(owner = scope.owner.copy(miniAppId = "imported"))))
        val invalidRequest = NetworkRequest("file:///blocked")
        assertEquals(NetworkResult.Revoked, restarted.execute(invocation(oldGrant), invalidRequest, ::unexpectedResponse))
        val current = scope.copy(revisionId = "current", sessionId = "new-session")
        val currentGrant = checkNotNull(restarted.activate(current))
        assertTrue(currentGrant.epoch > restored.epoch)
        assertEquals(NetworkResult.Revoked, restarted.execute(invocation(restored), invalidRequest, ::unexpectedResponse))
        restarted.invalidate(current)
        assertTrue(restarted.isEnabled(scope.owner))
        assertTrue(checkNotNull(restarted.activate(current)).epoch > currentGrant.epoch)

        restarted.revoke(scope.owner).getOrThrow()
        assertNull(MiniAppNetwork(limits, persistence).activate(current))
    }

    // A failed read or enable can grant permission from absent or uncommitted data.
    @Test fun persistenceReadAndEnableFailuresDoNotAuthorize() {
        val storageFailure = IllegalStateException("fixture storage failure")
        val unavailable = TestPersistence(loadFailure = storageFailure)
        assertSame(storageFailure, assertThrows(IllegalStateException::class.java) {
            MiniAppNetwork(limits, unavailable)
        })

        val persistence = TestPersistence(write = { enabled ->
            if (enabled) Result.failure(storageFailure) else Result.success(Unit)
        })
        val network = MiniAppNetwork(limits, persistence)
        assertSame(storageFailure, network.enable(scope.owner).exceptionOrNull())
        assertNull(network.activate(scope))
        assertFalse(network.isEnabled(scope.owner))
    }

    // Slow or failed durable revocation can leave active work or let a new request start.
    @Test fun revokeStopsWorkBeforePersistenceAndReportsFailureUntilTheWriteSucceeds() {
        val response = Checkpoint()
        val write = Checkpoint()
        val storageFailure = IllegalStateException("fixture storage failure")
        val revokeAttempts = AtomicInteger()
        val persistence = TestPersistence(write = { enabled ->
            if (!enabled && revokeAttempts.incrementAndGet() == 1) {
                write.pauseOnce()
                Result.failure(storageFailure)
            } else {
                Result.success(Unit)
            }
        })
        val network = MiniAppNetwork(limits, persistence, object : EventListener() {
            override fun callEnd(call: Call) = response.pauseOnce()
        }, allowDestinations)
        LocalServer { socket, _ -> socket.respond(200, "complete") }.use { server ->
            val grant = network.grant(scope)
            val request = NetworkRequest(server.url())
            onWorker({ network.execute(invocation(grant), request, ::unexpectedResponse) }) { pending ->
                try {
                    await(response.entered)
                    onWorker({ network.revoke(scope.owner) }) { revoked ->
                        try {
                            await(write.entered)
                            assertFalse(network.isEnabled(scope.owner))
                            assertNull(network.activate(scope))
                            response.proceed.countDown()
                            assertEquals(NetworkResult.Revoked, pending())
                        } finally {
                            write.proceed.countDown()
                        }
                        assertSame(storageFailure, revoked().exceptionOrNull())
                    }
                } finally {
                    response.proceed.countDown()
                }
            }
            assertNull(network.activate(scope))
            assertEquals(NetworkResult.Revoked, network.execute(invocation(grant), request, ::unexpectedResponse))
            network.revoke(scope.owner).getOrThrow()
            assertNull(MiniAppNetwork(limits, persistence).activate(scope))
            assertEquals(1, server.requests.get())
        }
    }

    // A method-preserving redirect can change the requested operation.
    @Test fun optionsPreservesItsMethodFor307And308AndUsesGetFor303() {
        LocalServer { socket, request ->
            if (request.path.startsWith("/target/")) {
                val expected = if (request.path.endsWith("/303")) "GET" else "OPTIONS"
                assertEquals(expected, request.method)
                socket.respond(200)
            } else {
                assertEquals("OPTIONS", request.method)
                val status = request.path.drop(1).toInt()
                socket.respond(status, headers = "Location: /target/${status}\r\n")
            }
        }.use { server ->
            val network = testNetwork(limits)
            val grant = network.grant(scope)
            for (status in listOf(307, 308, 303)) {
                assertEquals(NetworkResult.Delivered(200), network.execute(
                    invocation(grant), NetworkRequest(server.url("/$status"), NetworkMethod.OPTIONS)
                ) {})
            }
            assertEquals(6, server.requests.get())
        }
    }

    private fun MiniAppNetwork.grant(scope: NetworkScope): NetworkGrant {
        enable(scope.owner).getOrThrow()
        return checkNotNull(activate(scope))
    }

    private fun testNetwork(
        limits: NetworkLimits,
        events: EventListener = EventListener.NONE,
        destinationAccess: NetworkDestinationAccess = allowDestinations,
        dns: NetworkDns = platformNetworkDns
    ) = MiniAppNetwork(limits, TestPersistence(), events, destinationAccess, dns)

    private class TestPersistence(
        private val loadFailure: Throwable? = null,
        private val write: (Boolean) -> Result<Unit> = { Result.success(Unit) }
    ) : NetworkGrantPersistence {
        private val owners = mutableSetOf<NetworkOwner>()

        override fun loadEnabled(): Result<Set<NetworkOwner>> =
            loadFailure?.let { Result.failure(it) } ?: synchronized(owners) { Result.success(owners.toSet()) }

        override fun setEnabled(owner: NetworkOwner, enabled: Boolean): Result<Unit> =
            write(enabled).onSuccess {
                synchronized(owners) { if (enabled) owners.add(owner) else owners.remove(owner) }
            }
    }

    private fun invocation(grant: NetworkGrant?) = NetworkInvocation(scope, "request", grant)

    private class Checkpoint {
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        private val visits = AtomicInteger()

        fun pauseOnce() {
            if (visits.incrementAndGet() == 1) {
                entered.countDown()
                await(proceed)
            }
        }
    }

    private fun <T> runBlocked(checkpoint: Checkpoint, action: () -> T, whileBlocked: () -> Unit): T =
        onWorker(action) { pending ->
            try {
                await(checkpoint.entered)
                whileBlocked()
            } finally {
                checkpoint.proceed.countDown()
            }
            pending()
        }

    private fun <T, R> onWorker(action: () -> T, whileRunning: (() -> T) -> R): R {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val pending = executor.submit<T> { action() }
            return whileRunning { pending.get(5, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
    }

    private class LocalServer(private val handle: (Socket, Incoming) -> Unit) : AutoCloseable {
        private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val requests = AtomicInteger()
        @Volatile private var failure: Throwable? = null
        private val worker = thread(isDaemon = true, name = "mini-app-network-test-server") {
            try {
                while (!server.isClosed) {
                    server.accept().use { socket ->
                        socket.soTimeout = 5_000
                        val input = BufferedInputStream(socket.getInputStream())
                        val line = input.readAsciiLine() ?: return@use
                        val parts = line.split(' ')
                        val headers = buildMap {
                            while (true) {
                                val header = checkNotNull(input.readAsciiLine())
                                if (header.isEmpty()) break
                                put(header.substringBefore(':').lowercase(Locale.ROOT), header.substringAfter(':').trim())
                            }
                        }
                        val body = ByteArray(headers["content-length"]?.toInt() ?: 0)
                        DataInputStream(input).readFully(body)
                        requests.incrementAndGet()
                        handle(socket, Incoming(parts[0], parts[1], headers, body))
                    }
                }
            } catch (error: Throwable) {
                if (error !is SocketException || !server.isClosed) failure = error
            }
        }

        fun url(path: String = "/") = "http://127.0.0.1:${server.localPort}$path"

        override fun close() {
            server.close()
            worker.join(5_000)
            check(!worker.isAlive) { "The server did not close" }
            failure?.let { throw AssertionError("Local server failed", it) }
        }
    }

    private class Incoming(val method: String, val path: String, val headers: Map<String, String>, val body: ByteArray)

    companion object {
        private val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        private val allowDestinations = NetworkDestinationAccess { _, _ -> null }
        private val scope = NetworkScope(NetworkOwner("test-db", "test-mini-app"), "revision1", "session1")
        private val limits = NetworkLimits(
            maxRequestBytes = 1_024, maxResponseBytes = 1_024, maxMetadataBytes = 2_048,
            timeoutMillis = 4_000, maxRedirects = 3, maxConcurrent = 2
        )

        private fun await(latch: CountDownLatch) = assertTrue(latch.await(5, TimeUnit.SECONDS))

        private fun unexpectedResponse(response: NetworkResponse) {
            throw AssertionError("Unexpected response status ${response.status}")
        }

        private fun Socket.respond(status: Int, body: String = "", headers: String = "") {
            send("HTTP/1.1 $status Test\r\nContent-Length: ${body.length}\r\nConnection: close\r\n$headers\r\n$body")
        }

        private fun Socket.send(response: String) {
            getOutputStream().write(response.toByteArray(Charsets.US_ASCII))
            getOutputStream().flush()
        }

        private fun BufferedInputStream.readAsciiLine(): String? {
            val bytes = ByteArrayOutputStream()
            while (true) {
                when (val value = read()) {
                    -1 -> return null
                    '\n'.code -> return bytes.toString(Charsets.US_ASCII.name())
                    '\r'.code -> Unit
                    else -> bytes.write(value)
                }
            }
        }
    }
}
