# Secret-Leakage: Masker-Lücke und Audit-Dateirechte (P3)

> **Status:** Vorabklärung (2026-07-17)
> **Trigger:** Security-Vollaudit
> ([`security-audit-2026-07-17.md`](security-audit-2026-07-17.md), Befunde 10 und
> 11, beide P3).
> **Aktivierungsbedingung:** P3 — Härtung, Backlog. Klein und lokal; guter
> Quick-Win-Kandidat.

## Befunde

**10 (P3, CWE-532) — `ConnectionSecretMasker`-Allowlist verfehlt
Connector/J-Keystore-Params.** Die Maskierung arbeitet gegen eine Allowlist
bekannter Secret-Parameternamen; die MySQL-Connector/J-Keystore-Passwort-Params
(`trustCertificateKeyStorePassword`, `clientCertificateKeyStorePassword`) fehlen
darin. Folge: Klartext im Audit-JSONL und auf stderr.

Die Allowlist-Bauweise ist die eigentliche Frage: sie ist **fail-open** — was
nicht auf der Liste steht, wird durchgereicht. Bei einem Parameterraum, der pro
Treiber wächst, ist das strukturell die falsche Richtung. Eine Denylist wäre
nicht besser; tragfähiger ist vermutlich, Secrets typisiert zu führen
(vgl. das bereits vorbildliche `ConnectionConfig` mit handgeschriebenem
maskierendem `toString()`) statt sie nachträglich aus Strings herauszufiltern.

**11 (P3, CWE-276) — Audit-JSONL ohne 0600.** `JsonlFileAuditSink` legt die
Datei mit Default-Permissions an. Der Credential-Store macht es im selben Repo
korrekt vor: Temp-Datei im selben Verzeichnis mit `OWNER_RW` **beim Anlegen**
(kein chmod-Race), `ATOMIC_MOVE`, Verzeichnis 0700.

Verwandt (Bericht, Befund im secret-leakage-Abschnitt): `McpServerConfig` ist
`data class` mit Klartext-`introspectionClientSecret` und ohne
`toString()`-Override — Kotlin generiert `toString()` automatisch, das ist die
klassischste Leak-Quelle. Auch hier gibt es das richtige Gegenmuster im Repo:
`StoredCredential` ist bewusst **keine** data class.

## Arbeitspakete (Skizze)

1. Fehlende Connector/J-Params ergänzen (Sofortmaßnahme) **und** entscheiden, ob
   die Allowlist-Richtung bleibt — der Befund ist ein Symptom der fail-open-Bauweise.
2. `JsonlFileAuditSink` auf das `writeAtomically`-Muster des Credential-Stores
   ziehen (0600 beim Anlegen, nicht per nachträglichem chmod).
3. `McpServerConfig`: `toString()`-Override oder data-class-Charakter aufgeben.
4. Regression: Test, der eine Connection-URL mit jedem bekannten Secret-Param
   durch den Masker schickt; Test auf die Dateirechte des Audit-Sinks.

## Fundstellen

- `hexagon/ports-common/src/main/kotlin/dev/dmigrate/driver/connection/ConnectionSecretMasker.kt:27`
- `adapters/driven/audit-logging/src/main/kotlin/dev/dmigrate/server/adapter/audit/logging/JsonlFileAuditSink.kt:23`
- `adapters/driving/mcp/src/main/kotlin/dev/dmigrate/mcp/server/McpServerConfig.kt:38`
- `adapters/driven/connection-config/src/main/kotlin/dev/dmigrate/connection/AesGcmCredentialStore.kt` (korrektes Gegenmuster: `writeAtomically`)
