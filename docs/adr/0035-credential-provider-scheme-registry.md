---
status: accepted
date: 2026-07-16
decision-makers: pt9912
consulted: docs/adr/0034-master-key-architektur-credential-store.md, spec/connection-config-spec.md, spec/lastenheft-d-migrate.md
informed: hexagon/ports-common, adapters/driven/connection-config, adapters/driving/cli, adapters/driving/mcp
---

# Credential-Provider-Scheme-Registry (O4-Naht, LN-025 Slice 2)

> **Status: accepted (2026-07-16).** Konkretisiert die in
> [ADR 0034](0034-master-key-architektur-credential-store.md) beschlossene **O4-Richtung**
> („strukturierte `credentialRef`/`providerRef`-Delegation, gemeinsame Port-Naht statt if/else,
> Vault-Zukunft") in eine bauliche Entscheidung: eine **scheme-basierte Provider-Registry**, die
> CLI **und** MCP teilen, mit `file:` als erstem Nicht-`env:`-Provider. `keychain:` bleibt (wie in
> ADR 0034 D4) zurückgestellt und wandert als Folge-Slice nach
> [`../planning/next/`](../planning/next/).

## Kontext und Problemstellung

[ADR 0034](0034-master-key-architektur-credential-store.md) hat O4 als Richtung fixiert; der
konkrete Zuschnitt der Naht blieb offen. Verifizierter Code-Ist-Stand (Explore, 2026-07-16):

1. **Der aufgelöste Credential-Wert ist eine vollständige Connect-URL.** Der MCP-Pfad ruft
   `ConnectionSecretResolver.resolve(...)` → `ResolvedConnection.Success(url)` → `ConnectionUrlParser.parse(url)`
   → `HikariConnectionPoolFactory`. `EnvConnectionSecretResolver` liefert die ganze URL aus der
   Env-Variable (`urlFromEnv`, Default Identity).
2. **Die MCP-Map-Form trägt keine Verbindungs-URL.** `ConnectionReference.resourceUri` ist eine reine
   MCP-Adressierungs-URI (`dmigrate://tenants/…/connections/id`, aus dem Map-Key synthetisiert). Die
   URL kommt **ausschließlich** aus der `credentialRef`-Auflösung.
3. **`providerRef` ist inert** — geparst und in Projektionen gestrippt, aber von **keinem** Resolver
   gelesen. Greenfield.
4. **Keine Scheme-Registry existiert.** `EnvConnectionSecretResolver` ist eine flache Einzel-Impl mit
   hartkodiertem `env:`-Prefix, verdrahtet nur im MCP-Serve-Pfad. Der CLI-Pfad
   (`NamedConnectionResolver`) ruft den Port **nie** und ignoriert Map-Form-Einträge.

Frage dieser ADR: Wie wird die O4-Naht so geschnitten, dass CLI und MCP dieselbe
Credential-Auflösung teilen und weitere Provider (ab `file:`) pluggbar werden, **ohne** die
MCP-spezifische Principal-/`ConnectionReference`-Maschinerie dem CLI-Operator aufzuzwingen?

## Entscheidungsoptionen

- **A — Geteilte, principal-freie Provider-Registry.** Ein neuer Port `CredentialProvider`
  (Scheme → Auflösung) plus `CredentialProviderRegistry` (Dispatch am Scheme-Prefix) in
  `hexagon/ports-common`. `EnvConnectionSecretResolver` wird auf die Registry refaktoriert; der
  MCP-Wrapper behält Principal-Authz. Die CLI ruft die Registry **direkt** (kein Principal).
- **B — CLI-only-Dispatch.** Eigener Scheme-Dispatch nur auf dem CLI-Pfad; MCP bleibt unberührt.
  Der `env:`-Scheme existiert dann doppelt (CLI + MCP).

## Entscheidung

**Option A (geteilte Registry), Dispatch am `credentialRef`-Scheme, Provider liefert die volle URL,
`file:` als erster Nicht-`env:`-Provider.**

### D1 — Geteilte Provider-Registry (principal-frei)
`CredentialProvider` + `CredentialProviderRegistry` + `CredentialResolution` (`Success(url)` /
`Failure(reason, detail)`, reason-Codes geteilt mit `ResolvedConnection`) leben in
`hexagon/ports-common`. Die Registry ist **principal-frei** — der reusable Teil ist reine
Scheme→URL-Auflösung. `EnvConnectionSecretResolver` wird zu einem dünnen MCP-Wrapper
(`ProviderBackedConnectionSecretResolver`), der Principal-Authz + null-`credentialRef` behandelt und
die Auflösung an die Registry delegiert; für `env:` bleibt das MCP-Verhalten **unverändert**. Damit
teilen CLI und MCP genau einen Provider-Layer (vermeidet die `env:`-Duplikation von Option B) und der
CLI-Pfad braucht **keinen** synthetischen Principal.

### D2 — Dispatch am `credentialRef`-Scheme
Die Registry wählt den Provider am Scheme-Prefix (`env:`, `file:`, …) — konsistent mit dem heutigen
`env:`. **`providerRef` bleibt ein optionaler Backend-Selektor** (dokumentiert, für spätere
Provider mit eigener Konfiguration wie Vault) und ist in diesem Slice **nicht** dispatch-relevant —
keine tote Semantik wird behauptet.

### D3 — Provider liefert die volle URL
Ein Provider löst seinen `credentialRef` zu einer **vollständigen Connect-URL** auf (World-B-Parität) —
uniform über `env:`/`file:`. Ein `url`+Passwort-Fill-Modell (Basis-URL sichtbar, nur Passwort extern)
ist eine **spätere** Ergänzung; es würde den Provider-Vertrag von „→ URL" auf „→ Secret" ändern und
bleibt bewusst außen vor.

### D4 — `file:` zuerst, `keychain:` als Folge-Slice
`FileCredentialProvider` (`file:/pfad`, Datei-Inhalt getrimmt = URL): cross-platform, headless-tauglich
(CI/Server/k8s-Secret-Mounts), kleine Security-Fläche. Beweist die Registry-Naht mit minimalem Risiko.
`keychain:` (macOS/Windows/Linux, plattformspezifisch + Headless-Fallback nötig, s. ADR 0034 D4) baut
später auf **derselben** fertigen Registry auf → eigener Slice in [`../planning/next/`](../planning/next/).

### D5 — Fail-closed bei explizitem Ref
Ein **explizit** gesetzter, aber unauflösbarer `credentialRef` (fehlende Datei, unbekanntes Scheme,
nicht gesetzte Env-Variable) führt zu einem **Fehler**, nicht zu stillem Degrade — analog zum
World-B-fail-closed-Vertrag. Das unterscheidet sich bewusst von den **fail-open** Stufe-2/4-Fillern
([`LN-049`](../../spec/lastenheft-d-migrate.md#ln-049)), die nur ein *fehlendes* Passwort additiv
ergänzen und passwortlose Auth (PG `peer`/`trust`/`.pgpass`) erhalten müssen.

## Konsequenzen

- **Spec-Folgeänderung:** `connection-config-spec.md` beschreibt die CLI-Map-Form
  (`{ credentialRef, providerRef? }`) und den `file:`-Provider als Zielbild (kein Status, keine
  ADR-Abwärts-Referenz).
- **MCP-Blast-Radius:** `EnvConnectionSecretResolver` wird ersetzt — `:adapters:driving:mcp:check` +
  Regressionstests müssen den Verhaltens-Erhalt für `env:` beweisen (inkl. Principal-Authz,
  Discovery-Trennung: Store-/Ref-Inhalte lecken nicht nach `resources/list`/`*_list`).
- **Security-Review vor Merge (Pflicht):** `file:`-Provider — Symlink-/Path-Traversal-Abwehr,
  Permission-Politik (world-readable), kein Datei-Inhalt in Logs/Exceptions; Masking der neuen Felder.
- **Positiv:** Keine neuen Abhängigkeiten (`file:` = JDK-I/O); der Zukunftspfad
  (`keychain:`/`vault:` via `providerRef`) hängt an einer getesteten Naht statt an einem Retrofit.

## Security-Review-Ergebnis (2026-07-16, 3-Agenten-adversarial)

Kein fail-open, Authz-Ordering byte-identisch zum ersetzten `EnvConnectionSecretResolver`,
Discovery-Trennung intakt, keine Secret-Leaks in `detail`/Logs. **Angewandte Härtungen:**

- **URL-Leak (MED, downstream)**: `ConnectionUrlParser` hängte bei `URISyntaxException` die **rohe**
  URL via `e.message` unmaskiert an — jetzt nur `e.reason`+`e.index` (die maskierte URL bleibt). Betraf
  auch `env:`/Inline-URLs; `file:` erhöhte die Reachability (Datei-Inhalt eher malformed).
- **`file:` Size-Cap (1 MiB)** — verhindert uncaught `OutOfMemoryError` bei versehentlich
  referenzierten Riesen-Dateien; sauberes fail-closed `Failure` statt Prozess-Crash.
- **`file:` BOM-Strip** — führendes UTF-8-BOM (Windows-Editoren) wird entfernt (kein Whitespace,
  überlebte `trim()`).
- **Reason-Vokabular** — `ResolvedConnection` ist Single Source of Truth für die geteilten
  `REASON_*`-Codes; `CredentialResolution` referenziert sie (kein String-Drift). Die `file:`-Codes
  sind als offenes Vokabular dokumentiert.
- **Registry-Prefix-Guard** — kein Scheme darf Prefix eines anderen sein (macht die
  Reihenfolge-Unabhängigkeit des Dispatch erzwingbar).

**Bewusste Deferrals (Review-bestätigt, keine RC-Blocker):**

- **File-Permissions NICHT erzwungen.** Ein Refuse-on-world-readable würde den Flaggschiff-Fall
  brechen: k8s-Secret-/ConfigMap-Mounts sind tmpfs, mode **0644** (world-readable). POSIX-Perm-APIs
  werfen zudem auf Nicht-POSIX-FS (Windows) — ein naiver Check widerspricht dem Cross-Platform-Ziel.
  Die Vertraulichkeit ist ohnehin durch die operator-gesetzten Datei-Rechte begrenzt; d-migrate *liest*
  nur. Höchstens ein optionaler advisory WARN wäre denkbar — für RC weggelassen.
- **Symlinks werden gefolgt (kein `NOFOLLOW_LINKS`).** k8s-projizierte Secret-Files **sind** Symlinks
  (`<key>` → `..data/<key>`); `NOFOLLOW_LINKS` würde genau den Zielfall brechen. Die TOCTOU-Lücke
  gewährt einem lokalen Angreifer, der den Pfad schon schreiben kann, **kein** zusätzliches Privileg.

## Verworfene Alternativen

- **Option B (CLI-only-Dispatch)** — verworfen: dupliziert den `env:`-Scheme und lässt CLI und MCP
  auseinanderlaufen; widerspricht der ADR-0034-Absicht „gemeinsame Port-Naht".
- **`keychain:` in diesem Slice** — zurückgestellt (ADR 0034 D4): plattformspezifische Native-/CLI-
  Integration + Headless-Fallback lohnen den RC-Aufwand nicht; baut später auf dieser Registry auf.
- **`url`+Passwort-Fill-Provider-Vertrag** — vertagt: ändert den Provider-Vertrag (→ Secret statt
  URL); World-B-Parität (volle URL) hält den Vertrag uniform.
