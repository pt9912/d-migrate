# ImpPlan LN-049 — Credential-Auflösung aus der Quellen-Priorität (inkl. Store-Konsum)

**Status**: **REVIEWBARER PLAN (next/) — Rev. 3 (zwei Review-Runden eingearbeitet, 2026-07-14)**. Noch nicht
in Umsetzung. Folge-Schnitt zu [`LN-025`](../../../spec/lastenheft-d-migrate.md#ln-025) Slice 1 (Store
gebaut, aber **nicht** konsumiert) und die „O4-Naht" aus [ADR 0034](../../adr/0034-master-key-architektur-credential-store.md).

> **Rev. 2** (1. Review-Runde, 2 Blocker/4 Majors): Connection-**Name** geht am Hook-Punkt verloren (B1);
> **~14** verbindungsöffnende Stellen, nicht 8 (B2); Runner liegen in `hexagon/application` und werden von
> **MCP mitbenutzt** (M3); Store-/Master-Fehler → **Exit 7** nicht 4 (M4); Master-Secret pro Verbindung neu
> entschlüsselt (M5); `password == null` verfehlt Leer-Passwörter (M6).
>
> **Rev. 3** (2. Review-Runde, 2 Agenten): Rev.2-Fixes code-verifiziert als echt gelöst; `--parallel`-Race
> **widerlegt** (Pools einmal vorab, Main-Thread). Neu eingearbeitet: **🔴 Dialekt-Gate** — SQLite/no-auth
> (`user=null/password=null`) darf **keinen** Fill/Prompt/Exit-7 auslösen (D-6); **Umbenennung**
> `ResolvedConnection`→`ResolvedConnectionRef` (Namenskollision mit MCP-Typ in ports-common, D-1/AP0);
> **AP0 = Signatur-Migration** inkl. 2 MCP-Worker + ~30 Testdateien, nicht nur `.url`-Entpackung (AP-A2.0);
> **`CredentialFillSession`** als expliziter Owning-Scope + **Copy-on-read** (Store wiped die Kopie je
> Entschlüsselung) + Probe-Typealias-Aufweitung (D-5/AP-A2.3); Integrationstest-Netz greift nur unter
> `-PintegrationTests` → **per-Site-Unit-Asserts** nötig (AP-A1.4/A2.4).

## Fortschritt

- **A1-Stufe 2 KOMPLETT über alle CLI-DB-Ops geliefert** (`EnvCredentialFiller`): `data export`/`import`/
  `transfer`/`profile` **und** `schema reverse`/`migrate`/`compare`/`rollback` (inkl. der 4 auth-relevanten
  Preflight-/Sequenz-Probes) ergänzen ein fehlendes DB-Passwort aus `D_MIGRATE_DB_PASSWORD` — **additiv,
  dialekt-gegatet, kein fail-closed**. Realisiert als `EnvCredentialFiller().fill(parse(url))` bzw.
  `fillingParser` an den 12 auth-relevanten Sites; die 2 SQLite-only-Probes brauchen keinen Fill
  (Dialekt-Gate no-op); MCP unverändert (nackter Parser → kein Fill). cli test/detekt/koverVerify grün.
- **Implementierungs-Befund (Plan-Korrektur):** die ursprüngliche „Stufe 5 non-TTY → fail-closed Exit 7"
  (D-4) würde **passwortlose Auth** (Postgres `peer`/`trust`/`.pgpass`) brechen → **Stufe 5 (Prompt)
  verschoben** auf ein nicht-regressierendes Design (connect-then-prompt / Opt-in). A1 ist damit **rein
  additiv** (nur Stufe 2).
