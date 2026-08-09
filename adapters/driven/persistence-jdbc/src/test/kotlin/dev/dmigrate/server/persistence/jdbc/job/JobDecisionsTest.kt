package dev.dmigrate.server.persistence.jdbc.job

import dev.dmigrate.server.core.job.JobCancelRequest
import dev.dmigrate.server.core.job.JobRecord
import dev.dmigrate.server.core.job.JobStatus
import dev.dmigrate.server.core.job.JobVisibility
import dev.dmigrate.server.core.job.ManagedJob
import dev.dmigrate.server.core.pagination.PageRequest
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.resource.ResourceKind
import dev.dmigrate.server.core.resource.ServerResourceUri
import dev.dmigrate.server.ports.JobTransitionOutcome
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Instant

/**
 * Offset-Pagination — reiner Algorithmus, vorher nur ueber einen
 * Postgres-Integrationslauf erreichbar.
 */
class PaginateTest : FunSpec({

    val zehn = (1..10).map { "i$it" }

    fun page(size: Int, token: String? = null) = PageRequest(pageSize = size, pageToken = token)

    test("erste Seite liefert das Folge-Token") {
        val r = paginate(zehn, page(3))
        r.items shouldBe listOf("i1", "i2", "i3")
        r.nextPageToken shouldBe "3"
    }

    test("Folge-Token setzt exakt dort fort") {
        paginate(zehn, page(3, "3")).items shouldBe listOf("i4", "i5", "i6")
    }

    test("die letzte Seite traegt kein Folge-Token") {
        val r = paginate(zehn, page(4, "8"))
        r.items shouldBe listOf("i9", "i10")
        r.nextPageToken shouldBe null
    }

    test("genau aufgehende Seitengroesse liefert kein Token") {
        // Klassischer Off-by-one: end == size, also gibt es nichts mehr.
        paginate(zehn, page(10)).nextPageToken shouldBe null
        paginate(zehn, page(5, "5")).nextPageToken shouldBe null
    }

    test("unlesbares Token beginnt bei 0 statt zu scheitern") {
        // Tokens kommen von aussen; ein kaputtes darf keine Ausnahme
        // durch den Port tragen.
        listOf("abc", "", "1.5", "9999999999999999999").forEach { kaputt ->
            paginate(zehn, page(2, kaputt)).items shouldBe listOf("i1", "i2")
        }
    }

    test("negatives Token wird auf 0 geklemmt") {
        paginate(zehn, page(2, "-5")).items shouldBe listOf("i1", "i2")
    }

    test("Token jenseits des Endes liefert eine leere Seite ohne Fehler") {
        val r = paginate(zehn, page(3, "99"))
        r.items shouldBe emptyList()
        r.nextPageToken shouldBe null
    }

    test("Seitengroesse unter 1 wird auf 1 angehoben") {
        // Sonst liefe subList(offset, offset) leer und die Pagination
        // stuende still, ohne dass ein Aufrufer den Grund saehe.
        paginate(zehn, page(0)).items shouldBe listOf("i1")
        paginate(zehn, page(-3)).items shouldBe listOf("i1")
    }

    test("leere Liste ist kein Sonderfall") {
        val r = paginate(emptyList<String>(), page(5))
        r.items shouldBe emptyList()
        r.nextPageToken shouldBe null
    }
})

private val T0 = Instant.parse("2026-08-09T12:00:00Z")

private fun record(
    status: JobStatus,
    cancelRequested: Boolean = false,
    requestedReason: String? = null,
) = JobRecord(
    managedJob = ManagedJob(
        jobId = "job_1",
        operation = "data.export",
        status = status,
        createdAt = T0,
        updatedAt = T0,
        expiresAt = T0.plusSeconds(3600),
        createdBy = "alice",
        cancelRequest = JobCancelRequest(
            requested = cancelRequested,
            requestedBy = if (cancelRequested) "erster" else null,
            requestedReason = requestedReason,
            signalSource = if (cancelRequested) "mcp:job_cancel" else null,
        ),
    ),
    tenantId = TenantId("acme"),
    ownerPrincipalId = PrincipalId("alice"),
    visibility = JobVisibility.OWNER,
    resourceUri = ServerResourceUri(TenantId("acme"), ResourceKind.JOBS, "job_1"),
)

