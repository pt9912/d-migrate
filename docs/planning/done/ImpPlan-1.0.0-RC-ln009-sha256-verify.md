# ImpPlan 1.0.0-RC — LN-009: SHA-256-Verifikation der Datenintegrität (`data transfer --verify`)

> Status: **DONE / graduiert** (2026-07-12; [ADR 0030](../../adr/0030-datenwert-kanonisierung-verify.md)).
> Phase A (Kanonik-Kern + PBT), B (Verifier + `--verify`), C (familien-basierter
> Cross-Dialekt-Ausschluss + Live-Smoke), D (Spec/Doku/ADR) abgeschlossen; roadmap
> Roadmap-Eintrag → ✅. Design-Delta zur Planung: **D3 wurde von Wert-Projektion auf
> familien-basierten Ausschluss revidiert** (siehe unten „## Closure"). Schließt den letzten
> [`LN-009`](../../../spec/lastenheft-d-migrate.md#ln-009)-Gap (Roadmap-Milestone-1.0.0-RC,
> Fußnote ²): ein nutzerseitiges `--verify` samt echter Quelle↔Ziel-
> Reconciliation im `data transfer`-Pfad. Der reale SHA-256-Byte-Vergleich lebt
> heute nur im TPC-Perf-Harness (`smoke-tpch-perf.sh`, shell/psql, same-dialect);
> die Bundle-Import-Preflight-SHA-256 prüft nur Datei-Integrität/Resume.

## Kontext / Ist-Stand (verifiziert)

- **Command→Wiring→Runner** (Clikt): `DataTransferCommand.kt` (`adapters:driving:cli`)
  → `DataTransferWiring.kt` (`DataTransferOptions`) → `DataTransferRunner.kt`
  (`hexagon:application`, `DataTransferRequest`). Kern-Loop:
  `TransferExecutor.transferTable` (`hexagon:application`) — der einzige
  Choke-Point, durch den beide Seiten fließen. `session.write()` liefert bereits
  ein `WriteResult` (rows inserted/updated/skipped), das heute verworfen wird.
- **Werte** in `DataChunk.rows: List<Array<Any?>>` sind **rohe JDBC-Objekte**
  (`java.sql.Timestamp`, `PGobject`, `BigDecimal`, `ByteArray`, …); `core` ist
  bewusst **JDBC-frei**. `ColumnDescriptor` trägt nur einen opaken `sqlTypeName`.
- **Schema**: `ColumnDefinition.type: NeutralType` — per-Spalte-Neutraltyp liegt
  am Schema. Der Runner liest `srcSchema` **und** `tgtSchema` bereits ein.
- **Reuse-Hebel**:
  - `ValueSerializer` (`adapters:driven:formats`) mappt rohe JDBC-Werte per
    `value::class` auf ein neutrales Modell (Temporal-ISO, BigDecimal, Base64,
    SqlArray rekursiv, PGobject) — aber **nicht** cross-dialect-kanonisch
    (PG-`Bool(true)` ≠ MySQL-`Integer(1)`).
  - `NeutralTypeCanonicalizer` (`hexagon:ports-common`, `typeCanonicalizer()` je
    Driver; ADR 0026 / Fingerprint v7) projiziert Neutraltypen auf die
    Speicher-Realität des Ziel-Dialekts. **Zentral** für Wert-Kanonik (s. D3).
  - `HexEncoding.sha256Hex` (`hexagon:core`) + streamendes `MessageDigest`-Muster
    (`Sha256DigestCalculator`, `StreamingHashWriter`).
- **Exit-Codes** (`spec/job-contract.md` §8.1) sind voll und protokoll-stabil
  (1–7 belegt, 6 = AI-Provider, 130 = Cancel).

## Scope (user-abgestimmt 2026-07-12)

- **Verify-Tiefe:** volle **SHA-256-Content-Reconciliation** (nicht nur Row-Count).
- **Cross-Dialekt:** **voll inkl. Exoten** — JSON semantisch, Array rekursiv,
  Geometry via WKB, plus Typ-Flattening-Projektion (D3).
- **Kommando:** nur `data transfer --verify` (Roadmap-FN²-Scope). `data
  export/import --verify` = späterer Slice.
- **Exit-Code:** Verify-Divergenz → **3** („Validation failed", stabile
  Taxonomie, REST 422 / gRPC INVALID_ARGUMENT). Kein neuer Code.

## Architektur-Entscheidungen

**D1 — Hexagonale Platzierung.** Neuer Port
`ValueCanonicalizer { canonicalize(value: Any?, type: NeutralType): ByteArray }`
in `hexagon:ports-common` (cross-cutting, wie `AuditSink`/`NeutralTypeCanonicalizer`).
Impl `CanonicalValueCodec` im `formats`-Adapter (darf java.sql/Jackson/WKB sehen,
baut auf `ValueSerializer`-Entpackung auf). Framing + Combinator + SHA-256 rein in
`hexagon:application` (`MessageDigest` ist java.security, erlaubt; nur `java.sql`
nicht). Die CLI-Wiring injiziert die Impl in den Runner → `application` bleibt
java.sql-frei (reicht nur `Any?` durch).

**D2 — Reihenfolge-unabhängiger Tabellen-Checksum.** Pro Zeile: Spalten
**namensgeordnet**, jeder Wert **längen-gerahmt** (`[typeTag][len][canonBytes]`) in
ein Pro-Zeilen-SHA-256 (32 Byte) — Framing verhindert Feldgrenzen-Kollision, NULL
ist ein eigener Type-Tag (≠ Leerstring). Tabellen-Checksum = **additive Summe der
256-bit-Zeilendigests mod 2²⁵⁶**. Kein `ORDER BY` ([`LN-005`](../../../spec/lastenheft-d-migrate.md#ln-005)-freundlich), korrekt für
Duplikate/Multiset (anders als XOR, wo Paare sich auslöschen). Vertrag: Schutz
gegen **versehentliche** Korruption/Verlust, nicht adversariell (dokumentiert).

**D3 — Familien-basierter Cross-Dialekt-Ausschluss (REVIDIERT, siehe Closure).**
Jede Seite kanonisiert gegen ihren **eigenen** reverse-engineerten Spaltentyp.
Spalten, deren Quell-/Zieltyp in **verschiedenen Kanonik-Familien** liegen
(repräsentations-transformierend: `text[]`→`json`, `tsvector`→`text`, tz→lokal,
`datetime`→`text`), werden mit W-Code ausgeschlossen; innerhalb einer Familie
kollidieren die Formen (bool↔int, uuid↔text, decimal-Weite). Die kanonischen
Formen sind so entworfen, dass flattening-äquivalente Werte kollidieren (Boolean →
`1`/`0` wie Integer; UUID → Lowercase-Hyphen-String = Text-Kanonik). Prinzip von
ADR 0026 (Fingerprint-Kanonik) auf Wertebene — aber **ohne** Wert-Projektion
(begründet in „## Closure").

**D4 — Kanonische Form je NeutralType** (Ziel-projiziert):

| NeutralType | Kanonische Byte-Form |
|---|---|
| `Text`/`Xml`/`Email`/`Enum` | UTF-8 des Strings (unverändert) |
| `Char(n)` | UTF-8, **trailing-Space normalisiert** (PG-Pad vs MySQL-Trim) |
| `Uuid` | UTF-8 des Lowercase-Hyphen-Strings (`UUID.toString`) |
| `Integer`/`SmallInt`/`BigInteger`/`Identifier` | Dezimalstring ohne Leading-Zeros, kanonisches Vorzeichen; Boolean→`1`/`0` |
| `Decimal` | `BigDecimal.stripTrailingZeros().toPlainString()` (1.50==1.5) |
| `Float` | shortest round-trip Dezimal; **same-width exakt**, cross-width → W-Code-Ausschluss (D5) |
| `BooleanType` | 1 Byte `0x00`/`0x01`; akzeptiert Boolean und Int 0/1 |
| `Date` | ISO-8601 local date |
| `Time` | ISO-8601 local time, fractional trailing-zero normalisiert |
| `DateTime(tz=false)` | ISO local datetime, fractional normalisiert |
| `DateTime(tz=true)` | auf **UTC-Instant** normalisiert (Epoch-Millis/Nanos kanonisch) |
| `Json` | Jackson parse → Object-Keys rekursiv sortiert, Zahlen normalisiert, kein insignifikanter Whitespace → UTF-8 |
| `Array(elem)` | rekursiv je Element (Element-Typ), längen-gerahmte Sequence |
| `Binary` | rohe Bytes |
| `Geometry` | WKB (little-endian), Achsenordnung + SRID normalisiert (Spatial-Slice) |
| `FullText` | PG: normalisierte Lexem-Form; cross-dialect (→Text) über Text-Kanonik |

**D5 — Ehrliche Nicht-Kanonisierbarkeit.** Wo eine deterministische cross-dialect
Kanonik nachweislich unmöglich ist (z. B. Float unterschiedlicher Breite, ein Typ
den das Ziel verlustbehaftet ablegt), wird die **Spalte explizit aus dem Verify
ausgeschlossen** und ein **W-Code** gemeldet — **kein** stiller Pass, **keine** False
Positives. Der Verify-Report listet ausgeschlossene Spalten. (Kein Carve-Out i. S.
v. „kommt in Slice X" — eine vollständige, ehrliche Behandlung des Grenzfalls.)

**D6 — Verify-Pfad.** Nach erfolgreichem Transfer (nicht verschränkt mit dem
Schreibpfad, um den Streaming-Kern nicht zu belasten): `TransferVerifier`
(`hexagon:application`) streamt je Tabelle Quelle **und** Ziel via `DataReader`,
löst Per-Spalte-`(srcType, tgtProjectedType)` aus `srcSchema`/`tgtSchema` auf,
bildet die additiven Tabellen-Checksums und vergleicht. Ein billiger
**Row-Count-Vorabcheck** (aus `WriteResult` bzw. gezählten Zeilen) short-circuittet
offensichtliche Divergenz. Ergebnis `VerifyReport` (pro Tabelle: match / Hashes /
Row-Delta / ausgeschlossene Spalten). Divergenz → Runner-Exit **3**.

## Phasen

- **Phase A — Kanonik-Kern.** Port `ValueCanonicalizer` (ports-common) + Impl
  `CanonicalValueCodec` (formats, alle Typen inkl. Exoten) + additiver
  256-bit-Combinator + Row-Framing (application-Helper). Unit + PBT
  (kotest-property, `NeutralTypeArb`): „semantisch gleiche Werte über
  Dialekt-Repräsentationen → gleiche Bytes", Reihenfolge-Invarianz, Multiset.
- **Phase B — Verify-Orchestrierung + CLI.** `TransferVerifier` + `VerifyReport` +
  `--verify`-Flag (Command→Wiring→Request) + Row-Count-Vorabcheck + Exit 3 +
  Report-Ausgabe. Same-dialect + cross-dialect end-to-end (Unit/Fake-Pools).
- **Phase C — Live-/Exoten-Härtung.** sample-db-Smoke PG→PG / PG→MySQL / SQLite;
  Geometry-WKB gegen echtes PostGIS/SpatiaLite; JSON/Array/Enum; bewusst
  divergentes Paar → Exit 3 (Korruptions-Erkennung). Cross-dialect-PBT-Härtung.
- **Phase D — Spec/Doku/ADR + Gates.** cli-spec `--verify`-Flag + Exit-Code-Zeile;
  `migrations-leitfaden` §10.3 un-🔮; roadmap FN² → ✅; **ADR** für den
  Kanonisierungs-Vertrag (D2–D5, permanenter Design-Vertrag, verweist ADR 0026);
  `make docs-check` + Docker-Build grün.

## Nicht-Scope

- `data export/import --verify` (separater Slice).
- Adversarielle Manipulationssicherheit (D2: additive Set-Kanonik ist gegen
  Zufallskorruption ausgelegt, nicht gegen konstruierte Kollisionen).
- Verschränkung des Verify in den Schreib-Streaming-Kern (D6: separater Pass).

## Closure

**Design-Delta D3 (Wert-Projektion → familien-basierter Ausschluss).** Die
Planung sah vor, beide Seiten gegen den durch `tgtDrv.typeCanonicalizer()`
projizierten Zieltyp zu kanonisieren (analog zur Schema-Fingerprint-Kanonik, ADR
0026). Die Cross-Dialekt-Analyse (Pagila PG→MySQL) zeigte, dass das auf Wertebene
**nicht trägt**: der Quell-Treiber liefert weiterhin den quell-typisierten Wert
(`java.sql.Array` für `text[]`), egal welchen Typ das Ziel speichert —
`canonicalize(sqlArray, Json)` scheitert. Die Projektion ändert den *Typ*, nicht
den *Wert*. Korrekter Mechanismus: jede Seite kanonisiert gegen ihren eigenen Typ
(innerhalb einer Familie kollidieren die Formen ohnehin), und Spalten mit
**familien-fremder** Quell/Ziel-Paarung (repräsentations-transformierend) werden
ehrlich mit W-Code ausgeschlossen. `typeCanonicalizer()` wird vom Verify-Pfad nicht
mehr genutzt.

**Gelieferte Artefakte.** Port `ValueCanonicalizer` (+ `ValueCanonicalizationException`,
`hexagon:ports-common`); `CanonicalValueCodec` + `CanonicalNumeric`/`CanonicalTemporal`/
`CanonicalJson`/`CanonicalGeometry` (`formats`-Adapter); `TableChecksum` +
`TransferVerifier` + `VerifyReport` (`hexagon:application`); `--verify`-Flag
(Command→Wiring→Request), Codec-Injektion, Exit-3-Mapping + Report (`reportVerify`).

**Tests.** PBT über alle 21 NeutralType-Zweige (Determinismus); per-Familie Codec-
Tests (Cross-Repräsentations-Kollision bool↔int, uuid↔text, decimal-strip,
temporal-UTC, JSON semantisch, Geometry-WKB PostGIS↔MySQL + Byte-Order + SRID-0-
Disambiguierung); Combinator (Reihenfolge-Invarianz, Multiset ≠ XOR, NULL≠Leerstring);
Verifier (Match/Checksum-/Row-Count-Divergenz, Cross-Family- + Float-Breiten-
Ausschluss, Inkonklusiv, schema-qualifiziert); Runner (`reportVerify`-Branches,
End-to-End-Verify-Wiring). Alle Module test+detekt+kover≥90% grün.

**Live.** `--verify` in die sample-db-Cross-Smokes integriert (dokumentierte
Exoten-Ausschlüsse); SQLite→SQLite Live-Round-Trip-Smoke (same-dialect exakt).

## Referenzen

- [`LN-009`](../../../spec/lastenheft-d-migrate.md#ln-009),
  [`spec/lastenheft-d-migrate.md` §8.5](../../../spec/lastenheft-d-migrate.md)
  (Byte-für-Byte-Vergleich SHA-256).
- [`spec/cli-spec.md`](../../../spec/cli-spec.md) — `data transfer` (Zielbild).
- [`spec/job-contract.md`](../../../spec/job-contract.md) §8.1 (Exit-Codes).
- [ADR 0026](../../adr/0026-fingerprint-kanonisierung-post-compare.md) —
  Fingerprint-Kanonik / Post-Compare (Schema-Ebene; D3 überträgt das Prinzip auf
  Wertebene). Neuer Vertrag → **ADR 0030** (Phase D).
- [`roadmap.md`](../in-progress/roadmap.md) Milestone 1.0.0-RC, Fußnote ².
