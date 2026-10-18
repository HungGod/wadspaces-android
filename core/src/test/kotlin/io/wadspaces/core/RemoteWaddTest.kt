package io.wadspaces.core

import io.wadspaces.core.firebase.FakeHttp
import io.wadspaces.core.firebase.HttpResponse
import io.wadspaces.core.remote.Machine
import io.wadspaces.core.remote.RemoteFailure
import io.wadspaces.core.remote.RemoteWadd
import io.wadspaces.core.remote.Screen
import io.wadspaces.core.remote.Target
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject

class RemoteWaddTest {
    @Test
    fun onlyTailnetAddresses() {
        for (ip in listOf("100.64.0.1", "100.101.102.103", "100.127.255.255", "fd7a:115c:a1e0::7", "fd7a:115c:a1e0:ab12::1")) assertTrue(RemoteWadd.onTailnet(ip), ip)
        for (ip in listOf("100.63.255.255", "100.128.0.1", "192.168.1.5", "10.0.0.1", "fd7a:115c:a1e1::1", "::1", "example.com", "dead.beef", "")) assertFalse(RemoteWadd.onTailnet(ip), ip)
    }

    @Test
    fun machinesFromHeartbeats() {
        val now = 1_800_000_000_000
        val hb = json.parseToJsonElement(
            """{"id": "m1", "name": "Surface", "lastSeen": "${io.wadspaces.core.firebase.Values.rfc3339(now - 20_000)}",
                "tailnet": {"state": "running", "ips": ["fd7a:115c:a1e0::a01:2b03", "100.101.102.103"], "port": 8087, "listening": true},
                "images": [{"layer": "template:dev", "id": "a"}, {"layer": "base", "id": "b"}],
                "screen": {"width": 1500, "height": 1000, "scale": 2.0}}""",
        ).jsonObject
        val m = Machine.of(hb, now)
        assertEquals(Triple("Surface", true, "running"), Triple(m.name, m.online, m.tailnet))
        assertEquals(Target("100.101.102.103", 8087), m.target)
        assertEquals(setOf("template:dev", "base"), m.layers)
        assertEquals(Screen(1500, 1000, 2.0), m.screen)
        assertEquals(3000 to 2000, m.screen!!.pixelWidth to m.screen!!.pixelHeight)
        assertEquals("http://[fd7a:115c:a1e0::1]:8087", Target("fd7a:115c:a1e0::1", 8087).base)
        val off = Machine.of(json.parseToJsonElement("""{"id": "m2", "hostname": "desk", "lastSeen": "2020-01-01T00:00:00Z"}""").jsonObject, now)
        assertEquals(Triple("desk", false, "off"), Triple(off.name, off.online, off.tailnet))
        assertNull(off.target)
        assertNull(off.screen, "no compositor, no screen")
    }

    @Test
    fun callsAndRefusals() = runBlocking<Unit> {
        val http = FakeHttp { r ->
            when {
                r.url.endsWith("/v1/remote/hello") -> {
                    assertNull(r.headers["Authorization"], "hello carries no sign-in")
                    HttpResponse(200, """{"machine": "Surface", "version": "0.1.0", "device": "Pixel 9", "trust": "asked"}""")
                }
                r.url.endsWith("/build") -> HttpResponse(200, """{"workspace": "w", "layer": "custom:x", "buildId": "b1", "built": false}""")
                else -> HttpResponse(403, """{"code": "forbidden", "message": "Allow Pixel 9 on Surface first (WadSpaces asks there)."}""")
            }
        }
        val r = RemoteWadd(http) { "tok" }
        val t = Target("100.101.102.103", 8087)
        assertEquals("asked", r.hello(t).trust)
        assertEquals("b1", r.build(t, "w").buildId)
        assertEquals("Bearer tok", http.asked.last().headers["Authorization"])
        val e = assertFailsWith<RemoteFailure> { r.wadspaces(t) }
        assertTrue(e.notTrusted)
        // Never anywhere but the tailnet.
        val asked = http.asked.size
        assertFailsWith<RemoteFailure> { r.hello(Target("192.168.1.5", 8087)) }
        assertEquals(asked, http.asked.size)
    }
}
