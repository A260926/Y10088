package lab

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.ContentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import lab.web.labApplication
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApiEndToEndTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun parse(s: String) = json.parseToJsonElement(s) as JsonObject

    @Test
    fun `home page advertises the lab title and the seeded reduction`() = testApplication {
        application { labApplication("/tmp/lab-api-${System.nanoTime()}.db", seed = true) }
        val page = client.get("/")
        assertEquals(HttpStatusCode.OK, page.status)
        val html = page.bodyAsText()
        assertContains(html, "状态机一致性实验室")

        val defs = client.get("/api/definitions")
        assertEquals(HttpStatusCode.OK, defs.status)
        val runResp = client.get("/api/runs/by-key/seed-six-step")
        assertEquals(HttpStatusCode.OK, runResp.status)
        val run = parse(runResp.bodyAsText())
        val runId = (run["id"] as JsonPrimitive).content

        val cmp = parse(client.get("/api/runs/$runId/comparison").bodyAsText())
        assertEquals(false, (cmp["matches"] as JsonPrimitive).content.toBoolean())
        val reduction = parse(client.get("/api/runs/$runId/reduce").bodyAsText())
        assertEquals("6", (reduction["originalSize"] as JsonPrimitive).content)
        assertEquals("2", (reduction["reducedSize"] as JsonPrimitive).content)
    }

    @Test
    fun `idempotency conflict surfaces as 409 through the API`() = testApplication {
        application { labApplication("/tmp/lab-api-conflict-${System.nanoTime()}.db", seed = true) }

        val payload = """
            {
              "definition":"order-retries","version":1,"idempotencyKey":"api-key-1",
              "events":[{"name":"retry","payload":{}}]
            }
        """.trimIndent()
        val first = client.post("/api/runs") {
            contentType(ContentType.Application.Json); setBody(payload)
        }
        assertEquals(HttpStatusCode.Created, first.status)
        val again = client.post("/api/runs") {
            contentType(ContentType.Application.Json); setBody(payload)
        }
        assertEquals(HttpStatusCode.OK, again.status)

        val changed = """
            {
              "definition":"order-retries","version":1,"idempotencyKey":"api-key-1",
              "events":[{"name":"cancel","payload":{}}]
            }
        """.trimIndent()
        val conflict = client.post("/api/runs") {
            contentType(ContentType.Application.Json); setBody(changed)
        }
        assertEquals(HttpStatusCode.Conflict, conflict.status)
        assertContains(conflict.bodyAsText(), "idempotency key")
    }

    @Test
    fun `draft updates reject stale revisions with 409 and published versions cannot change`() = testApplication {
        application { labApplication("/tmp/lab-api-draft-${System.nanoTime()}.db", seed = true) }

        val created = client.post("/api/definitions") {
            contentType(ContentType.Application.Json)
            setBody(
                """{"name":"api-draft","body":{"title":"t","initial":{"x":1},"illegal":"reject","events":{}}}""",
            )
        }
        assertEquals(HttpStatusCode.Created, created.status)
        val detail = parse(created.bodyAsText())
        assertEquals(1, (detail["revision"] as JsonPrimitive).content.toInt())

        val saveA = client.put("/api/definitions/api-draft/versions/1/draft") {
            contentType(ContentType.Application.Json)
            setBody("""{"expectedRevision":1,"body":{"title":"A","initial":{"x":1},"illegal":"reject","events":{}}}""")
        }
        assertEquals(HttpStatusCode.OK, saveA.status)
        val saveBStale = client.put("/api/definitions/api-draft/versions/1/draft") {
            contentType(ContentType.Application.Json)
            setBody("""{"expectedRevision":1,"body":{"title":"B-stale","initial":{"x":1},"illegal":"reject","events":{}}}""")
        }
        assertEquals(HttpStatusCode.Conflict, saveBStale.status)
        val saveBFresh = client.put("/api/definitions/api-draft/versions/1/draft") {
            contentType(ContentType.Application.Json)
            setBody("""{"expectedRevision":2,"body":{"title":"B","initial":{"x":1},"illegal":"reject","events":{}}}""")
        }
        assertEquals(HttpStatusCode.OK, saveBFresh.status)

        client.post("/api/definitions/api-draft/versions/1/publish")
        val afterPublish = client.put("/api/definitions/api-draft/versions/1/draft") {
            contentType(ContentType.Application.Json)
            setBody("""{"expectedRevision":3,"body":{"title":"nope","initial":{"x":1},"illegal":"reject","events":{}}}""")
        }
        assertEquals(HttpStatusCode.Conflict, afterPublish.status)

        val v2 = client.post("/api/definitions/api-draft/versions") {
            contentType(ContentType.Application.Json)
            setBody("""{"body":{"title":"v2","initial":{"x":1},"illegal":"reject","events":{}}}""")
        }
        assertEquals(HttpStatusCode.Created, v2.status)
        assertEquals(2, (parse(v2.bodyAsText())["version"] as JsonPrimitive).content.toInt())
    }

    @Test
    fun `interleaving generation is bounded and commutativity api reports bad claims`() = testApplication {
        application { labApplication("/tmp/lab-api-interleave-${System.nanoTime()}.db", seed = true) }

        val interleavings = client.post("/api/interleavings") {
            contentType(ContentType.Application.Json)
            setBody(
                """{"limit":4,"parties":[
                  [{"name":"retry","payload":{}}],
                  [{"name":"heartbeat","payload":{}},{"name":"cancel","payload":{}}]
                ]}""",
            )
        }
        assertEquals(HttpStatusCode.OK, interleavings.status)
        val list = json.parseToJsonElement(interleavings.bodyAsText()) as JsonArray
        assertEquals(3, list.size)

        val comm = client.post("/api/commutativity") {
            contentType(ContentType.Application.Json)
            setBody(
                """{"definition":"order-retries","version":1,
                   "pairs":[{"a":"retry","b":"heartbeat"},{"a":"cancel","b":"confirm"}]}""",
            )
        }
        assertEquals(HttpStatusCode.OK, comm.status)
        val checks = json.parseToJsonElement(comm.bodyAsText()) as JsonArray
        val retryHeartbeat = checks.first {
            ((it as JsonObject)["eventA"] as JsonPrimitive).content == "retry"
        } as JsonObject
        assertEquals(true, (retryHeartbeat["passes"] as JsonPrimitive).content.toBoolean())
    }
}
