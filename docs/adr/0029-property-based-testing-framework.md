---
status: accepted
date: 2026-07-10
decision-makers: pt9912
consulted: spec/lastenheft-d-migrate.md, docs/planning/next/property-based-testing-ln046.md
informed: build.gradle.kts, docs/planning/in-progress/roadmap.md, hexagon/core
---

# Property-Based-Testing-Framework: kotest-property statt Jqwik

> **Status: accepted (2026-07-10).** Property-Based Testing wird mit
> **kotest-property** umgesetzt, nicht mit Jqwik. kotest-property ist
> stack-nativ (gleicher JUnit5-Runner, gleiches Spec-Idiom wie die bestehenden
> Kotest-Tests), liefert Generatoren **und** Shrinking und kommt ohne ein
> zweites Test-Framework aus.

## Kontext und Problemstellung

Der Lastenheft-Punkt [`LN-046`](../../spec/lastenheft-d-migrate.md#ln-046)
verlangt Property-Based Testing „für Robustheit" mit **automatischer
Testfall-Generierung für Schema-Parsing** und **Shrinking bei Testfehlern zur
Ursachenfindung". Als Beispiele nennt der Lastenheft *Hypothesis (Python)* und
*fast-check (JavaScript)* — also sprach-übergreifende Referenzen aus dem
jeweiligen Ökosystem, **kein** verbindliches JVM-Werkzeug.

Die Roadmap hat diesen Punkt bislang mit „Jqwik" konkretisiert (dem bekanntesten
eigenständigen PBT-Framework auf der JVM). Damit steht die Roadmap-Formulierung
im Konflikt mit dem tatsächlichen Test-Stack des Projekts:

- Der gesamte Testbestand ist **Kotest** auf der JUnit5-Platform
  (`kotest-runner-junit5` + `kotest-assertions-core`, Version `kotestVersion`
  in `build.gradle.kts`).
- Ein PBT-Präzedenzfall existiert nur als **manuell aufgezählte** Matrix, weil
  „the repo does not depend on kotest-property" (`hexagon/ports-execute/src/test/kotlin/dev/dmigrate/driver/migration/preserve/ExecutableSegmentsTest.kt`)
  — d. h. der Bedarf ist real, das Werkzeug fehlte nur.

## Entscheidung

Property-Based Testing wird mit **`io.kotest:kotest-property`** (gleiche Version
wie der übrige Kotest-Stack) umgesetzt. Property-Tests werden direkt in bestehenden
Kotest-Specs (`FunSpec`/`StringSpec`) über `checkAll(Arb.…) { … }` geschrieben.

Die Roadmap-Formulierung „Jqwik" wird auf „kotest-property (ADR 0029)"
angepasst. Der Lastenheft bleibt unverändert, weil er ohnehin kein konkretes
JVM-Werkzeug vorschreibt — kotest-property erfüllt beide harten Anforderungen
(Generatoren für Schema-Parsing, Shrinking).

## Betrachtete Optionen

- **kotest-property** (gewählt)
- **Jqwik** (Roadmap-Wortlaut)

### kotest-property

- **Ein** Test-Framework, **ein** Runner, **ein** Idiom: keine zweite
  Test-Sprache neben Kotest; der Kotest-Tag-Filter (`kotest.tags`, u. a. das
  `!perf`-Default) und die bestehende Gradle-/Kover-Verdrahtung greifen ohne
  Zusatzarbeit.
- Generatoren (`Arb<…>`) plus **eingebautes Shrinking** — erfüllt die geforderte
  Shrinking-Fähigkeit.
- Ein neuer `Arb<NeutralType>`/`Arb<SchemaDefinition>`-Baukasten ist in den
  bestehenden Specs direkt wiederverwendbar (Round-Trip, Idempotenz,
  Ordnungs-Unabhängigkeit).
- Kosten: Version an `kotestVersion` gebunden (bei Kotest-Upgrades mitziehen).

### Jqwik

- Mächtigere, ausgereifte PBT-Engine (reicheres Shrinking, Statistik-Reports).
- Aber: **zweites** Test-Idiom (JUnit5 `@Property`/`@ForAll`) parallel zu Kotest;
  der Kotest-Tag-Filter greift für Jqwik-Tests **nicht**, Generatoren sind nicht
  mit Kotest-`Arb` teilbar, mehr Integrations- und Wartungsreibung.
- Der einzige Vorteil (Engine-Reife) wiegt die Ziele dieses Projekts
  (reine Model-/Codec-/Parser-Invarianten) nicht auf.

## Konsequenzen

- **Gut**: minimale Reibung, sofort produktiv, ein konsistenter Test-Stack;
  wiederverwendbare Generatoren beschleunigen die Folge-Phasen (TypeMapper,
  Kanonisierer, Fingerprint, YAML-Parser-Round-Trip).
- **Schlecht/Risiko**: kotest-property ist an die Kotest-Version gekoppelt; sehr
  aufwändige Shrinking-/Statistik-Szenarien, die Jqwik out-of-the-box böte,
  müssten bei Bedarf selbst modelliert werden. Für die Ziele dieses Slices nicht
  relevant.
- **Abweichung dokumentiert**: Roadmap-Wortlaut „Jqwik" → „kotest-property"; der
  Slice `docs/planning/next/property-based-testing-ln046.md` führt Ziele, Phasen
  und Definition of Done.
