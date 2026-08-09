# SDKMAN-Distribution

- **Status**: Ready-to-arm (nach `next/` 2026-07-21). Die Release-Automatik ist **gebaut**
  ([`.github/workflows/sdkman-release.yml`](../../../.github/workflows/sdkman-release.yml)), aber
  **inert bis zum externen Onboarding** (Candidate-Freigabe + Secrets) und einem Tag-Cut. Nur bei einem
  echten Release wirksam, **nicht lokal testbar**.
- **Trigger**: Dritte der drei 1.0.0-Distributionszeilen (neben GraalVM Native Image ✅ und Docker Hub
  ✅). SDKMAN ist der idiomatische Kanal für JVM-CLIs: `sdk install dmigrate`.

## Offener Punkt: Candidate-PR (Stand 2026-08-09)

[**PR #794**](https://github.com/sdkman/sdkman-db-migrations/pull/794) ist offen — `MERGEABLE`,
Merge-State `BLOCKED` wegen `REVIEW_REQUIRED` (Branch-Protection; das Repo lässt auf PRs keine CI
laufen). Damit ist der **Merge selbst** der Wartepunkt, nicht die Candidate-Freigabe danach.

**Die Wartefrist ist abgelaufen.** Der Eigner hatte am 2026-07-31 entschieden, bis zum 2026-08-05 auf
den Merge zu warten; am 2026-08-09 ist der PR unverändert `OPEN` mit letzter Aktualisierung
`2026-07-31T08:54Z`. Damit gilt [ADR 0042](../../adr/0042-sdkman-kein-1.0.0-gate.md): **1.0.0 wird
ohne SDKMAN geschnitten**, die Roadmap-Zeile bleibt `⛔`, und es wird **keine neue Frist** gesetzt —
der Wartepunkt liegt im fremden Repo. Der Slice bleibt in `next/` und ist unverändert scharf; zu tun
ist nach dem Merge nur noch die Schrittfolge unter „Voraussetzung" plus der Nachlauf-Dispatch.

Das ist gefahrlos, weil der Publish **nachträglich** möglich ist: `sdkman-release.yml` hat neben dem
Tag-Trigger ein `workflow_dispatch` mit `tag`-Input. Sobald Candidate und Secrets stehen, genügt für
einen längst veröffentlichten Tag

```bash
gh workflow run sdkman-release.yml -f tag=v1.0.0
```

— kein Re-Release, keine Patch-Version. Der Dispatch-Pfad wurde am 2026-07-31 mit `tag=v1.0.0-RC2`
real durchlaufen (Job grün, Skip-mit-Notice mangels Credentials).

Normativ ist SDKMAN **kein 1.0.0-Gate**: das Lastenheft nennt es nicht, und
[ADR 0039](../../adr/0039-externer-security-audit-kein-1.0.0-gate.md) ordnet unter
„Entscheidungstreiber" ausdrücklich auch SDKMAN als Fremdbeschaffung ein, an der 1.0.0 nicht hängt.
Nach derselben ADR-Regel („ein permanenter Ausschluss/eine Verschiebung gehört in einen ADR") hält
[ADR 0042](../../adr/0042-sdkman-kein-1.0.0-gate.md) das Ergebnis der abgelaufenen Frist fest,
inklusive der fünfschrittigen Bedingung, unter der die Roadmap-Zeile `⛔` → `✅` wechselt.

Merge-Kadenz zur Erwartungshaltung (aus der PR-Historie des Repos): `Jenesis`/`jextract` am selben
Tag, `kUML` 1 Tag, `Atmosphere` 1 Woche, `TornadoVM` 3,5 Wochen, `Grace` 5, `dependency-watch` 6,
`ksrc` 3 Monate, `Jeka` 6 Monate. Vor #794 liegen vier weitere Candidate-PRs, der älteste seit
2026-07-06.

## Artefakt

Das **UNIVERSAL-JVM-Launcher-ZIP** `d-migrate-<version>.zip` (gradle-`application`-`distZip`). Struktur
`d-migrate-<version>/{bin/{d-migrate,d-migrate.bat}, lib/*.jar}` = **well-formed SDK-Archiv** laut
[SDKMAN-Wiki](https://github.com/sdkman/sdkman-cli/wiki/Well-formed-SDK-archives) (Basisverzeichnis
`${candidate}-${version}`, `bin/` kommt auf den PATH). **Kein Repackaging nötig**; braucht Java
(SDKMAN-Nutzer haben es typisch). Das ZIP ist bereits ein Release-Asset (release-homebrew.yml).

## Automatik (gebaut)

[`sdkman-release.yml`](../../../.github/workflows/sdkman-release.yml), **Tag-Push** (`on: push:
tags: v*`) plus `workflow_dispatch` für einen manuellen Nachlauf, mit Warten auf das Release-Asset:
- offizielle Action `sdkman/sdkman-release-action@…v0.2.0` (SHA-gepinnt) → `POST /release`
  (`candidate=dmigrate`, `version`, `url`, `platform=UNIVERSAL`).
- `PUT /default` (separater `curl`) nur bei **Stable** — RCs werden released, aber nicht Default
  (dieselbe Regel wie `:latest`/Homebrew-nur-Stable).
- Gated auf `SDKMAN_CONSUMER_KEY`/`SDKMAN_CONSUMER_TOKEN`; fehlen sie → Skip + Notice (kein roter
  Release), wie der Docker-Hub-Spiegel.

### Warum Tag-Push und nicht `on: release`

Der erste Entwurf hing an `on: release: [published, prereleased]` — der von SDKMAN empfohlene
Trigger, der scheinbar ohne Wait-Poll auskommt, weil er erst feuert, wenn Release und Assets
existieren. Beim ersten echten Einsatz (`v1.0.0-RC2`, 2026-07-31) hatte der Workflow **null Läufe**.
Ursache ist dokumentiertes GitHub-Verhalten: *„events triggered by the `GITHUB_TOKEN` will not create
a new workflow run, with the following exceptions: `workflow_dispatch` and `repository_dispatch`"*
([Doku](https://docs.github.com/en/actions/how-tos/write-workflows/choose-when-workflows-run/trigger-a-workflow)).
Das Release entsteht in `release-homebrew.yml` per `gh release create` mit `GH_TOKEN:
${{ github.token }}`, also genau so. Der Defekt war **unabhängig von den Credentials**: auch mit
freigegebenem Candidate wäre nichts passiert.

Verworfen: ein PAT bzw. GitHub-App-Token in `release-homebrew.yml` (löst es laut Doku, kostet aber
ein rotationspflichtiges Credential für einen optionalen Zusatzkanal) und ein `gh workflow run` aus
`release-homebrew.yml` heraus (funktioniert, weil Dispatch von der Regel ausgenommen ist, koppelt
aber den Release-Ersteller an diese Distributionsklasse).

Gewartet wird auf das **Asset**, nicht nur auf das Release: die SDKMAN-API bekommt eine URL und lädt
sie selbst — ein Release ohne das ZIP ergäbe einen Eintrag auf eine tote URL. Damit ist die Prüfung
strenger als der `attach-release`-Job in `native-image.yml`, dem das bloße Release genügt.

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