/**
 * Statusuebergang und Abbruch-Anforderung — Entscheidung ohne Datenbank.
 */
class JobTransitionDecisionTest : FunSpec({

    val spaeter = T0.plusSeconds(30)

    test("fehlende Zeile ist NotFound, ohne den Transformer zu rufen") {
        var gerufen = false
        val d = decideTransition(null, setOf(JobStatus.RUNNING)) { gerufen = true; it }
        d shouldBe JobTransitionDecision.Complete(JobTransitionOutcome.NotFound)
        gerufen shouldBe false
    }

    test("unerlaubter Ausgangszustand meldet IllegalTransition — Transformer bleibt ungerufen") {
        // Der Transformer ist die Regel des Aufrufers; sie darf keine Zustaende
        // sehen, aus denen sie nie haette rechnen sollen.
        var gerufen = false
        val d = decideTransition(record(JobStatus.SUCCEEDED), setOf(JobStatus.RUNNING)) { gerufen = true; it }
        d shouldBe JobTransitionDecision.Complete(
            JobTransitionOutcome.IllegalTransition(JobStatus.SUCCEEDED),
        )
        gerufen shouldBe false
    }

    test("erlaubter Ausgangszustand wendet den Transformer an und schreibt") {
        val d = decideTransition(record(JobStatus.RUNNING), setOf(JobStatus.RUNNING)) {
            it.copy(status = JobStatus.SUCCEEDED, updatedAt = spaeter)
        }.shouldBeInstanceOf<JobTransitionDecision.Write>()
        d.record.managedJob.status shouldBe JobStatus.SUCCEEDED
        d.record.managedJob.updatedAt shouldBe spaeter
    }
})

class CancelRequestDecisionTest : FunSpec({

    val spaeter = T0.plusSeconds(30)

    fun decide(r: JobRecord?, reason: String? = "user-cancel") =
        decideCancelRequest(r, spaeter, "bob", "mcp:job_cancel", reason)

    test("fehlende Zeile ist NotFound") {
        decide(null) shouldBe JobTransitionDecision.Complete(JobTransitionOutcome.NotFound)
    }

    test("ein terminaler Job laesst sich nicht mehr abbrechen") {
        listOf(JobStatus.SUCCEEDED, JobStatus.FAILED, JobStatus.CANCELLED).forEach { status ->
            decide(record(status)) shouldBe JobTransitionDecision.Complete(
                JobTransitionOutcome.IllegalTransition(status),
            )
        }
    }

    test("erster Abbruch traegt Zeitpunkt, Anforderer, Quelle und Grund ein") {
        val d = decide(record(JobStatus.RUNNING)).shouldBeInstanceOf<JobTransitionDecision.Write>()
        val cancel = d.record.managedJob.cancelRequest
        cancel.requested shouldBe true
        cancel.requestedAt shouldBe spaeter
        cancel.requestedBy shouldBe "bob"
        cancel.signalSource shouldBe "mcp:job_cancel"
        cancel.requestedReason shouldBe "user-cancel"
        d.record.managedJob.updatedAt shouldBe spaeter
    }

    test("wiederholter Abbruch behaelt den ersten Grund und schreibt NICHT erneut") {
        // Idempotenz: sonst ueberschriebe ein zweiter Aufruf den urspruenglichen
        // Grund und die Herkunft des Abbruchs waere nicht mehr rekonstruierbar.
        val bestehend = record(JobStatus.RUNNING, cancelRequested = true, requestedReason = "erster-grund")
        val d = decide(bestehend, reason = "zweiter-grund")
            .shouldBeInstanceOf<JobTransitionDecision.Complete>()
        val outcome = d.outcome.shouldBeInstanceOf<JobTransitionOutcome.Applied>()
        outcome.record.managedJob.cancelRequest.requestedReason shouldBe "erster-grund"
        outcome.record shouldBe bestehend
    }
})
