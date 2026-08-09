---
status: accepted
date: 2026-08-09
decision-makers: pt9912
consulted: build.gradle.kts, docs/planning/in-progress/roadmap.md
informed: docs/adr/0036-library-artefakte-github-packages.md, docs/adr/0037-database-agnostic-first-staffelung.md
---

# Interface-Default-Methoden ohne `$DefaultImpls`-Brücke (`-jvm-default=no-compatibility`)

> **Status: accepted (2026-08-09).** Der Kotlin-Compiler erzeugt für Interfaces mit
> Default-Membern **keine** `$DefaultImpls`-Kompatibilitätsklassen mehr. Ausgelöst vom
> Kotlin-Bump 2.1.20 → 2.4.10, der die Coverage-Gates ohne eine einzige Codeänderung
> reißen ließ.

## Kontext und Problemstellung

Bis Kotlin 2.1.20 landeten die Rümpfe von Interface-Default-Membern ausschließlich in
einer generierten `$DefaultImpls`-Klasse; das Interface selbst trug keinen ausführbaren
Code. Ab Kotlin 2.2 ist der Default anders: die Default-Methode wird als **echte
JVM-Default-Methode im Interface** erzeugt — und `$DefaultImpls` **zusätzlich**
beibehalten, als Brücke für Klassen, die gegen die alte Form kompiliert wurden.

Zur Laufzeit ruft niemand mehr die Brücke auf. Für die Coverage heißt das: der Zähler
bleibt gleich, der **Nenner wächst**. Gemessen an `hexagon:ports` (identische Quelldatei,
nur andere Kotlin-Version):

| | `DatabaseDriver` | `$DefaultImpls` | Summe |
| --- | --- | --- | --- |
| Kotlin 2.1.20 | — | 3/4 | **13/14 = 92,86 %** |
| Kotlin 2.4.10 | 3/4 | **0/4** | **13/18 = 72,22 %** |

Das 90-%-Gate riss damit an einem Modul, an dem sich kein Zeichen geändert hatte. Der
CI-Lauf brach nach 86 von 450 Tasks ab — `hexagon:ports` ist nur das erste Modul im
Graphen; betroffen ist jedes Interface mit Default-Membern.

## Entscheidungstreiber

- **Die Brücke schützt einen Vertrag, den es nicht gibt.** `$DefaultImpls` existiert für
  Binärkompatibilität mit *extern* kompilierten Implementierern. d-migrate publiziert vor
  2.0.0 **keine** Library-Artefakte ([ADR 0036](0036-library-artefakte-github-packages.md),
  [ADR 0037](0037-database-agnostic-first-staffelung.md)) — alle Implementierer liegen im
  selben Build und werden mit denselben Optionen übersetzt.
- **Tote Zeilen dürfen das Gate nicht verwässern.** Ein Kover-Exclude auf `*$DefaultImpls`
  würde die Zahl reparieren, aber eine Klassenfamilie dauerhaft aus der Messung nehmen —
  und zwar per Muster, das später auch echten Code treffen kann.
- **Das Gate soll Aussagekraft behalten.** Die Alternative „Schwelle senken" bezahlt einen
  Compiler-Artefakt mit dauerhaft niedrigerem Anspruch in allen Modulen.
- **Weniger Bytecode.** Ohne Brücke entfällt pro Interface eine Klasse.

## Betrachtete Optionen

1. **Compiler-Default belassen, `*$DefaultImpls` in die Kover-Excludes** aufnehmen.
2. **Coverage-Schwelle senken**, bis die toten Zeilen hineinpassen.
3. **Auf Kotlin 2.1.20 bleiben** und den Bump ablehnen.
4. **`-jvm-default=no-compatibility` setzen** — die Brücke gar nicht erst erzeugen (gewählt).

## Entscheidung

Gewählt: **Option 4.** Im `subprojects`-Block von
[`build.gradle.kts`](../../build.gradle.kts) wird
`freeCompilerArgs.add("-jvm-default=no-compatibility")` gesetzt. Damit erzeugt der Compiler
Default-Member ausschließlich als JVM-Default-Methoden im Interface.

Option 1 scheidet aus, weil ein Exclude die Ursache verdeckt statt sie zu beseitigen, und
weil ein Muster-Exclude auf eine ganze Klassenfamilie später echten Code mit ausblenden
kann. Option 2 bezahlt ein Compiler-Artefakt mit dauerhaft gesenktem Anspruch. Option 3
verschiebt das Problem nur auf den nächsten Bump.

**Nachgemessen** (`hexagon:ports`, Kotlin 2.4.10 mit Flag): **13/14 = 92,86 %** — exakt der
Wert von vor dem Bump. Das volle Gate (`build koverVerify --no-build-cache`) läuft mit
450 Tasks grün durch; kein weiteres Modul fällt unter die Schwelle.

## Konsequenzen

- **Positiv:** Das Coverage-Gate misst wieder ausführbaren Code statt generierter Brücken.
  Kein Exclude, keine gesenkte Schwelle. Weniger erzeugte Klassen.
- **Negativ:** Klassen, die **außerhalb** dieses Builds gegen ein d-migrate-Interface
  kompiliert wurden, würden bei einem Upgrade brechen. Das ist heute folgenlos, weil keine
  Library-Artefakte publiziert werden.
- **Wiedervorlage:** Mit dem Library-Publishing (Milestone 2.0.0) wird diese Entscheidung
  Teil des Binärkompatibilitäts-Vertrags und ist dort erneut zu prüfen — dann ist
  `no-compatibility` eine Zusage an Konsumenten, keine reine Innenangelegenheit mehr.
- **Abgrenzung:** Die Entscheidung betrifft die Codegenerierung, nicht die Sprachsemantik.
  An Kotlin-Quellcode ändert sich nichts.
