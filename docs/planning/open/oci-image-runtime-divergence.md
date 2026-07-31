# Tracker: Das publizierte OCI-Image ist NICHT die Dockerfile-`runtime`-Stage

> **Status:** Befund (Draft) / **potenziell release-relevant** (2026-07-31)
> **Trigger:** Beim Faktencheck für [`oci-image-multiarch-jvm.md`](oci-image-multiarch-jvm.md)
> aufgefallen: das publizierte Image läuft **als root** und enthält **kein `mod_spatialite`** —
> beides im Widerspruch zur Dokumentation und zu einem als „bestätigt" abgehakten Audit-Punkt.
> **Aktivierungsbedingung:** keine — hier ist zu **entscheiden**, nicht zu warten. Entweder die
> Jib-Konfiguration wird an die Dockerfile-Runtime angeglichen, oder Doku und Audit-Eintrag werden
> auf den Ist-Stand korrigiert. Beides zu lassen ist die einzige unzulässige Option.

## Belegter Ist-Stand (2026-07-31, gegen die publizierten Images)

```
docker run --rm --entrypoint sh <image> -c 'id -u; id -un'
  pt9912/d-migrate:1.0.0-RC2           -> 0 root
  ghcr.io/pt9912/d-migrate:1.0.0-RC2   -> 0 root
  ghcr.io/pt9912/d-migrate:latest      -> 0 root     (= 0.9.12, aktuelles Stable)

docker image inspect … --format '{{.Config.User}}'   -> leer
ls /usr/lib/*/mod_spatialite*                        -> nicht vorhanden
Entrypoint: java -XX:+UseZGC … -cp @/app/jib-classpath-file dev.dmigrate.cli.MainKt
```

## Ursache: zwei Runtime-Definitionen, publiziert wird die andere

| | Dockerfile-Stage `runtime` | **Jib** (publiziert) |
|---|---|---|
| Nutzer | `USER dmigrate` (uid 10001) | **keiner → root** |
| Entrypoint | `["d-migrate"]` (Launcher) | `java … MainKt` |
| `mod_spatialite` | per `apt-get` installiert | **fehlt** |
| Verwendung | `make docker-build`, sample-db-Harness, lokale Smokes | **GHCR + Docker Hub** |

`build.yml` publiziert `make docker-oci-build` → Dockerfile-Stage `jib-image-tar` → `docker load` →
`tag` → `push`. Die `runtime`-Stage wird dabei **nie** publiziert. Die Jib-Konfiguration
([`adapters/driving/cli/build.gradle.kts`](../../../adapters/driving/cli/build.gradle.kts))
setzt `workingDirectory`, `volumes` und Labels — aber **kein** `container.user`, und ihr
Base-Image `eclipse-temurin:21-jre-noble` bringt SpatiaLite nicht mit.

## Warum das mehr ist als ein Schönheitsfehler

1. **Die Doku behauptet das Gegenteil** — an drei Stellen, teils mit Handlungsanweisung:
   - [`README.md`](../../../README.md) „The published image runs as a **non-root** user (`uid 10001`)"
   - [`anwenderhandbuch.md`](../../user/anwenderhandbuch.md) 2.1 „Das Image läuft als **non-root** Benutzer"
   - [`packaging/dockerhub/overview.md`](../../../packaging/dockerhub/overview.md) „Both run as `uid 10001`"

   Der `--user "$(id -u):$(id -g)"`-Rat in README und Handbuch ist damit als *Notwendigkeit* falsch
   begründet (er hilft trotzdem gegen root-owned Output — aber aus dem umgekehrten Grund).

2. **Der Security-Audit hat den falschen Artefakt geprüft.**
   [`security-audit-2026-07-17.md`](../done/security-audit-2026-07-17.md) Zeile 584 führt
   „Runtime non-root bestätigt (`useradd --uid 10001`, `USER` **vor** `ENTRYPOINT`…)" unter den
   geprüften, befundfreien Flächen. Geprüft wurde die **Dockerfile-Stage**, ausgeliefert wird das
   **Jib-Image**. Das ist die unangenehmste Sorte Lücke: eine Kontrolle, die als vorhanden
   verbucht ist und es nicht ist. Vergleiche die eigene Merkregel „Befund-Ledger ≠
   Audit-Vollständigkeit".

3. **SpatiaLite ist im publizierten Image nicht benutzbar.** `--spatial-profile spatialite` kann
   dort nicht funktionieren. Unentdeckt blieb das, weil die sample-db-Harness gegen die
   **Dockerfile**-Runtime fährt — die Testfläche deckt das Auslieferungsartefakt nicht ab.

## Optionen

1. **Jib angleichen** (klein, bevorzugt): `container.user = "10001"` setzen; für SpatiaLite ein
   Base-Image mit der Extension oder `from.image` auf die Dockerfile-Runtime umstellen. Danach Doku
   und Audit-Eintrag als *bestätigt* belassen — dann stimmen sie.
2. **Auf eine Runtime vereinheitlichen** (größer, sauberer): das Dockerfile-`runtime`-Image
   publizieren und Jib fallenlassen — oder umgekehrt. Zwei Definitionen driften weiter
   auseinander, und Multi-Arch würde die Divergenz verdoppeln.
3. **Nur Doku korrigieren** (billig, ehrlich, aber schwach): festhalten, dass das Image als root
   läuft und SpatiaLite fehlt. Löst das Sicherheitsargument nicht.

## Nächster Schritt

Eigner-Entscheidung. Falls Option 1 oder 2: ein Smoke, der das **publizierte** Image prüft
(`id -u` ≠ 0, `mod_spatialite` vorhanden), gehört mit in `releasing.md` 4.8 — sonst wiederholt sich
genau dieser Auseinanderlauf.
