# ImpPlan 1.0.0-RC — LN-025 Slice 2: Credential-Provider-Naht (O4)

> Status: Done (2026-07-16) — AP0–AP6 geliefert; 3-Agenten-Security-Review + Härtungen; full-build
> + docs-check grün. Siehe [Closure](#closure).

**Quelle**: [ADR 0034](../../adr/0034-master-key-architektur-credential-store.md) O4-Staffel
(„`credentialRef`/`providerRef`-Delegation auf den CLI-Pfad, World-B-Modell ausdehnen") +
[`../next/config-cli-management-surface.md`](../next/config-cli-management-surface.md) §4.4.
Slice 1 (O2-Store) ist done ([`../done/ImpPlan-1.0.0-RC-ln025-slice1-credential-store.md`](../done/ImpPlan-1.0.0-RC-ln025-slice1-credential-store.md)).

**Kein Lastenheft-Gap.** [`LN-049`](../../../spec/lastenheft-d-migrate.md#ln-049) (Credential-Auflösung)
ist ✅ — Env-Secrets kann die CLI via `${VAR}`/`D_MIGRATE_DB_PASSWORD`/Store bereits. O4 ist die von
ADR 0034 gewollte **strukturierte Erweiterbarkeits-Naht**: Credential-Auflösung über einen Provider-Port
statt ad-hoc-if/else, damit Nicht-env-Provider pluggbar werden (erster: `file:`; `keychain:` Folge-Slice).

## Geklärtes Modell (Explore-verifiziert 2026-07-16)

- Die MCP-**Map-Form trägt keine URL**: `resourceUri` ist eine reine MCP-Adressierungs-URI
  (`dmigrate://tenants/…/connections/id`, aus dem Map-Key synthetisiert). Die Connect-URL kommt
  **ausschließlich** aus der `credentialRef`-Auflösung.
- **Der aufgelöste Wert ist eine vollständige Connect-URL** → `ConnectionUrlParser.parse(url)` →
  `HikariConnectionPoolFactory`. World B (`env:VAR`) liefert die ganze URL aus der Env-Variable.
- **`providerRef` ist inert** (geparst, nie gelesen) — Greenfield. Dispatch hängt am
  `credentialRef`-**Scheme**, konsistent mit dem heutigen `env:`.
- **Keine Scheme-Registry existiert**; `EnvConnectionSecretResolver` ist eine flache Einzel-Impl,
  verdrahtet an `McpServeWiring:194/256`. Der CLI-Pfad (`NamedConnectionResolver`) ruft den Port
  **gar nicht** und ignoriert Map-Form-Einträge.

## Entscheidungen (User-Review 2026-07-16)

- **E1 — Reuse-Tiefe: geteilte Registry.** `CredentialProviderRegistry` (Scheme → `CredentialProvider`)
  in `hexagon/ports-common`; `EnvConnectionSecretResolver` wird auf die Registry refaktoriert. MCP + CLI
  teilen den Provider-Layer. *(gewählt)*
- **Erster Provider: `file:` jetzt, `keychain:` als Folge-Slice** (`../next/`). *(gewählt)*
- **E2 — Dispatch am `credentialRef`-Scheme** (`env:`/`file:`/…); `providerRef` bleibt optionaler
  Backend-Selektor (dokumentiert, für Vault-Zukunft), in diesem Slice nicht dispatch-relevant.
- **E3 — Leichtgewichtige CLI-Map-Form** (`{ credentialRef, providerRef? }`) statt die schwere
  MCP-`ConnectionReference` (displayName/dialectId/sensitivity/tenant) dem CLI-Operator aufzuzwingen.
- **E4 — ADR-Inkrement** (0034 erweitern) für Registry + Provider-Vertrag + Headless-Semantik.
- **E5 — Security-Review** am Ende (Pflicht, multi-agent adversarial wie Slice-1-AP7).
- **E6 — CLI-`credentialRef` liefert die volle URL** (World-B-Parität), **kein** `url`+Passwort-Fill
  in diesem Slice. *(gewählt)* Hält den Registry-Vertrag uniform (Provider → URL-String).
  `url`+Passwort-Fill-Ergonomie = dokumentierte Zukunfts-Erweiterung.

**Principal-Auflösung (Design-Konsequenz von E1):** Der reusable Teil (`CredentialProviderRegistry`,
Scheme → URL) ist **principal-frei** — die CLI ruft ihn direkt, **kein** synthetischer Principal nötig.
Der Principal-Authz bleibt allein im MCP-`ConnectionSecretResolver`-Wrapper.

**Fail-closed:** Ein **explizit** gesetzter, aber unauflösbarer `credentialRef` → Fehler (kein
Silent-Degrade), analog World B. Das unterscheidet sich bewusst von den fail-open Stufe-2/4-Fillern
(die nur ein *fehlendes* Passwort additiv ergänzen).

## Architektur-Skizze

```
hexagon/ports-common (dev.dmigrate.server.ports)
  CredentialProvider           # fun scheme(): String; fun resolve(credentialRef): CredentialResolution
  CredentialProviderRegistry   # dispatch by scheme-prefix; principal-frei
  CredentialResolution         # Success(url) | Failure(reason, detail)   (reason-Codes s. ResolvedConnection)

adapters/driven/connection-config (dev.dmigrate.connection)
  EnvCredentialProvider        # "env:"  → envLookup (aus EnvConnectionSecretResolver extrahiert)
  FileCredentialProvider       # "file:" → Datei-Inhalt als URL (NEU)
  ProviderBackedConnectionSecretResolver  # MCP-Wrapper: Principal-Authz + null-ref + Registry-Delegation
                                          # (ersetzt EnvConnectionSecretResolver, verhaltensgleich für env:)

CLI: NamedConnectionResolver (o. Parallel-Resolver) liest Map-Form { credentialRef, providerRef? } →
     CredentialProviderRegistry.resolve(credentialRef) → volle URL → ConnectionUrlParser.parse → Pool
     (fail-closed bei explizitem, unauflösbarem Ref)
```

## AP-Struktur

- **AP0 — ADR + Spec-Vertrag.** ADR 0034 um „Provider-Scheme-Registry" erweitern (oder neues ADR,
  deutsch): Registry-Kontrakt, Dispatch am Scheme, `file:`-Provider-Vertrag (liefert volle URL),
  `providerRef`-Zukunftsrolle, Headless-Semantik, Fail-closed. CLI-Map-Form + `file:` in
  `connection-config-spec.md` als Zielbild (kein Status).
- **AP1 — Registry-Port (ports-common).** `CredentialProvider`, `CredentialProviderRegistry`,
  `CredentialResolution` (+ reason-Codes wiederverwenden/teilen). Unit-Tests: Dispatch, unbekanntes
  Scheme → Failure(PROVIDER_MISSING), leerer Ref.
- **AP2 — env in Registry refaktoriert.** `EnvCredentialProvider` (aus `EnvConnectionSecretResolver`
  extrahiert); `ProviderBackedConnectionSecretResolver` = Principal-Authz + null-ref + Registry.
  MCP-Verhalten für `env:` **unverändert** (Regressionstests grün). `EnvConnectionSecretResolver`
  wird ersetzt/umbenannt (Aufrufer `McpServeWiring:194/256` mitziehen).
- **AP3 — `FileCredentialProvider` (NEU).** Scheme `file:`; liest reguläre Datei, Inhalt getrimmt =
  URL. Security: nur reguläre Datei, Symlink-/Traversal-Abwehr, Permission-Prüfung (world-readable →
  laut/refuse — in AP5-Review final), kein Datei-Inhalt in Logs/Exceptions, operator-taugliche
  secret-freie Fehler. Tests: happy, fehlt, kein-File, leer, Permissions, Symlink.
- **AP4 — CLI World-A-Anschluss.** CLI liest leichtgewichtige Map-Form (`{ credentialRef, providerRef? }`);
  Auflösung via Registry → volle URL. Fail-closed bei explizitem, unauflösbarem Ref. Einhängen am
  `NamedConnectionResolver`/[`LN-049`](../../../spec/lastenheft-d-migrate.md#ln-049)-Seam für alle
  relevanten `--source`/`--target`-Ops. Masking der
  neuen Felder (`config show`/Logs). Tests: URL-aus-file, fail-closed, Präzedenz, Trennungs-Invariante
  (Map-Form-Secret leckt nicht in MCP-Discovery — Regressionstest).
- **AP5 — Security-Review + Doku-Abschluss.** Multi-agent adversarial (file: Pfad-Traversal/Symlink/
  Permissions, kein Secret-Leak, Masking); Härtungen einarbeiten. connection-config-spec/cli-spec auf
  realen Stand; CHANGELOG. `make docs-check` + Full-Build.
- **AP6 — keychain-Folge-Ticket** in `../next/` (nicht gebaut): `keychain:` auf der fertigen Registry,
  macOS/Windows/Linux-Mechanismen + Headless-Fallback.

## Tests / Coverage (Ziel ≥90%/Modul)

Registry-Dispatch · `EnvCredentialProvider` (Refactor-Parität) · `FileCredentialProvider`
(happy/fehlt/leer/Permissions/Symlink) · `ProviderBackedConnectionSecretResolver` (Principal-Authz
unverändert, env+file) · CLI-Map-Form-Auflösung (URL-aus-file, fail-closed, Präzedenz) ·
MCP-Discovery-Trennungs-Regressionstest · Masking neuer Felder.

## DoD

- CLI `--source <map-form-name>` mit `credentialRef: file:/pfad` verbindet über die Registry
  (Datei-Inhalt = URL); `env:` weiter identisch auf CLI **und** MCP.
- Explizit gesetzter, unauflösbarer `credentialRef` → Fehler (fail-closed), nicht Silent-Degrade.
- `providerRef` dokumentiert als Backend-Selektor (Zukunft), keine tote Semantik behauptet.
- Kein Secret in Logs/Exceptions/`config show`; MCP-Discovery-Trennung gewahrt.
- Security-Review ohne offene ausnutzbare Lücke; koverVerify ≥90% je berührtem Modul; docs-check +
  Full-Build grün. keychain-Folge-Ticket in `../next/`.

## Risiken / offene Review-Punkte

- **E6** (CLI-`credentialRef` = volle URL vs. `url`+Passwort-Fill) — Empfehlung volle URL; falls
  Passwort-Fill gewünscht, ändert das den Provider-Vertrag (Provider → Secret statt URL) und AP4.
- **Refactor-Blast-Radius MCP** (AP2): `EnvConnectionSecretResolver` ersetzen berührt den MCP-Pfad —
  Regressionstests + `:adapters:driving:mcp:check` müssen den Verhaltens-Erhalt für `env:` beweisen.
- **`file:`-Permission-Politik** (refuse vs. warn bei world-readable) — final im Security-Review (AP5).

## Closure

Geliefert wie geplant (E1–E6 unverändert; keychain → `../next/`).

- **AP0** ADR 0035 + ImpPlan. **AP1** `CredentialProvider`/`CredentialProviderRegistry`/
  `CredentialResolution` (ports-common). **AP2** `EnvCredentialProvider` +
  `ProviderBackedConnectionSecretResolver` (ersetzt `EnvConnectionSecretResolver` verhaltenserhaltend;
  `McpServeWiring` × 2 mitgezogen). **AP3** `FileCredentialProvider` (Datei-Inhalt = URL). **AP4** CLI
  World-A-Anschluss: `NamedConnectionResolver` löst Map-Form-`credentialRef` via `defaultCredentialProviderRegistry()`
  auf (fail-closed, keine `${VAR}`-Substitution); `ConnectionConfigParser.parseMapFormCredentialRef`.
  **AP6** keychain-Folge-Ticket `../next/credential-provider-keychain.md`.
- **AP5 Security-Review** (3 Agenten adversarial): kein fail-open, Authz-Ordering byte-identisch,
  Discovery-Trennung intakt. **Härtungen eingearbeitet:** (1) MED — `ConnectionUrlParser` re-appendete
  bei `URISyntaxException` die rohe URL via `e.message` → jetzt `e.reason`+`e.index` (maskiert);
  (2) `file:` 1-MiB-Size-Cap (kein uncaught OOM) + BOM-Strip; (3) `ResolvedConnection` = Single-Source
  fürs geteilte Reason-Vokabular, `CredentialResolution` referenziert es; (4) Registry-Prefix-Shadowing-
  Guard. **Bewusste Deferrals** (ADR 0035): File-Permissions nicht erzwungen + Symlinks gefolgt
  (k8s-Secret-Mounts world-readable + symlinked).
- **Verifikation:** ports-common/connection-config/cli/mcp/driver-common `:check` grün + Full-Build
  (`build koverVerify --no-build-cache`, alle Module) + `docs-check` grün. E6 (volle URL) wie empfohlen;
  MCP-Blast-Radius durch `:mcp:check`-Regression abgedeckt.
