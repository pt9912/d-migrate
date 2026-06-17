# P2-Pilot-Blocker — Arbeitstracker (0.9.9)

Status: **in Arbeit** (P1 abgeschlossen 2026-06-16; I-05+I-06 behoben
2026-06-17, Commit `b9e6ab38`). Offen: I-07, I-08, I-09, I-10. Dieser Tracker
bereitet den direkten Wiedereinstieg in den P2-Block vor: pro Bug die gegen den
Code lokalisierte Ursache, eine Fix-Skizze, die Test-Strategie und das zu
prüfende Modul.

Quellen:
[Pilot-Report](pilot-validation-0.9.9.md) (Symptome + Repro, Abschnitt 6) ·
[Roadmap-Milestone 0.9.9](roadmap.md).

> **Zeilennummern** sind Stand `develop` 2026-06-16 und können nach Edits
> driften — vor dem Fix kurz gegenprüfen. P1-Vorgehen war: Ursache verifizieren →
> betroffene Dateien zeigen → umsetzen → je ein Regressionstest → `make
> docker-test`/`make integration` grün → committen. Für P2 gleich verfahren.
>
> P2-Definition: **ungültige DDL-Generierung** (das Tool erzeugt Output, der vom
> Zieldialekt abgelehnt wird) bzw. Import-Abbruch. Akzeptables Fix-Ziel je Bug:
> entweder **valides DDL** erzeugen **oder** sauber mit Note/Skip-Code
> aussteigen (kein stiller, kaputter Output).

---

## I-05 — Domain `base_type` rendert neutralen Typnamen + falsche Präzision (P2) — BEHOBEN 2026-06-17

> **Fix:** `b9e6ab38` (zusammen mit I-06). Domain-`baseType` wird im
> Generate-Pfad jetzt über `typeMapper.toSql(NeutralType)` aufgelöst statt per
> `baseType.uppercase()` (biginteger → BIGINT, decimal → DECIMAL(p,s)); rohe
> SQL-Typstrings bleiben als Fallback unverändert. Regressionstests +
> `docker-check`/Live-PG grün.

**Symptom:** `CREATE DOMAIN … AS BIGINTEGER(64,0)` statt `AS BIGINT`. Der
Spaltenpfad mappt korrekt; nur der Domain-Pfad umgeht den Typ-Mapper.

**Ursache (lokalisiert):**
`adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresTypeSequenceDdlSupport.kt`,
Zweig `CustomTypeKind.DOMAIN`. Der `sqlType` wird per
`append(baseType.uppercase())` direkt aus dem gespeicherten neutralen
`typeDef.baseType`-String gebaut und Präzision/Scale werden unbedingt angehängt
— **ohne** den Dialekt-Typ-Mapper. Der unmittelbar darüberliegende
Composite-Type-Zweig macht es richtig: `typeMapper.toSql(col.type)`.

**Fix-Skizze:** Domain-`baseType` durch denselben Mapper schicken wie Spalten/
Composite-Felder (`typeMapper.toSql(...)`). Dafür muss der gespeicherte
`baseType` (String) in einen `NeutralType` aufgelöst werden — prüfen, ob das
Domain-Modell schon einen `NeutralType` trägt oder ob die Reverse-Seite nur den
Rohstring speichert (dann dort den neutralen Typ + Präzision strukturiert
ablegen). Präzision/Scale danach nur anhängen, wenn der Zieltyp sie zulässt
(der Mapper erledigt das ohnehin).

**Test:** DDL-Generator-Unit-Test (Domain mit `biginteger`/`decimal` →
erwartetes `BIGINT` / `NUMERIC(p,s)`). **Modul:**
`:adapters:driven:driver-postgresql`. Hängt eng mit I-06 zusammen (gleicher
Renderpfad) → zusammen angehen.

---

## I-06 — Domain-`check` doppelt gewrappt (P2) — BEHOBEN 2026-06-17

> **Fix:** `b9e6ab38` (zusammen mit I-05). Reverse-Normalisierung
> (`normalizeDomainCheck` in `PostgresSchemaStructureReaders.kt`) entfernt das
> führende `CHECK`-Token + genau die umschließende äußere Klammer, sodass das
> Modell nur das Prädikat hält und generate exakt einmal wrappt (idempotent).
> Unit- + Live-PG-Round-Trip-Test (echter `pg_get_constraintdef`-Output) grün.

**Symptom:** `CHECK (CHECK (((VALUE …))))` — reverse speichert den Check inkl.
Wrapper, generate wrappt erneut → ungültiges DDL.

