# Troubleshooting-Leitfaden

> **Zielgruppe:** Personen, deren d-migrate-Lauf fehlschlägt, unerwartet abbricht
> oder ein überraschendes Ergebnis liefert und die schnell zur Ursache kommen
> wollen. Der Leitfaden führt von **Exit-Code bzw. Symptom → Diagnose → Behebung**.

---

## 1. Zweck und Abgrenzung

Dieser Leitfaden ist die **schnelle Triage**. Für die Tiefe verweist er zurück:

- **Eine konkrete Fehlermeldung im Klartext** (z. B. „Authentication failed") schlagen
  Sie im [Anwenderhandbuch, Abschnitt 5 (Fehlerbehebung)](anwenderhandbuch.md#5-fehlerbehebung)
  nach — dort steht der Meldungskatalog nach dem Muster Symptom → Ursache → Lösung.
- **Wie vermeide ich Probleme von vornherein?** → [Best-Practices-Leitfaden](best-practices-leitfaden.md).
- **Wie läuft eine Migration ab?** → [Migrations-Leitfaden](migrations-leitfaden.md).

Hier geht es um **„Mein Lauf endet mit Exit N — was nun?"** und um **Betriebssymptome
ohne eindeutige Fehlermeldung** (langsamer Transfer, Verify-Divergenz, Cross-Dialect-
Daten wirken falsch).

---

## 2. Schnelltriage nach Exit-Code

Jeder Lauf endet mit einem stabilen Exit-Code. Die vollständige Tabelle steht im
[Anwenderhandbuch, Anhang B](anwenderhandbuch.md#anhang-b--exit-codes); hier die
Triage mit Sprungziel:

| Exit | Name | Typische Ursache | Wo weiter |
| ---- | ---- | ---------------- | --------- |
| `0` | SUCCESS | Erfolg — Warnungen sind möglich (gehen nach stderr) | Warnungen lesen ([Abschnitt 5](#5-reverse-generate-und-migrate)) |
| `1` | ERROR | Allgemeiner Fehler. **Bei `schema compare` bedeutet `1` „Unterschiede gefunden"** — dort erwartbar, kein Defekt | Meldung lesen; bei compare siehe [Abschnitt 5](#5-reverse-generate-und-migrate) |
| `2` | USAGE_ERROR | Ungültige Flags / unzulässige Kombination (z. B. `--parallel > 1` mit `--resume`/`--atomic`, `--chunk-size 0`) | Kommando-Referenz; [Best-Practices-Leitfaden, Abschnitt 3](best-practices-leitfaden.md#3-performance-tuning) |
| `3` | VALIDATION_ERROR | Validierung fehlgeschlagen, Resume-Inkompatibilität, **oder `data transfer --verify`-Divergenz** | [Abschnitt 6](#6-daten-transfer-import-und-verify) |
| `4` | CONNECTION_ERROR | DB nicht erreichbar / Authentifizierung | [Abschnitt 4](#4-verbindung-und-zugangsdaten) |
| `5` | MIGRATION_ERROR | Fehler **während** der Ausführung (Streaming, Apply) | [Abschnitt 6](#6-daten-transfer-import-und-verify) |
| `6` | AI_ERROR | KI-Provider nicht erreichbar (MCP-Prozedur-Transformation) | Provider-Konfiguration/Netz prüfen |
| `7` | LOCAL_ERROR | Konfiguration/Datei/IO: `.d-migrate.yaml` nicht ladbar, unbekannter Connection-Name, `credentialRef` unauflösbar, ungültiger `pipeline.*`-/`pool.*`-Config-Wert | [Abschnitt 4](#4-verbindung-und-zugangsdaten) |
| `8` | MIGRATION_BLOCKED | Riskante (datenlöschende) Operation, für das Ziel nicht erzeugbar, oder Drift/Fingerprint-Mismatch | [Abschnitt 5](#5-reverse-generate-und-migrate) |
| `130` | INTERRUPTED | Mit Ctrl+C abgebrochen | Fortsetzen via `--resume` ([Abschnitt 6](#6-daten-transfer-import-und-verify)) |

**Faustregel:** `2`/`7` sind **Ihre** Fehler (Aufruf/Config), `4` ist die Umgebung,
`8` ist eine **bewusste** Schutzblockade, `3`/`5` treten während der Arbeit auf.

---

## 3. Diagnose-Werkzeuge

Bevor Sie raten, machen Sie das Problem sichtbar:

- **`--report <datei>`** (migrate/generate) schreibt alle Notes, `action_required`-
  Objekte und Risiken strukturiert heraus — der erste Blick bei Exit 8 oder
  unerwartet fehlenden Objekten.
- **`schema migrate --plan-only`** zeigt den geplanten Diff, **ohne** ihn auszuführen;
  **`--dry-run`** validiert den Lauf ohne Schreibzugriff. Beide trennen „Plan falsch"
  von „Ausführung fehlgeschlagen".
- **`--output-format json`** macht Fehler maschinenlesbar (gleicher Exit-Code, Details
  im JSON) — nützlich in CI-Logs.
- **`--verbose`** erhöht die Log-Tiefe; Passwörter/Secrets bleiben maskiert (`***`).
- **`schema compare` als Diagnose:** unerwartetes Ergebnis? Vergleichen Sie Soll↔Ist —
  Exit 1 listet die konkreten Unterschiede. **`data profile`** stellt Kennzahlen je
  Quelle/Ziel gegenüber.

---

## 4. Verbindung und Zugangsdaten

Symptome: Exit 4 (Connection) oder Exit 7 (Config/credentialRef). Der Meldungskatalog
(„Connection refused", „Authentication failed", „credentialRef fail-closed", „Unknown
dialect", „Database does not exist") steht im
[Anwenderhandbuch, Abschnitt 5](anwenderhandbuch.md#5-fehlerbehebung). Schnelle Diagnose:

- **Erreichbarkeit zuerst:** Läuft die DB? Stimmen Host/Port? d-migrate testet die
  Verbindung mit `SELECT 1` — schlägt schon das fehl, liegt es nicht an d-migrate.
- **Sonderzeichen im Passwort** müssen in der URL kodiert werden (`@` → `%40`, `:` →
  `%3A` …) — oder das Passwort per `${VAR}`/Store aus der URL heraushalten.
- **`credentialRef` bricht ab (Exit 7, fail-closed):** d-migrate verbindet bewusst
  nicht ohne Secret; die Meldung nennt den Grund (`FILE_NOT_FOUND`/`ENV_NOT_SET`/
  `PROVIDER_MISSING`), nie das Secret. Behebung im
  [Anwenderhandbuch, Abschnitt 5](anwenderhandbuch.md#5-fehlerbehebung); Schicht-Wahl
  und Prioritätskette im [Best-Practices-Leitfaden, Abschnitt 8](best-practices-leitfaden.md#8-credential-handling)
  und [Administrationshandbuch, Abschnitt 4.6](administrationshandbuch.md#46-credential-handling).
- **`--source <name>` wird nicht gefunden (Exit 7):** der Connection-Name fehlt in der
  `.d-migrate.yaml`, oder es wird eine andere Config-Datei geladen als gedacht — Pfad-
  Auflösung siehe [Administrationshandbuch, Abschnitt 3](administrationshandbuch.md#3-konfiguration).

---

## 5. Reverse, Generate und Migrate

- **Reverse liefert keine Views/Trigger/Functions/Procedures.** Der Default-Reverse
  liest nur Tabellen. `--include-all` (oder gezielt `--include-*`) setzen — sonst fehlen
  die Objekte **still** schon im neutralen Modell (Meldungseintrag im
  [Anwenderhandbuch, Abschnitt 5](anwenderhandbuch.md#5-fehlerbehebung)).
- **Objekte werden übersprungen (`action_required`, Exit bleibt `0`).** Nicht
  regelbasiert übersetzbarer dialektspezifischer SQL-Inhalt in Views/Functions/
  Procedures/Triggern (**E053**) oder eine für das Ziel nicht erzeugbare Struktur
  (z. B. leere oder vom Zieldialekt nicht unterstützte Partition **E055**) werden
  übersprungen und im **`--report`** ausgewiesen — der Lauf bricht bewusst **nicht** ab.
  Prüfen Sie den Report und bilden Sie die Objekte zielseitig nach.
- **`schema migrate` blockiert (Exit 8).** Eine riskante, datenlöschende Operation
  wurde erkannt. Report ansehen; ist sie beabsichtigt, erlauben Sie sie gezielt mit
  **`--allow-destructive`** ([Anwenderhandbuch, Abschnitt 5](anwenderhandbuch.md#5-fehlerbehebung)).
  Ein zu knappes Lock-Budget lockern Sie mit `--lock-timeout-ms` (10–60000 ms, Default 5000).
- **Rollback/Overlay bricht nach einem d-migrate-Update ab (Exit 8,
  `…FINGERPRINT…MISMATCH`).** Ältere Artefakte pinnen ein früheres Fingerabdruck-
  Verfahren. Erzeugen Sie das Artefakt mit der aktuellen Version neu (`migrate
  --generate-rollback` erneut) — Details im
  [Anwenderhandbuch, Abschnitt 5](anwenderhandbuch.md#5-fehlerbehebung).
- **`schema compare` meldet Exit 1.** Das ist kein Fehler, sondern das Signal
  „Unterschiede gefunden" (CI-Gate). Die Diff-Liste nennt die Abweichungen; bewusste
  Cross-Dialect-Degradationen erkennen Sie an den `W…`-Notes ([Abschnitt 7](#7-cross-dialect-überraschungen)).

---

## 6. Daten-Transfer, Import und Verify

- **`--verify` meldet Divergenz (Exit 3).** Die Prüfsumme über eine Tabelle stimmt
  nicht. Häufigste Ursachen: **kein sauberer Load** (Ziel war nicht leer — mit
  `--truncate` fahren) oder eine echte Transfer-Lücke. Repräsentativ umgeformte
  Cross-Dialect-Spalten (`text[]`→`json`, `timestamptz`→`datetime`) sind **kein**
  Divergenzgrund — sie werden ausgeschlossen und im Report ausgewiesen. Mechanik:
  [Migrations-Leitfaden, Abschnitt 10.3](migrations-leitfaden.md#103-sha-256-verifikation).
- **Fremdschlüssel-Fehler beim Laden.** Tabellen werden topologisch sortiert; für
  Bulk-Loads gelten die FK-/Trigger-/Reihenfolge-Empfehlungen im
  [Best-Practices-Leitfaden, Abschnitt 5](best-practices-leitfaden.md#5-verifikation-und-sauberer-load)
  (u. a. `--disable-fk-checks` mit anschließender Integritätsprüfung, `--trigger-mode disable`,
  post-data zuletzt).
- **„database is locked" / Timeout bei SQLite.** SQLite erlaubt nur **einen** Schreiber
  (Pool 1). Läuft parallel ein anderer Zugriff, hilft ein größeres `busy_timeout`
  (Default 5000 ms in der URL) — und **nicht** gegen die Pool-1-Klemme antunen.
- **Transfer ist langsam.** Kein Fehler, sondern Tuning: `--parallel`, `--chunk-size`,
  `--fetch-size` und die Pool-Größe. Faustregeln und Wechselwirkungen (u. a. `--parallel`
  ⊥ `--resume`/`--atomic`) im [Best-Practices-Leitfaden, Abschnitt 3](best-practices-leitfaden.md#3-performance-tuning).
- **Wiederaufnahme schlägt mit Exit 3 fehl.** Die Resume-Referenz ist semantisch
  inkompatibel (Format/Filter/Tabellen/Optionen weichen vom Erst-Lauf ab). Nehmen Sie
  denselben Aufruf wie beim unterbrochenen Lauf; Details im
  [Anwenderhandbuch, Abschnitt 5](anwenderhandbuch.md#5-fehlerbehebung).

---

## 7. Cross-Dialect-Überraschungen

Symptom: kein Fehler, aber Typen oder Daten sehen im Ziel anders aus, oder ein
Round-Trip ist nicht zeichengleich.

- **Typ sieht anders aus** (z. B. `DECIMAL`→`REAL` in SQLite, `text[]`→`JSON`/`TEXT`,
  Enum ohne `CHECK` im migrate-Pfad). Das ist eine **gemeldete** Degradation, kein
  stiller Verlust — die zugehörige `W…`-Note benennt sie. Kuratierte Fallstrick-Liste:
  [Best-Practices-Leitfaden, Abschnitt 4](best-practices-leitfaden.md#4-cross-dialect-typ-fallstricke).
- **Round-Trip (Reverse → Generate → Reverse) ist nicht identisch.** Erwartbar, wenn
  der Zieldialekt eine Eigenschaft nur emuliert (z. B. SQLite-Sequenzen). Was stabil
  bleibt und was bewusst degradiert: [Migrations-Leitfaden, Abschnitt 6.6](migrations-leitfaden.md#66-round-trip-risiko-verstehen).
- **AUTOINCREMENT fehlt in SQLite** bei einer Identity-Spalte in einem
  **zusammengesetzten** Primärschlüssel (**W135**, siehe
  [Best-Practices-Leitfaden, Abschnitt 4](best-practices-leitfaden.md#4-cross-dialect-typ-fallstricke)) —
  die IDs müssen dann explizit geliefert werden.
- **Grundregel:** Warnungen sind das Diagnosewerkzeug. Ein Lauf mit Exit 0 **und**
  `W…`-Notes ist erfolgreich, aber nicht folgenlos — lesen Sie die Notes, bevor Sie
  abnehmen.

---

## Verwandte Dokumentation

- [Anwenderhandbuch](anwenderhandbuch.md) · [Best-Practices-Leitfaden](best-practices-leitfaden.md) · [Migrations-Leitfaden](migrations-leitfaden.md) · [Administrationshandbuch](administrationshandbuch.md) · [API-Referenz](api-referenz.md)
- [`spec/cli-spec.md`](../../spec/cli-spec.md), [`spec/connection-config-spec.md`](../../spec/connection-config-spec.md), [`spec/type-mapping.md`](../../spec/type-mapping.md)
