package dev.dmigrate.server.application.approval

import dev.dmigrate.server.core.approval.ApprovalCorrelationKind
import dev.dmigrate.server.core.approval.GrantRequest
import dev.dmigrate.server.ports.contract.Fixtures
import dev.dmigrate.server.ports.memory.InMemoryApprovalGrantStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

class GrantIssuerTest : FunSpec({

    fun request(
        tenant: String = "acme",
        caller: String = "alice",
        tool: String = "data_profile_start",
        approvalRequestId: String = "req-1",
        scopes: Set<String> = setOf("data.read"),
    ) = GrantRequest(
        tenantId = Fixtures.tenant(tenant),
        callerId = Fixtures.principal(caller),
        toolName = tool,
        approvalRequestId = approvalRequestId,
        correlationKind = ApprovalCorrelationKind.IDEMPOTENCY_KEY,
        correlationKey = "idem-1",
        payloadFingerprint = "fp-1",
        requiredScopes = scopes,
        expiresAt = Fixtures.NOW.plusSeconds(600),
    )

    context("FailClosedGrantIssuer") {

        test("liefert immer NotIssuable mit policy:no-issuer-configured") {
            val issuance = FailClosedGrantIssuer.issue(request(), Fixtures.NOW)
            issuance shouldBe GrantIssuance.NotIssuable(FailClosedGrantIssuer.REASON)
            issuance.shouldBeInstanceOf<GrantIssuance.NotIssuable>()
            issuance.reason shouldBe "policy:no-issuer-configured"
        }
    }

    context("ConfiguredAllowlistGrantIssuer") {

        fun issuer(
            store: InMemoryApprovalGrantStore = InMemoryApprovalGrantStore(),
            rules: List<GrantIssuanceRule>,
            seq: AtomicInteger = AtomicInteger(0),
        ) = ConfiguredAllowlistGrantIssuer(
            store = store,
            rules = rules,
            issuerFingerprint = "issuer-fp",
            tokenFactory = { "tok_${seq.incrementAndGet()}" },
        ) to store

        test("matchende Regel stellt Grant aus, persistiert ueber Fingerprint") {
            val (svc, store) = issuer(
                rules = listOf(GrantIssuanceRule(toolName = "data_profile_start")),
            )
            val issuance = svc.issue(request(), Fixtures.NOW)
            issuance.shouldBeInstanceOf<GrantIssuance.Issued>()
            issuance.approvalToken shouldBe "tok_1"
            issuance.grant.approvalTokenFingerprint shouldBe ApprovalTokenFingerprint.compute("tok_1")
            issuance.grant.approvalRequestId shouldBe "req-1"
            issuance.grant.issuedScopes shouldBe setOf("data.read")
            issuance.grant.grantSource shouldBe "configured-allowlist"
            issuance.grant.issuerFingerprint shouldBe "issuer-fp"

            // Store hat den Grant — aber NUR per Fingerprint, nie per rohem Token.
            store.findByTokenFingerprint(
                tenantId = issuance.grant.tenantId,
                approvalTokenFingerprint = issuance.grant.approvalTokenFingerprint,
            ) shouldBe issuance.grant
        }

        test("kein Match -> NotIssuable, kein Store-Write") {
            val (svc, store) = issuer(
                rules = listOf(GrantIssuanceRule(toolName = "andere_tool")),
            )
            val issuance = svc.issue(request(), Fixtures.NOW)
            issuance shouldBe GrantIssuance.NotIssuable(
                ConfiguredAllowlistGrantIssuer.REASON_NOT_ON_ALLOWLIST,
            )
            // Store ist leer.
            store.findByTokenFingerprint(
                tenantId = Fixtures.tenant("acme"),
                approvalTokenFingerprint = "any",
            ) shouldBe null
        }

        test("Regel mit explizit beschraenkten grantedScopes -> NotIssuable wenn unzureichend") {
            val (svc, _) = issuer(
                rules = listOf(
                    GrantIssuanceRule(
                        toolName = "data_profile_start",
                        grantedScopes = setOf("data.read"),
                    ),
                ),
            )
            val issuance = svc.issue(request(scopes = setOf("data.read", "schema.write")), Fixtures.NOW)
            issuance shouldBe GrantIssuance.NotIssuable(
                ConfiguredAllowlistGrantIssuer.REASON_SCOPES_INSUFFICIENT,
            )
        }

        test("Regel mit grantedScopes Superset stellt mit erweiterten Scopes aus") {
            // Ausgestellte Scopes koennen mehr sein als verlangte — solange sie
            // verlangte abdecken, ist das ok (LF-012 / LN-011 / LN-017 / LN-027: containsAll-Check).
            val (svc, _) = issuer(
                rules = listOf(
                    GrantIssuanceRule(
                        toolName = "data_profile_start",
                        grantedScopes = setOf("data.read", "data.export"),
                    ),
                ),
            )
            val issuance = svc.issue(request(scopes = setOf("data.read")), Fixtures.NOW)
            issuance.shouldBeInstanceOf<GrantIssuance.Issued>()
            issuance.grant.issuedScopes shouldBe setOf("data.read", "data.export")
        }

        test("erste passende Regel gewinnt") {
            val (svc, _) = issuer(
                rules = listOf(
                    GrantIssuanceRule(toolName = "data_profile_start"),
                    GrantIssuanceRule(toolName = "data_profile_start", grantedScopes = emptySet()),
                ),
            )
            val issuance = svc.issue(request(), Fixtures.NOW)
            issuance.shouldBeInstanceOf<GrantIssuance.Issued>()
            issuance.grant.issuedScopes shouldBe setOf("data.read")
        }

        test("rohes Token ist nur in Issued, nicht im gespeicherten Grant") {
            // LF-012 / LN-011 / LN-017 / LN-027: rohe Tokens erscheinen nicht in Store oder Audit.
            val (svc, store) = issuer(
                rules = listOf(GrantIssuanceRule()),
            )
            val issuance = svc.issue(request(), Fixtures.NOW) as GrantIssuance.Issued
            // Im Grant existiert nur der Fingerprint — typsicher, kein
            // Feld traegt das rohe Token.
            issuance.grant.approvalTokenFingerprint shouldBe ApprovalTokenFingerprint.compute(issuance.approvalToken)
            val stored = store.findByTokenFingerprint(
                issuance.grant.tenantId,
                issuance.grant.approvalTokenFingerprint,
            )!!
            // Defense-in-depth: gespeicherte Repraesentation enthaelt das
            // rohe Token nirgends (auch nicht als unbenutztes Feld).
            (stored.toString().contains(issuance.approvalToken)) shouldBe false
        }
    }

    context("DemoAutoApprovalGrantIssuer") {

        fun issuer(
            store: InMemoryApprovalGrantStore = InMemoryApprovalGrantStore(),
            seq: AtomicInteger = AtomicInteger(0),
        ) = DemoAutoApprovalGrantIssuer(
            store = store,
            tokenFactory = { "demo_${seq.incrementAndGet()}" },
        ) to store

        test("stellt fuer jede Anfrage aus, Audit-Markierung ueber grantSource und issuerFingerprint") {
            val (svc, _) = issuer()
            val issuance = svc.issue(request(), Fixtures.NOW)
            issuance.shouldBeInstanceOf<GrantIssuance.Issued>()
            issuance.grant.grantSource shouldBe "demo-auto-approval"
            issuance.grant.issuerFingerprint shouldBe "demo-auto-approval"
        }

        test("zwei Issues fuer gleichen Request liefern verschiedene Tokens und Grants") {
            val (svc, store) = issuer()
            val a = svc.issue(request(), Fixtures.NOW) as GrantIssuance.Issued
            val b = svc.issue(request(), Fixtures.NOW) as GrantIssuance.Issued
            (a.approvalToken == b.approvalToken) shouldBe false
            (a.grant.approvalTokenFingerprint == b.grant.approvalTokenFingerprint) shouldBe false
            // Beide sind im Store.
            listOfNotNull(
                store.findByTokenFingerprint(a.grant.tenantId, a.grant.approvalTokenFingerprint),
                store.findByTokenFingerprint(b.grant.tenantId, b.grant.approvalTokenFingerprint),
            ).shouldHaveSize(2)
        }
    }

    test("now-Param bleibt aktuell unbenutzt — kein Side-Effect aus dem Aufruf-Zeitstempel") {
        // Sanity-Check: der now-Param ist Teil der GrantIssuer-Surface fuer
        // spaetere Issuer (z.B. signed-grant-file mit eigener now-Validierung),
        // aber die drei aktuell ausgelieferten Issuer ignorieren ihn — sie
        // verwenden ausschliesslich GrantRequest.expiresAt.
        val (svc, _) = run {
            val store = InMemoryApprovalGrantStore()
            DemoAutoApprovalGrantIssuer(store) to store
        }
        val past = svc.issue(request(), Instant.parse("2000-01-01T00:00:00Z"))
        val future = svc.issue(request(), Instant.parse("2099-01-01T00:00:00Z"))
        past.shouldBeInstanceOf<GrantIssuance.Issued>()
        future.shouldBeInstanceOf<GrantIssuance.Issued>()
        past.grant.expiresAt shouldBe future.grant.expiresAt
    }
})
