# Tracker: macOS-Native-Leg ist auch mit Drosselung marginal

> **Status:** Befund mit Messreihe (Draft) / Trigger Watch (2026-08-09)
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

## Wege

1. **Speicherbudget weiter anheben** (`nativeMaxRamPercentage` über 80 %). Billig zu
   probieren, aber der Kopfraum ist dünn: bei 5,35 GB von 7,5 GB bleibt wenig für
   alles andere, und ein OOM-Kill des Runners ersetzt Exit 30 nur durch einen
   härteren Abbruch.
2. **`--parallelism=1`.** Weniger gleichzeitig gehaltene Arbeitsmengen, also mehr
   Kopfraum — auf Kosten der Laufzeit, die schon jetzt an der Geduldsgrenze liegt.
3. **macOS als Native-Plattform streichen.** Ehrlichste Option, wenn 1 und 2 nicht
   verlässlich tragen: kein Würfeln je Release, kein Rerun-Ritual. Kostet Nutzern das
   `macos-arm64`-Binary; JVM-Artefakte und Container-Image bleiben. Eine Streichung
   gehört nach [ADR 0039](../../adr/0039-externer-security-audit-kein-1.0.0-gate.md)s
   Regel in einen ADR, nicht in eine gelöschte Zeile.

## Warum nicht „einfach weiter rerunnen"

Der Rerun funktioniert (RC3 belegt es), aber er ist Handarbeit an einer Stelle, die
sonst automatisch läuft, und er verdeckt die Frage, ob das Leg überhaupt tragfähig
ist. Zwei von drei scharfen Läufen brauchten einen zweiten Versuch.
