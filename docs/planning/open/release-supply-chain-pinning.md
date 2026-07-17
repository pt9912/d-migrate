# Release-Supply-Chain: ungepinnte Actions und unverifizierte Downloads (P2)

> **Status:** Vorabklärung (2026-07-17)
> **Trigger:** Security-Vollaudit
> ([`security-audit-2026-07-17.md`](security-audit-2026-07-17.md), Befunde 6 = P2,
> 15/16/17 = P3). Gemeinsame Wurzel: Artefakte aus fremder Hand werden ohne
> Integritätsnachweis ausgeführt.
> **Aktivierungsbedingung:** P2 — RC-Kandidat → `next/`-Plan.

## Befunde

**6 (P2, CWE-1357) — Third-Party-Action mit Write-Token auf mutablem Tag.**
`release-homebrew.yml:64` nutzt `Justintime50/homebrew-releaser@v3` und
übergibt `secrets.HOMEBREW_TAP_TOKEN`. Ein Tag ist verschiebbar: wer das
Upstream-Repo kompromittiert, bekommt den Tap-Write-Token. Der
`verify-homebrew`-Job greift dagegen nicht. **Gleiche Klasse, unmitigiert:**
`build.yml:149` gibt `secrets.DOCKERHUB_TOKEN` an `docker/login-action@v3`.

**15 (P3, CWE-494) — Unverifiziertes `curl | bash`** von `deb.nodesource.com`
in der `integration-test`-Stage (`Dockerfile:248`). Die Stage läuft in CI und
lokal, der Container hat den Host-Docker-Socket.

**16 (P3, CWE-494) — yq/jq per `ADD` aus GitHub-Releases** ohne
SHA256-Verifikation, anschließend ausgeführt (`Dockerfile:271-275`).

**17 (P3, CWE-1104) — Test-Framework im Produktivartefakt.** kotest/JUnit/
byte-buddy-agent/JNA landen über `implementation(testFixtures(...))` im
Distributions-Artefakt (`adapters/driving/cli/build.gradle.kts:81`).

## Kontext: das Muster ist im Repo schon richtig gelöst

`make/gate.mk:29` pinnt das semgrep-Image per Digest
(`semgrep/semgrep@sha256:...`) und `scripts/fetch-semgrep-rules.sh` holt die
Regeln SHA256-verifiziert. Der Hermetik-Kontrakt existiert also und ist
formuliert — er ist nur nicht auf Actions, Base-Images und Tool-Downloads
angewandt. Ergänzend aus der supply-chain-Fläche (Befunde im Bericht):
Gradle-Wrapper ohne `distributionSha256Sum`, keine
`gradle/verification-metadata.xml` <!-- d-check:ignore (Nichtexistenz IST der Befund) -->, Base-Images nur Tag-gepinnt.

## Arbeitspakete (Skizze)

1. Alle GitHub-Actions auf Commit-SHA pinnen (`uses: owner/repo@<sha>  # v3.1.2`),
   priorisiert die beiden mit Secret-Zugriff (`homebrew-releaser`,
   `docker/login-action`). Dependabot hält sie danach aktuell — die
   `github-actions`-Sektion in `.github/dependabot.yml` ist dafür schon da.
2. `GITHUB_TOKEN`-Permissions je Workflow explizit minimieren.
3. nodesource-Installation ersetzen (Node aus gepinntem Base-Image) oder
   GPG-/SHA256-verifizieren.
4. yq/jq mit SHA256-Prüfung holen.
5. `distributionSha256Sum` im Gradle-Wrapper setzen + Wrapper-Validation-Action
   in CI.
6. testFixtures-Leak aus dem CLI-Distributionsartefakt entfernen (eigentliche
   Ursache: produktiver Code hängt an testFixtures — vgl. Memory-Regel
   „Resource-Loader-Kolokation").
7. Base-Images per Digest pinnen (Dependabot-`docker`-Sektion hält sie nach).
8. Gradle-Dependency-Verification erwägen — Nutzen gegen Wartungslast abwägen.

## Fundstellen

- `.github/workflows/release-homebrew.yml:64` (Tap-Token an `@v3`)
- `.github/workflows/build.yml:149` (`DOCKERHUB_TOKEN` an `docker/login-action@v3`)
- `Dockerfile:248` (`curl | bash` nodesource)
- `Dockerfile:271` (yq/jq per `ADD`)
- `Dockerfile:56`, `Dockerfile:393` (Base-Images nur Tag-gepinnt)
- `gradle/wrapper/gradle-wrapper.properties:3` (kein `distributionSha256Sum`)
- `adapters/driving/cli/build.gradle.kts:81` (testFixtures im Produktivartefakt)
- `make/gate.mk:29` (korrektes Gegenmuster: Digest-Pin)
