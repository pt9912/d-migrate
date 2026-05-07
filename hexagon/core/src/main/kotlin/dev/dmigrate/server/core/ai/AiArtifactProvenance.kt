package dev.dmigrate.server.core.ai

import dev.dmigrate.server.core.resource.ServerResourceUri

/**
 * Phase G § 5.4 + § 5.5 + § 5.6 (G.6.b) — operationsspezifische
 * Provenance für ein KI-Artefakt.
 *
 * Drei Varianten, weil Plan §5.4 explizit zwischen Plan-,
 * Execute- und Testdaten-Plan-Artefakten unterscheidet — jeder
 * trägt eigene Fingerprint-Felder, damit Replay und
 * Idempotenz-Checks scharf bleiben:
 *
 * - [Plan] — `planPromptFingerprint` + `planPayloadFingerprint`.
 * - [Execute] — `executePromptFingerprint` +
 *   `executePayloadFingerprint` plus die Plan-Bindung
 *   (`planRef`, `planArtifactFingerprint`).
 * - [TestdataPlan] — `testdataPromptFingerprint` +
 *   `testdataPayloadFingerprint`.
 *
 * Die gemeinsamen Properties [promptFingerprint] und
 * [payloadFingerprint] geben den Lookup-Pfad frei, der für alle
 * drei Operationen gleich ist (Audit, AiToolOutcomeStore-Replay
 * usw.); operations-spezifische Felder bleiben in den Subtypen.
 */
sealed interface AiArtifactProvenance {

    /** Hex-codierter SHA-256 über das hygienisierte Prompt. */
    val promptFingerprint: String

    /** Hex-codierter SHA-256 über die normalisierten Tool-Argumente. */
    val payloadFingerprint: String

    /**
     * Plan §5.4: Provenance für `procedure_transform_plan`-
     * Artefakte. Plan-Artefakte sind self-contained — keine
     * Plan-Ref, keine Output-Bindung, weil der Plan selbst das
     * Output ist.
     */
    data class Plan(
        override val promptFingerprint: String,
        override val payloadFingerprint: String,
    ) : AiArtifactProvenance {
        init {
            require(promptFingerprint.length == FP_LEN) {
                "promptFingerprint must be a $FP_LEN-char hex SHA-256"
            }
            require(payloadFingerprint.length == FP_LEN) {
                "payloadFingerprint must be a $FP_LEN-char hex SHA-256"
            }
        }
    }

    /**
     * Plan §5.5 Z. 779-792: Provenance für
     * `procedure_transform_execute`-Zielartefakte. **Pflicht-
     * Bindung an das Plan-Artefakt** — der Execute-Aufruf bringt
     * keine eigenen Source-Refs mit; sie werden ausschliesslich
     * über die Plan-Provenance validiert.
     *
     * @param planRef Tenant-scoped Resource-URI auf das
     *   freigegebene `procedure_transform_plan`-Artefakt.
     * @param planArtifactFingerprint hex-codierter SHA-256 über
     *   die durable persistierten Plan-Artefakt-Bytes. Bindet die
     *   Execute-Provenance an einen exakten Plan-Snapshot — ein
     *   später überschriebener Plan würde denselben Hash nicht
     *   erneut produzieren.
     */
    data class Execute(
        override val promptFingerprint: String,
        override val payloadFingerprint: String,
        val planRef: ServerResourceUri,
        val planArtifactFingerprint: String,
    ) : AiArtifactProvenance {
        init {
            require(promptFingerprint.length == FP_LEN) {
                "executePromptFingerprint must be a $FP_LEN-char hex SHA-256"
            }
            require(payloadFingerprint.length == FP_LEN) {
                "executePayloadFingerprint must be a $FP_LEN-char hex SHA-256"
            }
            require(planArtifactFingerprint.length == FP_LEN) {
                "planArtifactFingerprint must be a $FP_LEN-char hex SHA-256"
            }
        }
    }

    /**
     * Plan §5.6: Provenance für `testdata_plan`-Artefakte.
     * Self-contained wie [Plan]; die Source-Refs (`schemaRef`,
     * optional `profileRef`) liegen in [AiArtifactMetadata.sourceRefs].
     */
    data class TestdataPlan(
        override val promptFingerprint: String,
        override val payloadFingerprint: String,
    ) : AiArtifactProvenance {
        init {
            require(promptFingerprint.length == FP_LEN) {
                "testdataPromptFingerprint must be a $FP_LEN-char hex SHA-256"
            }
            require(payloadFingerprint.length == FP_LEN) {
                "testdataPayloadFingerprint must be a $FP_LEN-char hex SHA-256"
            }
        }
    }

    private companion object {
        const val FP_LEN: Int = 64
    }
}
