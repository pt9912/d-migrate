# `formats-parquet` liegt jetzt im Coverage-Gate — 77 % → 90,3 %

## Befund (gemessen 2026-09-13 beim Release-Abgleich)

Das Gate wirkt auf zwei Wegen: jedes Modul trägt in seiner `build.gradle.kts`
eine eigene Regel (`kover { reports { verify { rule { minBound(90) } } } }`),
und der Wurzelbau aggregiert die Module für den Gesamtbericht
(`kover(project(":…"))`). `adapters/driven/formats-parquet` hatte **keines von
beidem**: 22 Produktivdateien, 13 Testdateien, von keiner Seite gedeckt und
nirgends als Ausnahme vermerkt — der einzige Fall dieser Art im Repo.

## Was der Schnitt klären musste — und die Antworten

**Wie hoch die Deckung heute ist.** Gemessen mit einer temporären
`minBound(100)`-Regel (kover meldet bei jedem Fehlschlag die tatsächliche
Zahl): **77,2 %** Zeilen-Deckung. Der `koverHtmlReport` je Klasse zeigte,
wo — konzentriert in einer Handvoll Klassen, nicht flächig verteilt.

**Ob 90 % erreichbar sind.** Ja, ohne strukturellen Umbau. Die 13
bestehenden Testdateien deuteten schon vorab auf gepflegte, nicht auf
fehlende Tests hin — bestätigt: der Adapter hängt zwar an Hadoop-Klassen
(ADR 0046), aber die Lücke lag nicht dort. Sie lag in reinen
JVM-Koerzierungs- und Type-Dispatch-Funktionen (`ParquetGroupValueWriter`,
`ChunkSchemaToManifest`/`ManifestNeutralTypeToCore`, `ParquetMessageTypeTo
ChunkSchema`), deren **natürlicher** JDBC-Typ pro Spalte längst
round-trip-getestet war — nur die *anderen* Java-Typen, die ein JDBC-Treiber
für denselben Spaltentyp ebenso liefert (`Short`/`Byte` statt `Int`,
`java.sql.Date` statt `LocalDate`, ...), sowie die selteneren
`NeutralType`-Varianten (nur 4–6 von 21 liefen durch einen Manifest-Rundlauf)
waren nie geprüft. Kein Ausrede-Fall — echte Aufteilung war nicht nötig,
echte Tests genügten.

**Ob der Abgleich mechanisierbar ist.** Offen geblieben — kein eigenes
Skript gebaut. Die Tabelle aus dem ursprünglichen Befund (Modul × eigene
Regel × Wurzel-Aggregat × Ledger-Eintrag) bleibt eine manuelle Prüfung beim
nächsten Release-Abgleich, bis das jemand aufgreift.

## Zwei echte Befunde unterwegs

Das systematische Testen deckte zwei zuvor unbekannte, reproduzierbare
Defekte auf — nicht Nebeneffekte der Coverage-Arbeit, sondern ihr eigentlicher
Wert:

- **Eine `Time`-Spalte ließ sich nicht als Parquet schreiben.** `TIME(MICROS)`
  verlangt physisch `INT64`; der Code deklarierte `INT32` und scheiterte beim
  Schema-Bau — vor der ersten Zeile. Kein Randfall: Mikrosekunden seit
  Mitternacht überschreiten `Int.MAX_VALUE` ohnehin. Kein Test hatte je eine
  Time-Spalte durch den echten Schreibpfad geführt. Behoben in
  `ChunkSchemaToParquetMessageType`, `ParquetGroupValueWriter`,
  `ParquetGroupValueReader`, `ParquetMessageTypeToChunkSchema` (vier
  koordinierte Stellen, die laut eigener KDoc „zusammen geändert werden
  müssen" — die KDoc kannte das Risiko, der Fehler war trotzdem da).
- **Eine `FullText`-Spalte ließ sich in ein Bundle-Manifest schreiben, aber
  nicht mehr lesen.** Der Schreiber kannte `NeutralType.FullText`, der Leser
  nicht — Absturz beim nächsten Lesen des eigenen Manifests. Gefunden durch
  einen Rundlauf-Test über **alle** 21 `NeutralType`-Varianten statt der
  bis dahin üblichen vier bis sechs.

## Ergebnis

- `adapters/driven/formats-parquet/build.gradle.kts` trägt jetzt
  `kover { reports { verify { rule { minBound(90) } } } }`.
- Root-`build.gradle.kts` aggregiert das Modul (`kover(project(":adapters:
  driven:formats-parquet"))`).
- Gemessene Enddeckung: **90,3 %** Zeilen (Ausgangswert 77,2 %).
- Neue Tests: `ParquetSingleFileResolverTest` (zwei zuvor komplett
  ungetestete Klassen, 0 % → 100 %), `ParquetGroupValueWriterTest`
  (Koerzierungszweige), `ManifestNeutralTypeRoundTripTest` (alle 21
  `NeutralType`-Varianten durch den Manifest-Rundlauf), plus eine
  `Time`-Spalte im bestehenden `ParquetChunkRoundTripTest`.

## Herkunft

Aufgefallen beim Release-Abgleich für 1.4.0, §3.5 der Checkliste. Aufgegriffen
und geschlossen am 2026-09-13.
