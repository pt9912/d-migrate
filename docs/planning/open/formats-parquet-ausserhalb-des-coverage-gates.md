---
id: formats-parquet-ausserhalb-des-coverage-gates
title: "`formats-parquet` liegt ausserhalb des Coverage-Gates — von keiner Seite gedeckt"
status: open
---

# `formats-parquet` liegt ausserhalb des Coverage-Gates

## Befund (gemessen 2026-09-13 beim Release-Abgleich)

Das Gate wirkt auf zwei Wegen: jedes Modul traegt in seiner `build.gradle.kts`
eine eigene Regel (`kover { reports { verify { rule { minBound(90) } } } }`),
und der Wurzelbau aggregiert die Module fuer den Gesamtbericht
(`kover(project(":…"))`). `adapters/driven/formats-parquet` hat **keines von
beidem**:

| Modul | eigene 90-%-Regel | im Wurzel-Aggregat | im Excludes-Ledger |
| --- | --- | --- | --- |
| `:adapters:driven:formats-parquet` | **nein** | **nein** | **nein** |
| `:adapters:driven:connection-config` | ja | nein | — |
| `:adapters:driven:integrations` | ja | nein | — |
| `:adapters:driven:persistence-memory` | ja | nein | — |
| `:adapters:driven:storage-s3` | ja | nein | — |
| `:test:cross-dialect-matrix`, `:test:integration-concurrency`, `:test:perf-large-schema` | — | nein | **ja**, mit Begruendung |

Die vier anderen nicht-aggregierten Module sind also **gedeckt** (ihre eigene
Regel greift, nur der Gesamtbericht fuehrt sie nicht), und die drei
Testlauf-Module sind als `aggregate-carveout:` dokumentiert. `formats-parquet`
ist der einzige Fall, in dem Produktivcode von **keiner** Seite gedeckt und
nirgends als Ausnahme vermerkt ist: 22 Produktivdateien, 13 Testdateien.

## Warum es niemandem aufgefallen ist

`make ci-build` fuehrt `build koverVerify` ueber das ganze Repo — ein Modul ohne
Regel meldet dabei nichts, und der Gesamtbericht kennt es nicht. Es gibt also
keinen Lauf, der rot wuerde. Der Abgleich in
[`releasing.md`](../../user/releasing.md) stellt die richtige Frage („deckt
`koverVerify` weiterhin alle aktuellen JVM-Module ab?"), aber sie wird gelesen,
nicht ausgefuehrt.

## Was der Schnitt klaeren muss

- **Wie hoch die Deckung heute ist.** Ohne Regel gibt es keine Zahl; sie ist zu
  messen, bevor eine Latte gesetzt wird (13 Testdateien deuten auf gepflegte,
  nicht auf fehlende Tests).
- **Ob 90 % erreichbar sind oder ein dokumentierter Ausschluss faellig ist.**
  Der Adapter haengt an Hadoop-Klassen, die sich schwer ohne echte Dateien
  ansprechen lassen — das waere ein Ledger-Eintrag mit Begruendung, kein
  stilles Weglassen. Die bestehende Regel des Repos ist dabei eindeutig: echte
  Aufteilung statt Ausrede.
- **Ob der Abgleich mechanisierbar ist.** Ein Skript, das Module mit
  Produktivquellen gegen Regel-und-Aggregat stellt (genau die Tabelle oben),
  waere ein Gate statt einer Frage — und haette diesen Fall beim Entstehen
  gefunden.

## Herkunft

Aufgefallen beim Release-Abgleich fuer 1.4.0, §3.5 der Checkliste. Kein
Release-Blocker: der Zustand ist so alt wie der Parquet-Adapter und durch
dieses Release unveraendert.
