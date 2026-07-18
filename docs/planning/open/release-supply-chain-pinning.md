# Release-Supply-Chain: ungepinnte Actions und unverifizierte Downloads (P2)

> **Status:** Befund 6 (P2) + 15+16+17 (P3) BEHOBEN 2026-07-18; Gradle-Wrapper-/
> Base-Image-Digest-Härtung + Dependency-Verification offen.
> **Trigger:** Security-Vollaudit
> ([`security-audit-2026-07-17.md`](security-audit-2026-07-17.md), Befunde 6 = P2,
> 15/16/17 = P3). Gemeinsame Wurzel: Artefakte aus fremder Hand werden ohne
> Integritätsnachweis ausgeführt.
>
> **Umsetzung (Befund 6 = AP1 + AP2):** (1) **Alle** mutable-Tag-Actions über die 14
> Workflows auf Commit-SHA gepinnt (mit `# vX.Y.Z`-Kommentar; die
> `github-actions`-Sektion der `.github/dependabot.yml` hält sie nach), priorisiert
> die zwei secret-tragenden `docker/login-action` (`c94ce9fb…`, v3.7.0) und
> `Justintime50/homebrew-releaser` (`a62d7a35…`, v3.3.0). (2) `GITHUB_TOKEN`-
> Permissions je Workflow least-privilege: jeder der 14 Workflows hat jetzt einen
> expliziten top-level `permissions:`-Block (`contents: read` als Default),
> `packages: write` (GHCR-Push) ist auf den `docker`-Job von `build.yml` verengt,
> `contents: write` (GitHub-Release) auf den `publish`-Job von
> `release-homebrew.yml`. Der Tap-Write-Token (`homebrew-releaser`, die CWE-1357-
> Wurzel) ist eine separate PAT — ihn mitigiert das SHA-Pinning, nicht die
> Permissions. YAML validiert; `docs-check` grün.
>
> **Umsetzung (Befund 15+16, Dockerfile-Download-Integrität):** (15) Der Node-
> Install der `integration-test`-Stage nutzt kein `curl | bash` mehr — der
> NodeSource-GPG-Key wird über HTTPS geholt, per SHA256 gepinnt (`b42e0321…`), als
> `signed-by`-Keyring hinterlegt, danach installiert apt `nodejs` signatur-
> verifiziert (Node 20 unverändert). (16) yq/jq werden weiter per `ADD` geladen,
> aber **vor** `chmod`/Nutzung per `sha256sum -c` gegen gepinnte Hashes
> (`YQ_SHA256`/`JQ_SHA256`) geprüft; ein Versions-Bump ohne Hash-Update schlägt laut
> fehl. Verifiziert: `docker build --target integration-test` grün (Key-OK, Node
> 20.20.2 signaturverifiziert, pnpm/node-gyp installiert) + isolierter yq/jq-Build
> (`sha256sum -c` OK).
>
> **Umsetzung (Befund 17, testFixtures-Leak):** Wurzel behoben statt kaschiert —
> die in-memory Store-Impls (`InMemory*`, Prod-Default wenn `server.state.*`
> unkonfiguriert) sind aus `ports-common` testFixtures in ein echtes Adapter-Modul
> **`:adapters:driven:persistence-memory`** (kein kotest) gewandert (Paket
> `dev.dmigrate.server.ports.memory` unverändert → keine Import-Churn). Die CLI
> hängt jetzt via `implementation(project(...))` daran statt an testFixtures;
> Test-Konsumenten (mcp/application/e2e/integration + CLI-Test) via
> `testImplementation`. Der InMemory-spezifische `AuditSinkContractTests` wanderte
> mit (bricht den Projekt-Zyklus ports-common↔persistence-memory). **Verifiziert per
> `jar tf` auf den CLI-Shadow-Jar: kotest/JUnit/byte-buddy/mockk = 0** (vorher
> present); die InMemory-Stores bleiben als Prod-Default drin. (`com.sun.jna` bleibt
> — das ist Clikt/Mordants Terminal-Abhängigkeit, kein testFixtures-Leak; der Audit
> hatte JNA fehlattribuiert.) Coverage: die Contract-Suiten + Port-/`format.data`-
> DTOs (bisher von den InMemory-Tests mitgezählt) sind jetzt in ports-commons kover
> als Contract-Definitionen exkludiert (Logik lebt/testet in den Adaptern). Alle
> betroffenen Module `:check` grün.
>
> **Offen (P3, eigener Fix):** Gradle-Wrapper-`distributionSha256Sum`, Base-Images
> per Digest und Gradle-Dependency-Verification.

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
