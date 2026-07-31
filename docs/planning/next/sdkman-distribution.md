# SDKMAN-Distribution

- **Status**: Ready-to-arm (nach `next/` 2026-07-21). Die Release-Automatik ist **gebaut**
  ([`.github/workflows/sdkman-release.yml`](../../../.github/workflows/sdkman-release.yml)), aber
  **inert bis zum externen Onboarding** (Candidate-Freigabe + Secrets) und einem Tag-Cut. Nur bei einem
  echten Release wirksam, **nicht lokal testbar**.
- **Trigger**: Dritte der drei 1.0.0-Distributionszeilen (neben GraalVM Native Image ✅ und Docker Hub).
  SDKMAN ist der idiomatische Kanal für JVM-CLIs: `sdk install dmigrate`.

## Artefakt

Das **UNIVERSAL-JVM-Launcher-ZIP** `d-migrate-<version>.zip` (gradle-`application`-`distZip`). Struktur
`d-migrate-<version>/{bin/{d-migrate,d-migrate.bat}, lib/*.jar}` = **well-formed SDK-Archiv** laut
[SDKMAN-Wiki](https://github.com/sdkman/sdkman-cli/wiki/Well-formed-SDK-archives) (Basisverzeichnis
`${candidate}-${version}`, `bin/` kommt auf den PATH). **Kein Repackaging nötig**; braucht Java
(SDKMAN-Nutzer haben es typisch). Das ZIP ist bereits ein Release-Asset (release-homebrew.yml).

## Automatik (gebaut)

[`sdkman-release.yml`](../../../.github/workflows/sdkman-release.yml), `on: release: [published,
prereleased]` (kein Wait-Poll — das Release-Event feuert erst, wenn Release + Assets existieren):
- offizielle Action `sdkman/sdkman-release-action@…v0.2.0` (SHA-gepinnt) → `POST /release`
  (`candidate=dmigrate`, `version`, `url`, `platform=UNIVERSAL`).
- `PUT /default` (separater `curl`) nur bei **Stable** — RCs werden released, aber nicht Default
  (dieselbe Regel wie `:latest`/Homebrew-nur-Stable).
- Gated auf `SDKMAN_CONSUMER_KEY`/`SDKMAN_CONSUMER_TOKEN`; fehlen sie → Skip + Notice (kein roter
  Release), wie der Docker-Hub-Spiegel.

## Voraussetzung (EXTERN, manuell — der eigentliche Gate)

Aus dem [Vendor-Onboarding](https://github.com/sdkman/sdkman-cli/wiki/Vendor-onboarding-process):
1. **PR an `sdkman/sdkman-db-migrations`** — ein Changeset legt den Candidate an (Identifier `dmigrate` — Bindestrich NICHT erlaubt, alle SDKMAN-Candidates sind [a-z0-9]; Anzeigename „d-migrate",
   Beschreibung, Website, Distributionstyp; **keine** Versionen — die kommen später per API). Freigabe
   automatisch nach Merge, kein Review-Gate.
2. **Armored GPG-Public-Key an `info@sdkman.io`** (als Plaintext) → verschlüsselte Antwort mit
   `Consumer-Key` + `Consumer-Token`.
3. Beide als GitHub-Secrets `SDKMAN_CONSUMER_KEY` / `SDKMAN_CONSUMER_TOKEN` hinterlegen.

## Verifikation (beim ersten Tag-Cut nach Onboarding)

Nach grünem `sdkman-release.yml`-Lauf auf einem Host mit `sdk` + Java:
`sdk install dmigrate <version>` → `d-migrate --version`. **Erst dann** die Roadmap-Zeile ⛔→✅ — wie
DockerHub: nichts behaupten, bevor tatsächlich installierbar.

## Erweiterung (kein 1.0.0-Scope): plattform-native SDKMAN-Binaries

SDKMAN unterstützt plattformspezifische Distributionen (`LINUX_64`/`MAC_ARM64`/`WINDOWS_64`). Damit
ließen sich die **nativen Binaries** (kein Java, schneller Start) über SDKMAN ausliefern — je Plattform
ein well-formed Archiv (`bin/d-migrate`) + je ein `POST /release` mit dem passenden `platform`.
Nachrangig; das UNIVERSAL-ZIP deckt 1.0.0.

## Optionale Härtung

`checksum-sha-256` an die Action geben (Input existiert, das `.sha256`-Asset liegt am Release) → SDKMAN
verifiziert dann den Download. Im ersten Wurf weggelassen (untestbar bis Credentials da), sauber
nachrüstbar.