- **A1 (Stufe 2) vollständig** über die CLI-DB-Ops.
- **A2 (Stufe 4 Store-Konsum) — leichter Schnitt begonnen** (User-Entscheidung: statt der 30-Datei-
  Namens-Migration die **Wiring-kennt-Namen**-Variante für Single-Connection-Ops): Kern
  `CredentialFillSession` (Master-Secret-Cache, **copy-on-read**, `wipe`) + `StoreCredentialFiller`
  (Stufe 4 nach Env, additiv, dialekt-gegatet, kein fail-closed, secret-freie Diagnose, User-Feld D-9) +
  `CredentialFilling` (baut `parse→Env→Store` aus `rawSource`; Store nur bei Name, kein `://`; `fill(url)`
  für inline-parsende Wirings, `storeOnTop(name, base)` für Bundle-Seams). **Verdrahtet:** `data export`
  (`request.source`), `data import` (`options.target` via `storeOnTop` über `bundle.urlParser`), `schema
  compare` (je Operand `op.source`, inline im Exit-7-`try`), **`data transfer`** (Quelle+Ziel via
  additivem `credentialFiller`-Seam auf `DataTransferRunner`/`TransferConnectionResolver`,
  `CredentialFilling.perConnectionStoreFiller` mit **einer** geteilten Session; MCP/Tests = Identity-Default). A2-Kern-Review fand + fixte einen Major-Bug
  (Falsch-Secret → Exit 7 statt Crash, `9a9d6760`). Kern voll unit-getestet inkl. `storeOnTop`.
  **`schema reverse`** (via `storeOnTop` über `bundle.urlParser`, wie import).
  **Offen A2 (invasiv):** `data profile` (opaker `poolFactory: (url,dialect)->pool` → Runner-Connection-Fluss
  restrukturieren, damit ein config-Level-Fill-Punkt entsteht), `schema migrate`/`rollback` (`loadFromDb` +
  ~5 Probe-Runner öffnen **dieselbe** Ziel-Verbindung als bare `::probe`-Refs → **gemeinsam durchgereichte
  Session** nötig, sonst N Master-Secret-Prompts; berührt Probe-Signaturen/Typealiases + Render-Pipeline),
  `default_*`-Resolution. Diese drei decken sich uniform mit der Namens-Migration (`ResolvedConnectionRef`).
  **Bekannte Nachbesserungen:** Session-`wipe`-`finally`-Lifecycle (aktuell GC); Wiring-Tests hängen für
  Name-Quellen latent von `~/.d-migrate` ab (Session injizierbar machen); totes `DataImportWiringBundle.
  urlParser`-Feld (durch `storeOnTop` ersetzt). **Offen:** Stufe 5 (Prompt), Slice B (`credentialRef` O4).

## 1. Ziel, Scope & Staffelung

