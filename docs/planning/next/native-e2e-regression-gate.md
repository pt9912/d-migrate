# Native-E2E-Regressionsgate

- **Status**: Draft mit Scope (nach `next/` 2026-07-20). Der Hebel ist gebaut und hat sich bewiesen;
  offen ist die **CI-Verdrahtung**. Aktiv erst beim ersten Implementierungs-Commit.
- **Trigger**: Beim Härten des GraalVM-Native-Binaries
  ([graalvm-native-image-distribution](../done/graalvm-native-image-distribution.md), Phase F.4)
  fielen **zwei native Defekte auf, die der Sondenlauf NICHT fand**: `mcp serve` antwortete mit einem
  leeren Fehlerobjekt (`ec…`-Vorlauf: fehlende lsp4j-/DTO-Konstruktor-Registrierung) und die
  S3-Artefaktablage brach bei der ersten echten Operation ab (`--enable-url-protocols=http,https`
  fehlte, `fix(native)` `ec9fa2fd`). Beide wurden erst durch **`test/e2e-cli` gegen das Native-Binary**
  sichtbar — und beide gaben Exit 0 bzw. einen grünen Build zurück, hätten also jede reine
  Exit-Code-/Build-Prüfung passiert.

## Ausgangslage (Stand 2026-07-20)

- **Der Hebel existiert und ist bewiesen** (`6dc4e916`):
  - `test/e2e-cli`-`RealCliSubprocess` startet bei gesetztem `DMIGRATE_CLI_BIN` das Native-Binary statt
    einer Kind-JVM — dieselben Tests, anderes Artefakt.
  - `scripts/test-integration-docker.sh` mountet die Binärdatei in den Container und reicht die Variable
    durch (`-v … :/native/d-migrate:ro`).
  - Manuell verifiziert: `McpS3SubprocessE2ETest` läuft damit den vollen S3-Round-Trip gegen echtes
    SeaweedFS.
- **Es fährt ihn aber KEIN Workflow automatisch.** `native-image.yml` smoked nur die 17 Sonden aus
  `scripts/native-probe.sh` (Konstruktion + Handshake). Die E2E-Suite bleibt außen vor — genau die
  Schicht, die die beiden gefundenen Defekte aufdeckte.

## Wo läuft es — beide Seiten sind schon Docker

Die E2E-Suite braucht **Docker** (Testcontainers: SeaweedFS, PostgreSQL, MySQL). Daraus folgt zunächst:
**macOS/Windows scheiden aus** — die nativen Runner können keine Linux-Container fahren (mehrfach beim
3-OS-Bau belegt). Ein Native-E2E-Gate ist damit **Linux-only**, wie schon die tiefe Testmatrix.

Auf Linux ist die Verdrahtung aber **kein Zusammenführen zweier Welten** — beide Seiten laufen bereits
über make+docker:

- **Binary-Bau**: `make native-build` baut es über `docker/native-image.Dockerfile` (GraalVM im Image).
  Das ist der lokale Weg; er läuft auf jedem Runner mit Docker.
- **E2E-Harness**: `scripts/test-integration-docker.sh` fährt die Suite im Container und reicht per
  `DMIGRATE_CLI_BIN` das Binary durch (bereits gebaut, `6dc4e916`).

**Klarstellung zum heutigen CI-Stand**: `native-image.yml` baut auf **allen** OS per
`setup-graalvm` + Gradle-Wrapper statt über Docker. Das ist für **macOS/Windows zwingend** (kein
Linux-Container, native-image cross-kompiliert nicht), auf **Linux aber nur der geerbte Zustand** —
dort brächte `make native-build` denselben konventionskonformen Docker-Weg wie lokal. Ob das
Native-E2E-Gate in `native-image.yml`s Linux-Leg eingeklinkt oder als eigener make/docker-Schritt
gefahren wird, ist eine der offenen Entscheidungen — in beiden Fällen bleibt es make+docker.

## Offene Entscheidungen (vor dem Bau)

1. **Wo verdrahten?** Optionen:
   - **Eigenes make-Target** (z. B. `native-e2e`), das `native-build` + die Subprozess-Suite via
     `DMIGRATE_CLI_BIN` kettet — konventionskonform, lokal wie in CI identisch aufrufbar. Der Workflow
     ruft dann nur das Target.
   - **In `native-image.yml`s Linux-Leg** als nachgelagerter Step. Nah am Binary, aber der Workflow ist
     heute dispatch-/tag-getriggert, kein PR-Gate.
   - Trigger: tag (Release-Gate) und/oder dispatch (Schleife).
2. **Welche Teilmenge?** Die Subprozess-E2Es (`RealCliSubprocess`-basiert) sind der Kern —
   heute `McpRealCliSubprocessTest`, `McpS3SubprocessE2ETest` und die MCP-Szenarien. Die
   in-process-E2Es (`DMigrate()` direkt) bringen nichts gegen das Binary und bleiben JVM. Eine
   Tag-/Filter-Konvention muss die Subprozess-Klassen sauber selektieren.
3. **Bauzeit-Budget.** Das Native-Binary ist ~190 MB und braucht auf Linux ~5 min; die E2E-Suite
   selbst ~2–3 min. Als Release-Gate vertretbar, als PR-Gate zu schwer (dieselbe Begründung, aus der
   `native-image.yml` heute kein PR-Gate ist).

## Akzeptanzkriterien

- Ein CI-Pfad baut das Linux-Native-Binary und fährt die **Subprozess-E2E-Suite** dagegen (mindestens
  `McpS3SubprocessE2ETest` + `McpRealCliSubprocessTest`), mit laufenden Testcontainers.
- Ein regressierter Defekt der heute behobenen Klasse (nicht registrierte Reflection/Serialisierung,
  fehlendes `--enable-url-protocols`) lässt das Gate **rot** werden — nicht nur den Sondenlauf grün.
- Die JVM-Läufe derselben Suite bleiben unverändert der Default (ohne `DMIGRATE_CLI_BIN`).
- `docs/user/releasing.md` nennt das Gate, falls es Release-getriggert ist.

## Abgrenzung

- **Keine neue Testsuite.** Es geht ausschließlich um die Verdrahtung der VORHANDENEN Subprozess-E2Es
  gegen das Binary. Neue Testfälle sind nicht Teil dieses Slices.
- **Kein PR-Gate.** Zu schwer für jeden PR (s. Bauzeit); Release-/Dispatch-getriggert wie der
  Native-Bau selbst.
- **macOS/Windows-E2E** bleibt außen vor (kein Linux-Container möglich). Deren Abdeckung endet beim
  Sondenlauf aus `native-probe.sh` — bewusst.