**Ursache (lokalisiert):** Generate-Seite gleiche Datei wie I-05
(`PostgresTypeSequenceDdlSupport.kt`, Domain-Zweig): `append(" CHECK
(${typeDef.check})")` wrappt unbedingt. Reverse-Seite: der Check wird inkl.
`CHECK (...)`-Hülle aus dem Katalog gelesen (Domain-Metadaten-Pfad, vgl.
`adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresTypeMetadataQueries.kt`).

**Fix-Skizze:** Normalisieren — vorzugsweise **auf der Reverse-Seite** den
führenden `CHECK`-Token + die äußeren Klammern entfernen, sodass das Modell nur
das Prädikat (`VALUE > 0`) hält; generate wrappt dann genau einmal. Alternativ
defensiv in generate vor dem Wrappen entwrappen. Reverse-Normalisierung
bevorzugen (ein Speicherformat, idempotent über Round-Trips).

**Test:** Round-Trip-Unit-Test (Check `VALUE > 0` → reverse → generate → genau
ein `CHECK (...)`). **Modul:** `:adapters:driven:driver-postgresql`.

---

## I-07 — Partitionierte Tabelle → ungültiges MySQL-DDL (P2)

**Symptom:** `PARTITION BY RANGE (col)` mit **leerer** Partitionsliste, plus
Platzierung vor `ENGINE`; zusätzlich AUTO_INCREMENT nicht führend in der
erzwungenen Composite-PK (ERROR 1075).

**Ursache (lokalisiert):**
`adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlIndexPartitionDdlHelper.kt`,
`generatePartitionClause`: bei `partitioning.partitions.isEmpty()` wird nur
`PARTITION BY RANGE (key)` ohne Partitionsdefinitionen emittiert — MySQL
verlangt für RANGE/LIST mindestens eine Partition. Einbindung +
Klausel-Reihenfolge:
`adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlDdlGenerator.kt`
(um Zeile 118: Partition-Klausel relativ zu `ENGINE` prüfen). Die
AUTO_INCREMENT-in-PK-Reihenfolge ist ein separater Teil-Defekt im PK-Rendering
(MySQL verlangt die AUTO_INCREMENT-Spalte als führende Key-Spalte).

**Fix-Skizze:** (a) Leere Partitionsliste → `E055`/Skip mit Note **oder** valides
DDL (mindestens eine Default-Partition) erzeugen — kein nacktes `PARTITION BY`.
(b) Klausel-Reihenfolge gegen MySQL-Grammatik prüfen (Partition nach
`ENGINE`/Tabellenoptionen). (c) Composite-PK so ordnen, dass die
AUTO_INCREMENT-Spalte führt, oder mit Note aussteigen. Teil (a)+(b) zuerst (das
ist der eigentliche Blocker), (c) ggf. als eigener Schritt.

**Test:** DDL-Generator-Unit-Tests (RANGE ohne Partitionen → Skip/Note; mit
Partitionen → valide Reihenfolge). **Modul:** `:adapters:driven:driver-mysql`.

---

## I-08 — Index auf typ-inkompatibler Spalte → ungültiges DDL ohne Sekundärwarnung (P2)

**Symptom:** MySQL: Index auf unbounded `TEXT` ohne Präfixlänge (ERROR 1170).
PG: GIST-Index auf `tsvector`→text-degradierter Spalte (kein Operator-Class).
Nur die Typ-Warnung (R301), kein Index-Hinweis.

**Ursache (lokalisiert):**
- MySQL: `MysqlIndexPartitionDdlHelper.kt`, `generateIndex` →
  `renderIndexColumn`. GIN/GIST/BRIN werden via `W102` geskippt, aber ein
  BTREE-Index auf einer unbounded `TEXT`/`BLOB`-Spalte erhält keine Präfixlänge
  → ERROR 1170.
- PG: `adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresDiffSqlBuilders.kt`
  (um Zeile 79: `USING ${idx.type.name}`). Wurde die Spalte auf `text`
  degradiert (z. B. `tsvector`→text), fehlt die für GIST nötige Operator-Class.

**Fix-Skizze:** MySQL: TEXT/BLOB-Index-Spalten ohne Schlüssellänge erkennen und
entweder Präfixlänge ergänzen oder mit Index-Note (z. B. `E0xx`/`W1xx`) skippen.
PG: wenn der Index-Typ eine Operator-Class verlangt, die die (degradierte)
Spalte nicht hat, Sekundär-Note ausgeben statt invaliden `USING gist`-DDL.

**Test:** DDL-Generator-Unit-Tests je Dialekt (TEXT-Index → Präfix/Skip+Note;
GIST auf text-degradiert → Skip/Note). **Module:**
`:adapters:driven:driver-mysql`, `:adapters:driven:driver-postgresql`.

---

## I-09 — View-Bodies als rohes Quell-Dialekt-SQL ins Ziel-DDL (P2)

