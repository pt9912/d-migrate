# Tracker: Multi-Arch für das JVM-OCI-Image (`linux/amd64` + `linux/arm64`)

> **Status:** Vorschlag (Draft) / Trigger Watch (2026-07-31)
> **Trigger:** Die publizierten Images sind **Single-Platform `linux/amd64`** (verifiziert am
> `1.0.0-RC2`-Tag: `application/vnd.docker.distribution.manifest.v2+json`, kein Multi-Arch-Index).
> Auf Apple Silicon und ARM64-Linux-Servern (Graviton, Ampere) läuft das Image damit **emuliert** —
> spürbar beim JVM-Start. Nutzerfrage 2026-07-31.
> **Aktivierungsbedingung** (Move nach `../next/`): Klärung der Registry-Naht (siehe „Der eigentliche
> Knackpunkt") **plus** eine Entscheidung, ob die Digest-Identität des Docker-Hub-Spiegels erhalten
> bleiben muss. Vorgelagert sinnvoll:
> [`oci-image-runtime-divergence.md`](oci-image-runtime-divergence.md) — solange Jib- und
> Dockerfile-Runtime auseinanderlaufen, verdoppelt Multi-Arch die Divergenz.

## Ausgangslage

Das **publizierte** JVM-Image baut **Jib**, nicht die Dockerfile-`runtime`-Stage:
`build.yml` → `make docker-oci-build` → Dockerfile-Stage `jib-image-tar` → `docker load` → `docker
tag` → `docker push` (GHCR **und** Docker Hub). Die Jib-Konfiguration liegt in
[`adapters/driving/cli/build.gradle.kts`](../../../adapters/driving/cli/build.gradle.kts)
(`jib { from { image = "eclipse-temurin:21-jre-noble" } … }`).

JVM-Bytecode ist architekturneutral — der Unterschied steckt **nur im Base-Image**. Jib unterstützt
das direkt:

```kotlin
jib {
    from {
        image = "eclipse-temurin:21-jre-noble"
        platforms {
            platform { architecture = "amd64"; os = "linux" }
            platform { architecture = "arm64"; os = "linux" }
        }
    }
}
```

Damit ist das hier der **billige** der beiden Multi-Arch-Schritte; der teure ist
[`oci-image-multiarch-native.md`](oci-image-multiarch-native.md).

## Der eigentliche Knackpunkt: Registry-Naht vs. Spiegel-Garantie

Der aktuelle Ablauf baut **einmal lokal** und pusht dasselbe Image zweimal. Genau daraus folgt die
Spiegel-Zusage, die in `releasing.md` 4.4.1 steht und am RC2-Tag verifiziert wurde: GHCR und Docker
Hub tragen **denselben Manifest-Digest** (`sha256:c7304c16…`).

Multi-Arch bricht diesen Ablauf, weil ein Multi-Plattform-Image **nicht** in den lokalen
Docker-Daemon geladen werden kann (`jibDockerBuild` / `docker load` können nur eine Plattform).
Optionen:

1. **Zweimal direkt aus Jib in die Registries pushen** — einfach, aber zwei Builds; die Digests
   können auseinanderlaufen, die Spiegel-Zusage wäre nicht mehr trivial belegbar.
2. **Einmal nach GHCR pushen, dann das Manifest kopieren** (`docker buildx imagetools create
   --tag <hub> <ghcr>` oder `crane copy`) — erhält die Digest-Identität und hält GHCR als
   Referenz-Registry. **Bevorzugt**, kostet einen zusätzlichen Schritt in `build.yml`.
3. **`docker buildx` statt Jib** — größerer Umbau, verwirft Jibs reproduzierbare Layer;
   nur sinnvoll, wenn ohnehin auf die Dockerfile-Runtime vereinheitlicht wird (siehe Divergenz-Ticket).

## Akzeptanz (falls gebaut)

- `docker pull` auf einem ARM64-Host zieht die arm64-Variante ohne Emulationshinweis.
- `docker buildx imagetools inspect` zeigt einen Index mit beiden Plattformen.
- GHCR und Docker Hub tragen weiterhin **denselben** Digest (oder die Zusage wird bewusst und
  dokumentiert aufgegeben).
- `releasing.md` 4.4.1 und `packaging/dockerhub/overview.md` nachgezogen.

## Abgrenzung

Kein macOS-/Windows-Image: Container-Images tragen Linux-Userspace. macOS-Container existieren nicht,
Windows-Container brauchen einen Windows-Host und einen eigenen Build — beides kein Scope. Die
OS-Abdeckung liefern die **nativen Binaries** am GitHub-Release (`linux-x64`, `macos-arm64`,
`windows-x64`).
