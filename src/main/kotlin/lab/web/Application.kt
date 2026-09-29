package lab.web

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import lab.engine.RunExecutor
import lab.store.DefinitionRepository
import lab.store.RunRepository
import io.ktor.server.response.respondText
import io.ktor.http.ContentType
import lab.engine.CommutativityCheck

fun main(args: Array<String>) {
    val port = args.indexOf("--port").takeIf { it >= 0 }?.let { args[it + 1].toInt() } ?: 8080
    val dbPath = args.indexOf("--db").takeIf { it >= 0 }?.let { args[it + 1] }
        ?: System.getProperty("lab.db")
        ?: System.getenv("LAB_DB")
        ?: "lab.db"
    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        labApplication(dbPath, seed = true)
    }.start(wait = true)
}

fun Application.labApplication(dbPath: String = "lab.db", seed: Boolean = true) {
    val db = lab.store.openDatabase(java.nio.file.Paths.get(dbPath))
    val definitionRepo = DefinitionRepository(db)
    val runRepo = RunRepository(db)
    val executor = RunExecutor(runRepo)
    val service = LabService(definitionRepo, runRepo, executor)
    val clock = { System.currentTimeMillis() }

    if (seed) {
        lab.engine.seed.SeedData.seedIfAbsent(definitionRepo, runRepo, executor, clock)
    }

    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true; prettyPrint = true })
    }
    install(StatusPages) {
        exception<LabException> { call, cause ->
            call.respond(
                HttpStatusCode.fromValue(cause.statusCode),
                ErrorJson(cause.message ?: "error", cause.statusCode),
            )
        }
        exception<IllegalArgumentException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ErrorJson(cause.message ?: "bad request", 400))
        }
    }

    routing {
        get("/") {
            call.respondText(WebPage.render(), ContentType.Text.Html)
        }
        get("/api/definitions") {
            call.respond(service.listDefinitions())
        }
        post("/api/definitions") {
            val req = call.receive<CreateDefinitionRequest>()
            call.respond(HttpStatusCode.Created, service.definitionRowToDetail(service.createDraft(req.name, req.body, clock())))
        }
        get("/api/definitions/{name}") {
            val name = call.parameters["name"]!!
            val version = call.request.queryParameters["version"]?.toInt()
            call.respond(service.definitionDetail(name, version))
        }
        post("/api/definitions/{name}/versions") {
            val name = call.parameters["name"]!!
            val req = call.receive<CreateVersionRequest>()
            val row = service.createNextVersion(name, req.body, clock())
            call.respond(HttpStatusCode.Created, service.definitionRowToDetail(row))
        }
        put("/api/definitions/{name}/versions/{version}/draft") {
            val name = call.parameters["name"]!!
            val version = call.parameters["version"]!!.toInt()
            val req = call.receive<UpdateDraftRequest>()
            val row = service.updateDraft(name, version, req.expectedRevision, req.body, clock())
            call.respond(service.definitionRowToDetail(row))
        }
        post("/api/definitions/{name}/versions/{version}/publish") {
            val name = call.parameters["name"]!!
            val version = call.parameters["version"]!!.toInt()
            val row = service.publish(name, version, clock())
            call.respond(service.definitionRowToDetail(row))
        }
        post("/api/runs") {
            val req = call.receive<CreateRunRequestJson>()
            val view = service.createRun(req, clock())
            call.respond(if (view.created) HttpStatusCode.Created else HttpStatusCode.OK, view)
        }
        get("/api/runs/by-key/{key}") {
            call.respond(service.getRunByIdempotencyKey(call.parameters["key"]!!))
        }
        get("/api/runs/{id}") {
            call.respond(service.getRun(call.parameters["id"]!!))
        }
        get("/api/runs/{id}/comparison") {
            call.respond(service.compareRun(call.parameters["id"]!!))
        }
        get("/api/runs/{id}/reduce") {
            call.respond(service.reduceRun(call.parameters["id"]!!))
        }
        post("/api/interleavings") {
            val req = call.receive<InterleavingRequest>()
            val list = service.interleavings(req.parties, req.limit)
            call.respond(list.map { InterleavingView(it.index, it.events) })
        }
        post("/api/commutativity") {
            val req = call.receive<CommutativityRequestJson>()
            call.respond(service.commutativity(req).map(CommutativityCheck::toView))
        }
    }
}

fun LabService.definitionRowToDetail(row: lab.store.DefinitionRow): DefinitionDetailJson =
    definitionDetail(row.name, row.version)

fun CommutativityCheck.toView(): CommutativityView = CommutativityView(
    eventA = eventA,
    eventB = eventB,
    declared = declared,
    passes = passes,
    sameState = sameState,
    sameOutputs = sameOutputs,
    stateAB = stateAB,
    stateBA = stateBA,
    outputsAB = outputsAB,
    outputsBA = outputsBA,
)
