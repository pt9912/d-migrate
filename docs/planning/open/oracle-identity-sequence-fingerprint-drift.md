---
id: oracle-identity-sequence-fingerprint-drift
title: "Postcompare-Fingerprint faelscht Drift bei jeder Oracle-IDENTITY-Spalte (system-generierter Sequenzname)"
status: open
---

# Postcompare-Fingerprint faelscht Drift bei jeder Oracle-IDENTITY-Spalte

## Befund

`SchemaMigrateExecutionStage.runPostCompare` vergleicht nach einem
`--execute`-Lauf den Fingerprint des **user-authored** `desired`-Schemas
gegen den Fingerprint des frisch reverse-gelesenen `observed`-Schemas
(`SchemaMigrateExecutionStage.kt:174-176`). Der Fingerprint zieht dafür
`FingerprintValueProjection.generation(col.generation)`
(`FingerprintValueProjection.kt:86-93`) heran — eine reine,
dialektunabhängige Funktion, die `ColumnGeneration.Identity.sequenceName`
**verbatim** einbettet, wenn er nicht `null` ist.

Für Oracle ist das immer der Fall, sobald eine `IDENTITY`-Spalte neu
angelegt wird: `OracleTypeMapping.mapIdentity`
(`OracleTypeMapping.kt:52-54`) liest den echten, aber
**system-generierten** Sequenznamen (`ISEQ$$_n`, real gegen den
Testcontainer verifiziert) aus dem Katalog und trägt ihn unverändert ins
neutrale Modell — eine bewusste, getestete Slice-1-Entscheidung
(`OracleSchemaReaderTest.kt:125`, `OracleTypeMappingTest.kt:123`), weil sie
für `schema reverse`/`schema compare`-Ausgaben echten Informationswert hat
(z. B. für manuelle Grants/Monitoring auf der Sequenz).

Ein Anwender, der ein `schema.json` für eine NEUE Tabelle mit
Identity-Spalte verfasst, kann diesen Namen nicht vorher kennen — er
entsteht erst beim `CREATE TABLE`. Das `desired`-Schema trägt deshalb
zwangsläufig `generation = Identity(mode=ALWAYS, sequenceName=null)`,
während das `observed`-Schema nach der Ausführung
`sequenceName="ISEQ$$_73345"` (oder eine andere laufende Nummer) trägt —
**der Fingerprint-Vergleich schlägt fehl, obwohl die Migration exakt wie
gewünscht angewendet wurde.**

Live reproduziert beim Bau von
`OraclePostCompareFingerprintIntegrationTest` (Oracle Slice 4a,
`test/integration-oracle`): eine `Identifier(autoIncrement=true)`-PK-Spalte
im Typ-Probe erzeugte exakt dieses Muster
(`schema-fingerprint-v9`-Diff nur in der `id`-Spalte, Feld `generation`).
Der Test wurde deshalb auf eine PLAIN PK (`autoIncrement=false`, keine
IDENTITY-Klausel, kein Sequenzname) umgestellt, um die TYP-Projektion isoliert
zu belegen — das umgeht den Befund, löst ihn aber nicht.

## Warum nicht in Slice 4a gelöst

Die Ursache liegt nicht in der [`NeutralTypeCanonicalizer`][nc]-Substanz
dieses Slices (der reine `(NeutralType) -> NeutralType`-Hook kann
`ColumnGeneration` gar nicht sehen), sondern im generischen
`MigrationFingerprint`/`SchemaMigrateExecutionStage`-Vertrag selbst, der
für `ColumnGeneration` **keinen** Kanonisierungs-Hook kennt (nur
`canonicalizeType`/`canonicalizeIndex`). Ein Fix müsste diesen Vertrag um
eine dritte Achse erweitern — eine Änderung an geteiltem Hexagon-Kern-Code,
die alle fünf Dialekte betrifft, kein Oracle-lokaler Slice-4a-Fix.

Zudem ist der Pfad heute **nicht erreichbar**: `DialectCommandGate` blockt
`schema migrate` für Oracle bis Slice 5 (`OracleDiff*Ops` noch nicht
gebaut) — der Befund ist real, aber aktuell dormant.

[nc]: ../../../hexagon/ports-common/src/main/kotlin/dev/dmigrate/driver/NeutralTypeCanonicalizer.kt

## Mögliche Lösungsrichtungen (für Slice 5, nicht vorentschieden)

1. `MigrationFingerprint`/`FingerprintValueProjection` um einen
   `canonicalizeGeneration: (ColumnGeneration?) -> ColumnGeneration?`-Hook
   erweitern (analog `canonicalizeType`), den `SchemaMigrateExecutionStage`
   zielsystemabhängig füllt.
2. Dialektspezifisch: Oracle liefert einen Hook, der `sequenceName` aus dem
   Vergleich herausprojiziert (analog dazu, dass MSSQL ihn nie setzt — dort
   tritt der Fehler strukturell nicht auf).
3. Am generischen Default (`{ it }`) NICHTS ändern, aber
   `SchemaMigrateExecutionStage` für Oracle explizit eine
   generation-blinde Vergleichsvariante nutzen.

Betrifft potenziell auch PostgreSQL, sobald dort eine **frisch angelegte**
(nicht bereits reverse-gelesene) `IDENTITY`-Spalte denselben Weg durchläuft
— dort ist der Sequenzname aber deterministisch (`<table>_<col>_seq`), das
Risiko ist ungeprüft, aber kleiner.
