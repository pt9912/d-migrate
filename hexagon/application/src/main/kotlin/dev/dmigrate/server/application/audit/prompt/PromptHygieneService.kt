package dev.dmigrate.server.application.audit.prompt

/**
 * LF-017 / LF-024 / LN-030 / LN-031— zentraler Hygiene-Vertrag, der vor
 * jedem Provider-Aufruf läuft.
 *
 * Pflichten (LF-012 / LN-011 / LN-017 / LN-027):
 *
 * 1. Secret-Pattern erkennen und blocken (JDBC-Passwörter, OAuth-
 *    Bearer, API-Keys, PEM-Schlüssel, ...). Maskierung allein
 *    reicht nicht — wenn Secrets im Prompt landen, hat der
 *    Tool-Handler bereits einen Bug, und die Hygiene macht ihn
 *    sichtbar (LF-012 / LN-011 / LN-017 / LN-027 LF-017 / LF-024 / LN-030 / LN-031 Akzeptanz).
 * 2. Inline-Massendaten (CSV/JSON/SQL > Limit) blocken — das
 *    Modell soll Ressourcen-Refs sehen, keine Rohdaten (LF-012 / LN-011 / LN-017 / LN-027).
 * 3. Externe URLs außerhalb der Whitelist blocken — verhindert,
 *    dass ein Agent dem Modell Tracking-/Exfil-URLs unterschiebt.
 * 4. Prompt- und Payload-Fingerprint deterministisch berechnen
 *    (SHA-256 über UTF-8). Diese Fingerprints binden später
 *    Idempotency, Approval-Grant und Artefakt-Provenance.
 * 5. Bei [PromptHygieneResult.Block] **niemals** den Eingabetext
 *    oder Match-Inhalte ausgeben — nur die Klasse und ein
 *    generischer Public-Message. LF-012 / LN-011 / LN-017 / LN-027 LF-017 / LF-024 / LN-030 / LN-031 Akzeptanz:
 *    "Fehlerdetails enthalten keine Secrets".
 *
 * Implementierungen:
 *
 * - [DefaultPromptHygieneService] — pattern-basiert, in-process,
 *   keine externen Abhängigkeiten.
 *
 * Die Hygiene ist **stateless** — gleicher Request liefert
 * byte-identisches Ergebnis. Das ist die Grundlage für die
 * `approvalKey`-Idempotenz in LF-017 / LF-024 / LN-030 / LN-031.
 */
fun interface PromptHygieneService {

    fun sanitize(request: PromptHygieneRequest): PromptHygieneResult
}
