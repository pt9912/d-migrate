---
status: accepted
date: 2026-07-31
decision-makers: pt9912
consulted: docs/planning/done/oci-image-runtime-divergence.md, docs/user/releasing.md, docker/native-image.Dockerfile
informed: Dockerfile, Makefile, .github/workflows/build.yml, adapters/driving/cli/build.gradle.kts, README.md, docs/user/anwenderhandbuch.md
---

# Das publizierte JVM-OCI-Image kommt aus der Dockerfile-`runtime`-Stage, nicht aus Jib

> **Status: accepted (2026-07-31).** Das zu veröffentlichende JVM-Image wird mit
> `docker build --target runtime` gebaut. Der Jib-Pfad (Plugin, `jib {}`-Block,
> Dockerfile-Stage `jib-image-tar`) entfällt ersatzlos. Damit gibt es **einen**
> Bauweg je Image-Klasse — denselben, den das native Image über
> `docker/native-image.Dockerfile` bereits geht.

## Kontext und Problemstellung

Bis einschließlich `1.0.0-RC2` existierten **zwei** Runtime-Definitionen, und
publiziert wurde die *andere* als die geprüfte und dokumentierte:

| | Dockerfile-Stage `runtime` | Jib (**publiziert** bis RC2) |
|---|---|---|
| Nutzer | `USER dmigrate` (uid 10001) | keiner → **root** |
| Entrypoint | `["d-migrate"]` | `java … MainKt` |
| `mod_spatialite` | installiert | **fehlt** |
| Verwendung | lokale Smokes, sample-db-Harness | GHCR + Docker Hub |

Belegt am RC2-Tag: `docker run --rm --entrypoint sh <image> -c 'id -u'` liefert
für `pt9912/d-migrate:1.0.0-RC2`, `ghcr.io/…:1.0.0-RC2` und `ghcr.io/…:latest`
(= 0.9.12, das damals aktuelle Stable) jeweils **0**.

Drei Folgen, die den Kern des Problems ausmachen:

- **Die Dokumentation sagte das Gegenteil.** README, Anwenderhandbuch und die
  Docker-Hub-Overview beschrieben das Image als non-root (`uid 10001`).
- **Der interne Security-Audit hatte den falschen Artefakt geprüft.** Der Punkt
  „Runtime non-root bestätigt (`useradd --uid 10001`, `USER` vor `ENTRYPOINT`)"
  bezog sich auf die **Dockerfile**-Stage. Eine als vorhanden verbuchte Kontrolle,
  die im Auslieferungsartefakt fehlte.
- **SpatiaLite war im publizierten Image nicht benutzbar.** Unentdeckt, weil die
  sample-db-Harness gegen die Dockerfile-Runtime fährt — die Testfläche deckte das
  Auslieferungsartefakt nicht ab.

Die Ursache war kein Fehlgriff, sondern ein blinder Fleck: Der `USER` kam
2026-06-19 (`43bda239`) ins Dockerfile, ausgelöst von der semgrep-Regel
`dockerfile.security.missing-user`. Semgrep sieht Dockerfiles — das publizierte
Image entsteht aber aus Gradle-/Kotlin-Konfiguration, die keine Dockerfile-Regel
je erfassen kann. Der Fix landete dort, wo der Linter hinsah, nicht dort, wo das
Artefakt gebaut wird. Der Kommentar an dieser Stelle („Run the **published** image
as a non-root user") zeigt die Fehlannahme, die von dort in Doku und Audit wanderte.

## Entscheidungstreiber

- **Ein Artefakt, ein Bauweg.** Zwei Runtime-Definitionen driften; welche davon
  ausgeliefert wird, war nicht am Code ablesbar.
- **Die Zusage ist dokumentiert.** „non-root" steht in drei Nutzerdokumenten. Sie
  einzulösen ist billiger und ehrlicher, als sie zurückzunehmen.
- **Das native Image beweist das Muster bereits.** `docker/native-image.Dockerfile`
  (Stage `native-runtime`) wird direkt per `docker build`/`push` publiziert und hat
  `USER dmigrate`, `/work` und SpatiaLite korrekt — verifiziert am RC2-Tag.
- **Prüfbarkeit.** Ein Dockerfile-Bauweg ist von semgrep und von einem Smoke gegen
  das gebaute Image erreichbar; eine Jib-Gradle-Konfiguration ist es für die
  vorhandenen Gates nicht.

## Betrachtete Optionen

1. **Nur `container.user` in Jib setzen** — minimal, aber unvollständig: SpatiaLite
   fehlte weiterhin, und `/work` bliebe root-owned (die `runtime`-Stage `chown`t es),
   sodass Schreiben ohne Bind-Mount bricht. Die Divergenz bliebe bestehen.
2. **Nur die Dokumentation korrigieren** („läuft als root") — billig und ehrlich,
   löst aber weder das Sicherheitsargument noch SpatiaLite.
3. **Die `runtime`-Stage publizieren, Jib entfernen** — beseitigt beide Defekte und
   die Divergenz.

## Entscheidung

Gewählt: **Option 3.** `make docker-oci-build` baut `--target runtime`; `build.yml`
tagt und pusht dieses Image unverändert weiter. Der Jib-Pfad wird entfernt: Plugin,
`jib {}`-Block und die Dockerfile-Stage `jib-image-tar`.

## Konsequenzen

- **Gut:** Das publizierte Image läuft als `uid 10001`, hat `mod_spatialite` und den
  `d-migrate`-Entrypoint. Doku und Audit-Aussage stimmen wieder. Es gibt je
  Image-Klasse genau einen Bauweg, und beide sind Dockerfiles.
- **Schlecht:** Das Image wächst von **432 MB auf 516 MB** (+84 MB, ~19 %) —
  `mod_spatialite` samt apt-Layern und das `installDist`-Layout statt Jibs
  optimiertem Layer-Schnitt. Bewusst in Kauf genommen; wer das Minimum will, nimmt
  das native Image (356 MB).
- **Schlecht:** Jibs reproduzierbares, dependency-getrenntes Layering entfällt.
  Praktisch irrelevant, solange je Release ohnehin ein voller Build läuft.
- **Neutral:** Der Entrypoint wechselt von `java … MainKt` auf den
  `d-migrate`-Launcher. Für Aufrufer identisch — Argumente stehen wie bisher direkt
  hinter dem Image-Namen.
- **Absicherung:** [`releasing.md`](../user/releasing.md) 4.8 prüft ab sofort das
  **publizierte** Image (`id -u` ≠ 0, `mod_spatialite` vorhanden). Ohne diesen Smoke
  könnte derselbe Auseinanderlauf unbemerkt wiederkehren — er war schließlich zwei
  Releases lang unbemerkt.

## Weitere Informationen

- Befund und Beweisführung: [`oci-image-runtime-divergence.md`](../planning/done/oci-image-runtime-divergence.md)
- Multi-Arch baut auf dieser Vereinheitlichung auf:
  [`oci-image-multiarch-jvm.md`](../planning/open/oci-image-multiarch-jvm.md)