**Ziel:** [`LN-049`](../../../spec/lastenheft-d-migrate.md#ln-049) — Zugangsdaten aus der definierten
**Quellen-Priorität aufgelöst** (Inline, Env, `${VAR}`, verschlüsselter Store, Prompt, externe
Secret-Referenz), nie Klartext gespeichert/geloggt/in Fehlern (Maskierung). Normativer Vertrag:
[connection-config-spec 4.1](../../../spec/connection-config-spec.md#41-priorität).

**Ist-Zustand (Explore + Plan-Review 2026-07-14, code-verifiziert).** Auf dem CLI-Pfad
(`NamedConnectionResolver`) sind nur Stufe 1 (Inline-URL) + Stufe 3 (`${VAR}`) live. Stufe 2
(`D_MIGRATE_DB_PASSWORD`) existiert **nirgends** im Code, Stufe 4 (Store) ist **gebaut, unkonsumiert**,
Stufe 5 (DB-Prompt) fehlt. `credentialRef`/`providerRef` gibt es nur hinter der MCP-seitigen
`ConnectionSecretResolver`-Naht (principal-autorisiert), **nicht** auf dem CLI-Pfad.

**Passwort-Injektionspunkt:** `ConnectionUrlParser.parse(url)` → `ConnectionConfig(user,password?,host,db)` →
`HikariConnectionPoolFactory.create(config)` (setzt `password`, wenn `!= null`). **Aber:** `ConnectionConfig`
trägt **keinen** Connection-Namen, und `NamedConnectionResolver.resolve` liefert nur die URL (Name geht
verloren, besonders bei `default_source`/`default_target`). Store-Lookup **per Name** braucht daher zuerst
eine Namens-Durchreichung (Abschnitt 2, AP0).

**Staffelung (Review-empfohlen — Komplexität isolieren):**

- **Slice A1 — Stufe 2 (Env) + Stufe 5 (Prompt).** Brauchen **weder** Connection-Name **noch** Master-Secret
  → ein uniformer `password.isNullOrEmpty()`-Fill an **allen** ~14 Stellen, minimales Risiko. Liefert den
  globalen `D_MIGRATE_DB_PASSWORD`-Fallback und den interaktiven DB-Prompt.
- **Slice A2 — Stufe 4 (Store-Konsum).** Braucht die **Namens-Durchreichung** (B1), Master-Secret-Handling
  (M5) und die Store-Key-Semantik (D-2). Landet den Refactor bewusst separat. **Schließt die
  Speicher↔Konsum-Lücke** (gespeicherte Zugangsdaten werden nutzbar) — der eigentliche Auslöser dieses Slices.
- **Slice B — externe Secret-Referenz (O4).** `credentialRef`/`providerRef` auf dem CLI-`--source`-Pfad
  (World-B-Modell ausdehnen, gemeinsame `ConnectionSecretResolver`-Naht, Vault-Zukunft). Erst danach ist
  die Anforderung vollständig → roadmap 🚧→✅ **am Ende von Slice B**.

> **Offene Staffelungs-Entscheidung (User):** A1→A2→B (Review-Empfehlung, de-riskt) **oder** A2 zuerst
> (Store-Konsum = der Kernnutzen, den der Store-Bau motiviert hat) mit 2+5 im selben Schnitt. Abschnitt 6.

**Nicht in Scope:** Store-Bau/`set`/`list` (Slice 1 fertig); `config show`; MCP-Pfad-Änderungen (die Naht
existiert dort; Slice B spiegelt sie).

## 2. Architektur (review-korrigiert)

**Eine identitäts-defaultete Fill-Naht, durch die geteilten Runner + Resolver + Probe-Runner gefädelt** —
nicht ein einzelner Punkt, weil (a) der Name am Parse-Punkt fehlt und (b) ~14 Stellen unabhängig
resolve→parse→create ausführen.

- **AP0 Namens-Durchreichung:** `NamedConnectionResolver` liefert statt `String` ein
  **`ResolvedConnectionRef(url, name?)`** (Name **nicht** `ResolvedConnection` — der ist in
  `hexagon/ports-common` bereits als MCP-Secret-Ergebnis vergeben, M-1). Der Typ lebt in
  `ports-common`/`application` (nicht cli), sonst bricht die Hexagon-/`a-check`-Regel. **Blast-Radius
  (M-2, ehrlich):** eine **Signatur-Migration**, keine `.url`-Entpackung — die injizierten
  Resolver-Funktionstypen der geteilten Runner ändern sich und berühren damit auch die **2
  MCP-Worker-Konstruktionen** (`DataRunnerWorkers`) und **~30 Testdateien** (Runner-`sourceResolver`-
  Lambdas + ~40 `NamedConnectionResolverTest`-Assertions `shouldBe "<url>"` → `.url`). Via `make ast-grep`
  + docker-check-Netz.
- **Fill-Seam:** `credentialFiller: (ConnectionConfig, ref: ResolvedRef?) -> ConnectionConfig = { c, _ -> c }`
  (Identity-Default) auf den **geteilten** Runnern `DataExportRunner`/`DataImportRunner`/`DataTransferRunner`
  (`hexagon/application`) **und** ihrem `TransferConnectionResolver` (Quelle/Ziel je einzeln geparst+gepoolt)
  **und** den 6 Probe/Executor-Runnern. **CLI** injiziert den echten Filler; **MCP** lässt den Default
  (Identity) → kein Fill auf dem MCP-Pfad (D-7).
- **Resolve-once-pass-down:** innerhalb eines `schema migrate --execute`/`rollback`-Laufs die gefüllte
  `ConnectionConfig` (bzw. einen Pool-Provider) **einmal** bilden und an die Probes durchreichen, statt
  jeden Probe erneut füllen/entschlüsseln zu lassen (M5/B2).
- Der geteilte `ConnectionUrlParser`/`HikariConnectionPoolFactory` bleibt **unangetastet** (D-7 sauber).

**~14 verbindungsöffnende Stellen (B2 — vollständige Liste, AP4):** `DataExportWiring`, `DataImportWiring`
(Ziel!), `DataTransferWiring` (Quelle **und** Ziel via `TransferConnectionResolver`), `DataProfileWiring`,
`SchemaReverseWiring`, `SchemaMigrateWiring.loadFromDb`, `SchemaCompareWiring.loadDatabaseOperand` (je
Operand), `SchemaRollbackWiring`, plus die Probe/Executor-Runner `CheckPreflightProbeRunner`,
`AtomicSequencePreserveRunner`, `JdbcMigrationExecutor`, `MysqlSequenceCanonicityProbeRunner`,
`SqliteCastPreflightProbeRunner`, `SqliteLiveCatalogProbeRunner`.

## 3. Design-Entscheidungen (Rev. 2)

- **D-1 Fill-Seam** identitäts-defaultet auf den geteilten Runnern + `TransferConnectionResolver` +
  Probe-Runnern (nicht „ein CLI-Punkt"; die Runner sind MCP-geteilt → Seam ist der einzige saubere Weg,
  Fill CLI-exklusiv zu halten). MCP = Identity-Default.
- **D-2 Store-Key = Connection-Name** (nur wenn `--source`/`--target` ein Name war, kein Inline-`://`;
  inkl. `default_*`-aufgelöster Name via AP0). Inline-URLs → kein Store-Lookup. Exakt/case-sensitiv wie
  `database.connections`. **Silent-Miss-Diagnose (m7):** Store initialisiert, Name gesetzt, aber kein
  Eintrag → klare, secret-freie Meldung (`listNames()` ist per Vertrag secret-frei), nicht still auf
  Prompt/Fail durchfallen. URL-abgeleitete Keys/`credentialRef` = Slice B.
- **D-3 Stufe 2 `D_MIGRATE_DB_PASSWORD`:** globaler Fallback, ergänzt fehlendes, überschreibt nichts
  Explizites; Quelle≠Ziel-Grenze dokumentiert (eine Variable kann nicht unterscheiden).
- **D-4 Stufe 5 Prompt:** nur TTY **und** kein anderes Passwort; non-TTY → **fail-closed Exit 7** (kein
  Leer-Passwort).
- **D-5 Master-Secret-Session (M5, M-3):** ein **`CredentialFillSession`** (an der Kommando-Wiring-Wurzel je
  Invocation erzeugt) hält Master-Secret + entschlüsselten Store und wird in **alle** ~14 Stellen (inkl.
  Probes) durchgereicht → **ein** Prompt statt n. **Copy-on-read (Rev.3):** der memoisierende Provider gibt
  je Aufruf `cache.copyOf()` zurück, weil `AesGcmCredentialStore.readEntries` die zurückgegebene Kopie nach
  jeder Entschlüsselung wiped — sonst bekäme der 2. Konsum ein geblanktes Secret. Session am
  **Invocation-Ende** wipen (nicht per-Fill-`finally`, sonst vor Ziel/Probe zerstört). Nur wenn Stufe 4 dran
  ist; non-TTY + `D_MIGRATE_MASTER_PASSWORD` = single-source.
- **D-6 Präzedenz + Dialekt-Gate** (Spec 4.1): explizit (inline/`${VAR}`) > Env(2) > Store(4) > Prompt(5) —
  Fill nur bei fehlendem Passwort **und nur für authentifizierende Dialekte (PG/MySQL)**. **🔴 Dialekt-Gate
  (Rev.3-Blocker):** SQLite (und jeder no-auth-Dialekt) hat immer `user=null/password=null` → **kein** Fill,
  **kein** Prompt, **kein** Exit-7 (sonst bräche jeder non-TTY-`sqlite:///…`-Export/-Transfer, u. a.
  sample-db/e2e-Harness). **Fehlend = `password.isNullOrEmpty()`** (M6): `user:@host`/leer-`${VAR}` bei
  PG/MySQL = fehlend (füllen); `user:pass` unangetastet. Gesetztes-aber-unauflösbares `${VAR}` wirft weiter
  Exit 7 **vor** dem Filler (kein Fallback; m8). **`${VAR}`-Passwort ⊥ Store (m-1):** eine Verbindung mit
  `${DB_PASS}`-Platzhalter im Passwort-Segment würde bei unset `DB_PASS` Exit 7 werfen, **bevor** der Store
  greift — für store-gefüllte Verbindungen also `user@host` (ohne Passwort-Segment) dokumentieren.
- **D-7 MCP-Trennung:** Fill CLI-only (Identity-Default auf MCP). Der Discovery-Pfad bezieht Verbindungen
  secret-frei als `ConnectionReference`, Auflösung nur hinter der principal-autorisierten
  `ConnectionSecretResolver`-Naht. Invariante testgesichert.
- **D-8 Exit-Codes (M4):** Fill in eigenem `try`; Store-/Master-/„kein-Credential"-Fehler → **Exit 7**
  (LOCAL, secret-frei), **nicht** Exit 4 (das bleibt dem echten Treiber-Connect). Tests asserten die Codes.
- **D-9 User-Feld (m9):** hat die URL keinen User (`postgresql://host/db`), setzt der Store-Konsum
  **User + Passwort** aus dem Eintrag; bei User-Mismatch (URL-User ≠ Store-User) definiertes Verhalten
  (URL-User gewinnt, Store nur fürs Passwort) — dokumentiert + getestet.
- **D-10 Masking:** store-/env-/prompt-gefüllte Passwörter nie in Log/Fehler (`ConnectionSecretMasker`
  deckt URLs, `ConnectionConfig.password` maskiert) — Fehlerpfade gegenprüfen; Secret als `CharArray` bis
  zur Hikari-`String`-Grenze halten.

## 4. Arbeitspakete (TDD — je AP rot→grün→docker-check)

**Slice A1 (Stufe 2 + 5, kein Name/Master nötig):**
- **AP-A1.0** `password.isNullOrEmpty()`-Fill-Helfer + Präzedenzlogik (Env(2)→Prompt(5)); Fake-Seams; Tests
  je Reihenfolge, explizit-vorhandenes bleibt.
- **AP-A1.1** Stufe 2 `D_MIGRATE_DB_PASSWORD` (ergänzt-nicht-überschreibt) + `user:@`/leer-`${VAR}`-Fälle.
- **AP-A1.2** Stufe 5 TTY-Prompt; non-TTY → Exit 7 fail-closed.
- **AP-A1.3** identity-defaulteter Seam an **allen ~14 Stellen** (Abschnitt 2) + `TransferConnectionResolver`
  Quelle/Ziel; MCP unverändert (Identity). Wiring-/Runner-Tests je Naht **inkl. MCP-Identity-Pfad via Fake**
  (D-7-Invariante; keine neuen Kover-Excludes; m-2). **SQLite-Akzeptanz:** `data export --source sqlite:///x.db`
  non-TTY → Exit 0, kein Prompt (Dialekt-Gate D-6).
- **AP-A1.4** Exit-Code-Mapping (D-8) + Masking (D-10) + **per-Site-Unit-Asserts je Fill-Naht** (der e2e-Test
  ist `-PintegrationTests`-gated und greift im Normal-Build **nicht**, M-4); zusätzlich e2e env-/prompt-backed
  `data transfer` unter dem Integrations-Profil.

**Slice A2 (Stufe 4 Store-Konsum, braucht den Refactor):**
- **AP-A2.0** `ResolvedConnection(url, name?)` (AP0-Namens-Durchreichung) inkl. `default_*`; Call-Sites via
  `make ast-grep`.
- **AP-A2.1** Store-Konsum an der Fill-Kette (Env→**Store**→Prompt), Store-Key = Name (D-2), Silent-Miss-
  Diagnose, User-Feld (D-9).
- **AP-A2.2** Master-Secret-/Store-Caching pro Invocation (D-5), Wipe in `finally`; Exit-7-Mapping falsches
  Master-Secret.
- **AP-A2.3** `CredentialFillSession` (Owning-Scope je Invocation) + **Probe-Seam als Parameter statt
  statischer Referenz** (Typealiases `CheckPreflightProbeFn` etc. aufweiten, ~7 Migrate/Rollback-Stellen) →
  resolve-once-pass-down, kein Mehrfach-Fill/Prompt (M-3). Eigenes AP, kein Sub-Bullet.
- **AP-A2.4** **Anwenderhandbuch-Aufgabe „Zugangsdaten sicher hinterlegen und nutzen"** (die aus dem Store-Slice (AP6)
  hierher verschobene, jetzt end-to-end erfüllbare Aufgabe) + Recovery/Master-Secret-Hinweis; `make docs-check`.
  Store-backed `schema migrate`/`data transfer` Integrationstest (B2-Schutz).
- **AP-A2.5** Security-Review (Präzedenz, MCP-Trennung, Master-Handling, Masking, non-TTY-fail-closed).

## 5. Akzeptanz

- **A1:** `data export --source postgresql://admin@host/db` ohne Passwort zieht `D_MIGRATE_DB_PASSWORD`
  (Stufe 2) bzw. (TTY) den Prompt (Stufe 5); non-TTY ohne Quelle → Exit 7, kein Leer-Passwort. Kein Fill
  auf dem MCP-Pfad (testgesichert). `user:pass` unangetastet.
- **A2:** `config credentials set --name prod …` + `data export --source prod …` zieht das gespeicherte
  Passwort (Master via `D_MIGRATE_MASTER_PASSWORD`/**ein** Prompt) — Store end-to-end nutzbar; `data transfer`
  Quelle+Ziel=stored → **ein** Master-Prompt; `schema migrate` gegen `prod` füllt auch alle Probes; falsches
  Master-Secret / kein Store-Eintrag → Exit 7 (secret-frei, mit Diagnose).
- Präzedenz inline/`${VAR}` > Env > Store > Prompt. Kein Klartext in Log/Fehler. Kover ≥ 90 % je Modul;
  detekt clean. roadmap-Status bleibt **🚧** bis Slice B.

## 6. Offene Fragen (für User-Go vor Umsetzung)

1. **Staffelung:** A1 (Env+Prompt) zuerst — de-riskt, aber der **Store-Konsum** (dein Kernanliegen) kommt
   erst in A2 — **oder** A2 zuerst/zusammen (Store-Konsum = Nutzen, der den Store-Bau motivierte), mit dem
   Namens-Refactor als bewusstem Teil? (Empfehlung Review: A1→A2. Empfehlung Anliegen: A2 nicht zu weit
   nach hinten.)
2. **Store-Key-Semantik (D-2):** nur Connection-Name in A2? URL-abgeleitete Keys/`credentialRef` = Slice B.
3. **Master-Secret-UX (D-5):** je-Invocation-Cache (ein Prompt) akzeptabel; Session-übergreifendes Caching
   = Nicht-Ziel (Sicherheits-Trade-off).

## 7. Referenzen

- [`LN-049`](../../../spec/lastenheft-d-migrate.md#ln-049) / [`LN-025`](../../../spec/lastenheft-d-migrate.md#ln-025) / [`LN-038`](../../../spec/lastenheft-d-migrate.md#ln-038)
- [connection-config-spec 4.1](../../../spec/connection-config-spec.md#41-priorität) — Priorität (Zielbild)
- [ADR 0034](../../adr/0034-master-key-architektur-credential-store.md) — O2+O4, gestaffelt (O4 = Slice B)
- [ImpPlan LN-025 Slice 1](../done/ImpPlan-1.0.0-RC-ln025-slice1-credential-store.md) — Store-Bau (O2)