**Symptom:** MySQL→PG: Backticks + `schema.tabelle` + `group_concat` →
`syntax error at or near "."`. `W111` warnt, aber das DDL ist nicht parsebar
(sollte `E053`/Skip sein).

**Ursache (lokalisiert):** View-Query wird beim Generieren verbatim
übernommen:
- MySQL-Ziel: `adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlDiffSqlBuilders.kt`
  (um Zeile 123: `CREATE VIEW … AS ${v.query?.trimEnd(';')}`).
- PG-Ziel: `adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresDiffOtherOps.kt`
  (CREATE-VIEW-Pfad) bzw.
  `adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresRoutineDdlHelper.kt`
  und `PostgresDiffMaterializedViewOps.kt` (Materialized-View). Der Body wird
  ohne Dialekt-Übersetzung weitergereicht.

**Fix-Skizze:** Entscheidung Scope-Frage für morgen — wir übersetzen
View-Bodies nicht zwischen Dialekten (kein SQL-Transpiler in 0.9.9). Daher: nicht
trivial portierbare View-Bodies (Fremd-Quoting, qualifizierte Namen,
dialektspezifische Funktionen wie `group_concat`) als **`E053`/Skip** mit klarer
Note behandeln statt invaliden DDL zu emittieren. Heuristik zur Erkennung
„nicht portierbar" festlegen (konservativ: alles außer simpel parsebaren
SELECTs skippen). Ggf. eigener ADR, falls das ein dauerhaftes Nicht-Ziel wird.

**Test:** Generator-Unit-Tests (Cross-Dialect-View mit Backticks/`group_concat`
→ Skip+`E053`, kein invalides DDL). **Module:** `:adapters:driven:driver-mysql`,
`:adapters:driven:driver-postgresql`.

---

## I-10 — Parquet-Import scheitert an Timestamp-Spalten (P2)

**Symptom:** `data import --format parquet` → Exit 3 „column 'created_at'
expects TIMESTAMP, got Instant". Blockiert Timestamp-Tabellen.

**Ursache (lokalisiert):**
`adapters/driven/formats/src/main/kotlin/dev/dmigrate/format/data/converters/TypeConverters.kt`
(um Zeile 179–186): der TIMESTAMP-Konverter (`LocalDateTimeConverter`, registriert
in `TypeConverterRegistry.kt:48` für `Types.TIMESTAMP`) akzeptiert
`LocalDateTime`/String, aber nicht `Instant`. Parquet liefert Zeitstempel als
`Instant` (INT64 µs UTC) → Typfehler. Der Serializer kennt `Instant` bereits
(`ValueSerializer.kt:78/110`), die Import-Konvertierung nicht.

**Fix-Skizze:** Im TIMESTAMP-Konverter `Instant` als Eingabe akzeptieren und nach
`LocalDateTime` (UTC) umwandeln; analog für `TIMESTAMP WITH TIME ZONE` (Zeile
~196, `OffsetDateTimeConverter`) `Instant` → `OffsetDateTime` (UTC). Auf die
bestehende TZ-Semantik achten (`ValueDeserializer.kt:82`: TIMESTAMP ohne Offset
behandelt Offset-Input als Typfehler — `Instant` ist UTC-normalisiert, daher als
LocalDateTime@UTC zulässig).

**Test:** Konverter-Unit-Test (`Instant` → TIMESTAMP/`LocalDateTime`); möglichst
ein Parquet-Round-Trip-Test (Export→Import einer Timestamp-Spalte). **Modul:**
`:adapters:driven:formats`.

---

## Vorgeschlagene Reihenfolge

1. ~~**I-05 + I-06** (Domain-Renderpfad, gleiche Datei)~~ — ✅ behoben 2026-06-17 (`b9e6ab38`).
2. **I-10** (Parquet-Timestamp) — isoliert, klar abgegrenzt, schneller Win. ← **nächster**
3. **I-07** (Partition MySQL) — Skip/valide-DDL-Entscheidung + PK-Reihenfolge.
4. **I-08** (Index TEXT-Präfix / GIST) — zwei Dialekte, je Sekundär-Note.
5. **I-09** (View-Bodies) — Scope-Entscheidung (Skip vs. Übersetzung) zuerst
   klären; potentiell ADR-relevant.

## Verifikations-Rezept (pro Fix)

```
make docker-test MODULES=":adapters:driven:driver-<dialekt>"   # bzw. :adapters:driven:formats
# Output nach /tmp/build.log umleiten, dann greppen (tail schneidet Fehler ab)
```

Bei DB-Verhalten zusätzlich Live-Test wie bei I-04:
`make integration INTEGRATION_TASKS="-PintegrationTests :test:integration-<dialekt>:test --tests *<Suite>*"`.
