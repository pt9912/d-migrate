# Tracker: macOS-Native-Leg ist auch mit Drosselung marginal

> **Status:** Befund mit vollstaendiger Messreihe (2026-08-15) — Wege 1 und 2 widerlegt,
> Entscheidung zwischen groesserem Runner und Verzicht steht aus
> **Trigger:** Das `macos-latest`-Leg von
> [`native-image.yml`](../../../.github/workflows/native-image.yml) fällt wiederholt
> aus, obwohl die dafür eingebaute Drosselung aktiv ist. Es blockiert kein Release
> (best-effort, Hybrid-Gate — nur `linux-x64` ist Pflicht), kostet aber bei jedem
> Release das `macos-arm64`-Asset und einen Rerun-Zyklus.
> **Aktivierungsbedingung** (Move nach `../next/`): Entscheidung für einen der drei
> Wege unten. Der dritte ist eine Streichung und gehört dann in einen ADR.

## Mechanik

Der `native-image`-Builder erstickt auf dem macOS-Runner im GC. Die Maschine hat
**3 Kerne und ~7,5 GB**; der Builder bekommt davon 5,35 GB (`MaxRAMPercentage=80.0`)
und läuft mit `--parallelism=2` — beides bereits Gegenmaßnahmen, keine Defaults.

Der Mehrbedarf kam mit **GraalVM 25**: derselbe Bau lief unter GraalVM 21.0.2 noch
mit 31,9 % GC in 26m20s durch. Belege stehen als Kommentar an den beiden Stellen, die
die Schrauben setzen ([`native-image.yml`](../../../.github/workflows/native-image.yml)
für `--parallelism`, [`build.gradle.kts`](../../../adapters/driving/cli/build.gradle.kts)
für `MaxRAMPercentage`).

## Messreihe

| Lauf | Konfiguration | Ergebnis |
| --- | --- | --- |
| `29727572204` | GraalVM 25, `MaxRAMPercentage=60` | 26m20s, **45,9 % GC**, Peak RSS 2,21 GB |
| `29741017853` | GraalVM 25, Default-Parallelismus | **Exit 30 nach 42 min**, 77,5 % GC |
| `29747084840` (2026-07-20) | nach Drosselung | rot |
| `31299091580` (RC3-Tag, Versuch 1) | 80 % + `parallelism=2` | **Exit 30 nach 30m51s** |
| `31299091580` (RC3-Tag, Rerun) | identisch | grün |
| `31319673441` (2026-08-09) | identisch, neue Toolchain | nach 60 min in `Native compile` abgebrochen — kein Urteil |

**Exit 30 ist keine Runner-Panne, sondern die Signatur des erstickenden Builders.**
Das ist die wichtigste Einordnung: der Fehler sieht nach Infrastruktur aus, ist aber
ein Ressourcenproblem mit bekannter Ursache.

## Was der Befund NICHT ist

**Keine Folge des Kotlin-2.4.10-Bumps.** Der RC3-Tag wurde mit **Kotlin 2.1.20**
gebaut — der Bump kam danach — und genau dort fiel macOS mit Exit 30. Die Fragilität
ist älter als die neue Toolchain.

**Kein Laufzeit-Ausreißer.** Ein Vergleich gegen „historisch 26 min" trägt nicht: der
Wert stammt von GraalVM 21.0.2 und aus der Zeit **vor** der Drosselung.
`--parallelism=2` kauft Speicher mit Zeit; lange Läufe sind dort der Normalfall.

## Messreihe 2026-08-15: alle drei Maschinen-Schrauben durchprobiert

Die Wege 1 und 2 der ursprünglichen Fassung sind **gemessen und widerlegt**. Dafür
wurden `max_ram_percentage`, `parallelism` und `gradle_heap` als
`workflow_dispatch`-Inputs verfügbar gemacht (`503036f5`, `318b97cb`); Tag-Läufe
haben keine Inputs und bleiben unverändert.

| Lauf | Budget | Threads | Ergebnis |
| --- | --- | --- | --- |
| `31870585218` `parallelism=1` | 5,35 GB | 1 | **Exit 30** nach 27 min |
| `31872022614` `gradle_heap=2g` | 5,35 GB | 2 | **Exit 30** nach 27 min |
| `31873419273` `90 %` + `gradle_heap=2g` | **6,02 GB** | 2 | **Exit 30** nach 25 min |

**Was jeder Versuch ausschließt:**

- **Nebenläufigkeit ist nicht der Engpass.** Bei einem einzigen Compiler-Thread ist die
  gleichzeitig gehaltene Arbeitsmenge minimal — es erstickt trotzdem. Der Engpass ist
  die *Grundlast* des Analyse-Universums, nicht deren Aufteilung.
- **Die Gradle-JVM nimmt dem Builder nichts weg.** Zwei Gigabyte weniger
  Gradle-Anspruch ließen das Builder-Budget auf die Nachkommastelle **unverändert**:
  `MaxRAMPercentage` rechnet gegen den physischen Speicher, nicht gegen den freien.
  Die Überzeichnung (Builder + Gradle > Maschine) existiert real, steuert aber das
  Budget nicht.
- **Mehr Prozent bringt nichts.** +0,67 GB (12,5 % mehr Budget) bewegten den Ausgang
  nicht. Die Lücke ist größer als das, was diese Maschine hergeben kann.

**Nebenbefund zur Arithmetik:** 80 % ergaben 5,35 GB = 71,1 % des gemeldeten
Systemspeichers, 90 % ergaben 6,02 GB = 80,1 %. Die JVM sieht also ~6,7 GB als
physisch an, das Tool meldet 7,52 GB — kein Fehler, zwei Bezugsgrößen.

**Zweiter Fehlermodus, vorher fehlinterpretiert:** Die Läufe vom 2026-08-09 (Dispatch
und RC4-Tag) endeten bei **exakt 60 Minuten** und erschienen als `cancelled`. Das war
kein Handabbruch, sondern `timeout-minutes: 60` des Jobs — GitHub meldet einen
Job-Timeout als `cancelled`, nicht als `failure`.

## Wege (revidiert)

1. ~~Speicherbudget anheben~~ — **widerlegt**, s. o.
2. ~~`--parallelism=1`~~ — **widerlegt**, s. o.
3. **Größerer macOS-Runner.** GraalVM Native Image **cross-kompiliert nicht** — das
   `macos-arm64`-Binary kann ausschließlich auf einem macOS-Runner entstehen, die
   großzügige Linux-Maschine ist keine Alternative. GitHub bietet größere
   macOS-Runner an (mehr Kerne und RAM), kostenpflichtig und mit höherem
   Minuten-Multiplikator. Der einzige Weg, der das Problem *löst* statt es zu
   verschieben. Größen und Kosten sind vor einer Entscheidung nachzusehen.
4. **`macos-arm64` nicht mehr ausliefern.** Ehrlich und sofort wirksam: kein Würfeln je
   Release, kein Rerun-Ritual. Kostet Nutzern das native macOS-Binary; JVM-Artefakte,
   Container-Image und Homebrew bleiben. Eine Streichung gehört nach der Regel aus
   [ADR 0039](../../adr/0039-externer-security-audit-kein-1.0.0-gate.md) in einen ADR.

## Warum nicht „einfach weiter rerunnen"

Der Rerun funktioniert (RC3 belegt es), aber er ist Handarbeit an einer Stelle, die
sonst automatisch läuft, und er verdeckt die Frage, ob das Leg überhaupt tragfähig
ist. Zwei von drei scharfen Läufen brauchten einen zweiten Versuch.
