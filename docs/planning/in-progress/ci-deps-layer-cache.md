# CI: Layer-Cache für die `deps`-Stage (Maven Central antwortet mit 429)

- **Status**: In Arbeit (2026-11-26). Umgesetzt und lokal belegt; offen ist der Nachweis in der CI (siehe DoD).
- **Ziel**: Ein Push lädt die Abhängigkeiten nicht fünfmal neu von Maven Central. Ein `429 Too Many Requests` beim Bau
  der `deps`-Stage färbt einen Lauf nicht mehr rot, der nichts mit dem Code zu tun hat.
- **Herkunft**: Compare-Matrix #79 auf dem Release-Commit von 1.14.0 (Maven-429 bei `kotlin-gradle-plugins-bom`); gleiche
  Ursache wie bei den Läufen zu 1.13.0 und davor. Eigner-Frage vom 2026-11-26 („sollen wir ein Maven-Repo cachen?").
- **Vorbedingungen**: keine. Kein ADR nötig: Es ändert den Bau, nicht das Verhalten des Produkts.

## Ausgangslage (gemessen 2026-11-26)

- `Dockerfile` (Stage `deps`): `gradle --no-daemon resolveAllDependencies` lädt **alle** auflösbaren Konfigurationen.
  Das Ergebnis ist 837 MiB groß (das Gradle-Benutzerverzeichnis des Images, davon 533 MiB `modules-2`); der Bau dauert lokal 3,6 Minuten.
- Fast alle Workflows bauen über `docker build` (`make ci-build`, `integration`, Coverage, Compare-Matrix, Smokes).
  Jeder Lauf startet auf einem frischen Runner ohne Layer-Cache und holt die Abhängigkeiten neu. Pro Push sind das
  mindestens fünf volle Downloads (Build & Test, Integration Tests, Per-Module Coverage, Compare-Matrix, dazu der
  Veröffentlichungs-Job).
- Nur `native-image.yml` cacht schon (`setup-gradle`). Ein `actions/cache` auf ein Host-Verzeichnis käme für die anderen
  nicht in den Container.
- Actions-Cache des Repositories: 102 Einträge, 2,6 GiB von 10 GiB.

## Entscheidung

Den Layer der `deps`-Stage über BuildKit cachen (`--cache-from type=local`), den Cache-Ordner mit `actions/cache`
halten. **Nur die `deps`-Stage wird exportiert**, nie `compile`, `build` oder ein Testlauf: Ein Cache, der einen Test
überspringt, wäre ein grüner Lauf, der nichts geprüft hat. Das ist durch den Aufbau gesichert, nicht durch einen Filter:
Der Export läuft mit `--target deps`, der Cache kennt die späteren Stages nicht.

- **Schlüssel** (`hashFiles`): `Dockerfile`, `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`,
  `**/build.gradle.kts`. Er entscheidet nur, ob ein Ordner geladen wird; ob ein Layer trifft, prüft BuildKit selbst.
  Bei geänderten Build-Dateien gibt es einen Fehltreffer, nie einen veralteten Treffer.
- **Schreiben** nur bei Fehltreffer (`actions/cache` speichert den Ordner nur, wenn der Schlüssel neu ist).
- **Ort**: Composite-Action `.github/actions/docker-deps-cache`; sie setzt `DOCKER_BUILD_FLAGS`
  (`--load --cache-from type=local,src=…`), das das `Makefile` an jeden `docker build` des Haupt-Dockerfiles hängt.
  Lokal bleibt die Variable leer, dort ändert sich nichts. `--load` gehört dazu, weil ein buildx-Builder das Image sonst
  nicht in den Daemon legt, die Make-Ziele es aber danach starten.
- **Retry**: `RUN … resolveAllDependencies` im `Dockerfile` versucht es dreimal (30 s, 60 s Wartezeit). Er fängt den
  Fehltreffer-Fall ab, in dem der Cache nichts hilft.
- **Verdrahtet** in den vier Push-Workflows: `build.yml` (Job `build`), `integration.yml`, `coverage-modules.yml`,
  `mcp-e2e-compare-matrix.yml`. Der Job `docker` in `build.yml` (Veröffentlichung) und die übrigen Smokes bleiben
  vorerst unberührt, bis der Cache in der CI belegt ist.
- **Dependabot** liest Composite-Actions nur, wenn ihr Ordner unter `directories` steht (`.github/dependabot.yml`).

**Nicht gewählt:** ein eigener Maven-Mirror oder Nexus (Betrieb, neue Vertrauensstelle in der Lieferkette, dieselbe
Last an anderer Stelle); `actions/cache` auf `~/.gradle` (kommt nicht in den Docker-Bau).

## Lokaler Beleg (2026-11-26, Docker, frischer buildx-Builder `docker-container`)

| Schritt | Ergebnis |
| ------- | -------- |
| `buildx build --target deps --cache-to type=local,mode=max` (Fehltreffer) | 4 min 42 s, Cache-Ordner **936 MiB** |
| frischer Builder, `make docker-test MODULES=":hexagon:core" DOCKER_BUILD_FLAGS="--builder … --load --cache-from type=local,src=…"` | Lauf grün (3 min 9 s). Die 48 Layer der Stage `deps` kommen aus dem Cache, **0 Zeilen „Resolving …"** (kein Zugriff auf Maven Central). `compile` (`gradle classes`) und `build` (`:hexagon:core:test`, Task `:hexagon:core:test` ausgeführt) laufen vollständig. |
| `docker build --builder … --load --iidfile … --target deps` | Exit 0, die ID aus der Datei startet ein Image mit gefülltem `modules-2` (die Make-Ziele `release-assets`/`docker-smoke` stützen sich darauf) |

## Akzeptanz (DoD)

* **Lokal belegt** (siehe Tabelle): Cache-Treffer ohne Download; Compile und Tests laufen auch mit Cache; `--load` plus
  `--iidfile` funktioniert.
* **In der CI belegt** (offen, erst nach dem Push messbar): Auf dem ersten Push mit geänderten Build-Dateien läuft der
  Schritt „Warm deps layer" und speichert den Cache; auf dem **nächsten** Push derselben Dateien zeigen alle vier
  Workflows „Cache restored" und überspringen den Schritt; im Log von `make ci-build` steht kein `Resolving :`.
* **Tests laufen weiter**: In `make ci-build` auf dem Cache-Treffer stehen die Testaufgaben im Log (kein `UP-TO-DATE` für
  `test`), `koverVerify` läuft, der Lauf ist nicht schneller, weil er etwas ausgelassen hätte.
* **Cache-Größe gemessen**: Der Ordner liegt bei 936 MiB je Schlüssel. Nach einigen Pushes prüfen, dass die Gesamtgröße
  (`gh api repos/pt9912/d-migrate/actions/cache/usage`) deutlich unter 10 GiB bleibt; sonst Schlüssel oder Umfang kürzen.
* **Release-Pfad**: Ein Tag-Lauf liest Caches der Standardverzweigung; ob der Veröffentlichungs-Job (`docker` in
  `build.yml`) den Cache nutzen soll, wird erst nach den Messungen entschieden.

## Nicht im Umfang

* Der Veröffentlichungs-Job und die Smokes (`sample-db-*`, `bi-demo-smoke`, `perf-acceptance`, `mcp-e2e-smoke`).
* `docker/native-image.Dockerfile` (eigener Bau, eigene Basis).
* Ein Mirror oder ein Proxy für Maven Central.
