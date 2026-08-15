---
status: accepted
date: 2026-08-15
decision-makers: pt9912
consulted: .github/workflows/native-image.yml, docs/planning/done/native-macos-build-marginal.md
informed: README.md, README.de.md, docs/user/releasing.md, docs/user/administrationshandbuch.md, packaging/dockerhub/overview.md, docs/planning/in-progress/roadmap.md
---

# Kein natives `macos-arm64`-Binary — GraalVM-Auslieferung auf Linux und Windows begrenzt

> **Status: accepted (2026-08-15).** Das `macos-latest`-Leg fällt aus der
> Native-Image-Matrix. Native Binaries erscheinen nur noch für `linux-x64` und
> `windows-x64`. Für macOS bleiben Homebrew, das JVM-Artefakt und das Container-Image.

## Kontext und Problemstellung

Der `native-image`-Builder erstickt auf dem GitHub-macOS-Runner reproduzierbar im GC
und endet mit `exit status 30`. Die Maschine hat **3 Kerne und ~7,5 GB**; der
Mehrbedarf kam mit GraalVM 25 (derselbe Bau lief unter 21.0.2 mit 31,9 % GC in
26m20s durch).

Entscheidend ist, dass es **keinen Ausweg über eine andere Maschine gibt**: GraalVM
Native Image **cross-kompiliert nicht**. Ein `macos-arm64`-Binary kann ausschließlich
auf einem macOS-Runner entstehen — die großzügige Linux-Maschine, die dasselbe Binary
für Linux in ~9 Minuten baut, ist keine Alternative.

Der Zustand war schon vor dieser Entscheidung ein Würfelspiel: Von den scharfen Läufen
brauchten zwei von drei einen zweiten Versuch, und bei identischer Konfiguration
schwankte die Laufzeit zwischen 31 und über 60 Minuten — ein metastabiler Bau, der
entweder knapp in den Heap passt oder in die GC-Spirale kippt.

## Entscheidungstreiber

- **Alle Stellschrauben der Maschine sind durchgemessen und wirkungslos** (Messreihe
  2026-08-15, drei Dispatch-Läufe):

  | Versuch | Builder-Budget | Ergebnis |
  | --- | --- | --- |
  | `--parallelism=1` | 5,35 GB | Exit 30 |
  | Gradle-JVM auf 2 GB gedeckelt | 5,35 GB (unverändert) | Exit 30 |
  | `MaxRAMPercentage=90` + Gradle-Deckel | 6,02 GB | Exit 30 |

  Bei **einem** Compiler-Thread ist die gleichzeitig gehaltene Arbeitsmenge minimal —
  es erstickt trotzdem. Der Engpass ist die Grundlast des Analyse-Universums, nicht
  deren Aufteilung, und +12,5 % Budget bewegen sie nicht.
- **Ein Würfelspiel im Release-Pfad ist teurer als ein fehlendes Artefakt.** Jeder
  Release kostete sonst einen Rerun-Zyklus mit ungewissem Ausgang — Handarbeit an der
  Stelle, die am verlässlichsten sein sollte.
- **macOS-Nutzer verlieren wenig.** Homebrew ist auf macOS der übliche Bezugsweg und
  bleibt vollständig unterstützt; dazu kommen das JVM-Artefakt (ZIP/TAR/Fat JAR) und
  das Container-Image. Verloren geht allein der Java-freie Schnellstart.
- **Ehrlichkeit.** Ein stillschweigend fehlendes Asset ist die schlechteste Variante:
  Der Release sähe unvollständig aus, ohne dass jemand die Ursache fände. Ein
  permanenter Ausschluss gehört in einen ADR (Regel aus
  [ADR 0039](0039-externer-security-audit-kein-1.0.0-gate.md)).

## Betrachtete Optionen

1. **Weiter rerunnen.** Funktioniert nachweislich, aber als Ritual bei jedem Release
   und mit ~50 % Trefferquote je Versuch.
2. **Größerer, kostenpflichtiger macOS-Runner.** Der einzige Weg, der das Problem
   löst statt es zu verschieben — erkauft mit laufenden Kosten und einem höheren
   Minuten-Multiplikator für ein Artefakt, das eine Minderheit nutzt.
3. **`macos-arm64` nicht mehr ausliefern** (gewählt).

## Entscheidung

Gewählt: **Option 3.** `macos-latest` entfällt aus der Matrix von
[`native-image.yml`](../../.github/workflows/native-image.yml). Native Binaries
erscheinen für **`linux-x64`** (Release-Gate) und **`windows-x64`** (best-effort).

Damit entfallen auch die macOS-spezifischen Drosselungen (`--parallelism=2`) und die
Dispatch-Schrauben, die eigens für die Messreihe gebaut wurden — sie hatten keinen
anderen Zweck.

**Rückgängig zu machen** ist das jederzeit: Kehrt die Plattform zurück (größerer
Runner, geringerer Speicherbedarf einer künftigen GraalVM-Version), genügt der
Matrix-Eintrag. Diese ADR verzichtet nicht dauerhaft auf macOS als Zielplattform,
sondern auf das native Binary unter den heutigen Bedingungen.

## Konsequenzen

- **Positiv:** Der Release-Pfad ist deterministisch. Kein Rerun-Ritual, keine
  Erklärung fehlender Assets, ~30 Minuten weniger Laufzeit je Tag-Build.
- **Negativ:** macOS-Nutzer bekommen kein Java-freies Binary und damit nicht den
  ~20-fach schnelleren Start, der Aufrufe in Schleifen, Hooks und CI-Schritten
  dominiert. Sie brauchen eine JVM — über Homebrew ist die Abhängigkeit deklariert
  und wird mitinstalliert.
- **Abgrenzung:** Betrifft **nur** das native Binary. macOS bleibt vollwertige
  Laufzeitplattform über Homebrew, ZIP/TAR, Fat JAR und Container-Image. Der
  `verify-homebrew`-Job läuft weiterhin auf einem macOS-Runner — er ist von dieser
  Entscheidung nicht berührt und historisch belastbar.
