# CLI-Verwaltungsoberfläche `config` (`config show` / `config credentials list` / `config credentials set`)

**Status**: Entwurf (2026-06-09 — Scope aus cli-spec §6.7 + connection-config-spec §4 abgeleitet, Review-Findings eingearbeitet: Phase 1 nur `config show`, Secret-Management vollständig in Phase 2).

**Trigger**: `spec/cli-spec.md` §6.7 spezifiziert drei `config`-Subkommandos
(`config credentials set`, `config credentials list`, `config show`), die
bis dato als „Geplant." markiert sind, **kein** Top-Level-`config`-Command
im Code haben (`Main.kt` registriert nur `schema`/`data`/`export`/`mcp`)
und **keinen Lastenheft-Eintrag** tragen. Eine Konsistenzdurchsicht der
„Geplant"-Marker (2026-06-09) hat ergeben:

- Die *zugrundeliegende* Connection-/Config-Mechanik ist implementiert und
  trägt LF-012 / LN-038: `ConnectionConfigParser`, `NamedConnectionResolver`
  (`adapters/driving/cli/.../config/`), `EnvConnectionSecretResolver`,
  `YamlConnectionReferenceLoader` (`adapters/driven/connection-config`).
- `connection-config-spec.md §4` spezifiziert die Credential-Verwaltung
  vollständig: §4.1 eine **5-stufige Prioritätskette** und §4.2 den
  AES-256-Store (`~/.d-migrate/credentials.enc` + `~/.d-migrate/master.key`,
  chmod 600).

Daraus folgt: Es fehlt (a) die **CLI-Hülle** für `config show` über bereits
gebauter Config-Pfad-/YAML-Mechanik und (b) ein eigener
**Secret-Management-Slice** für `config credentials set/list` inklusive der
noch offenen Credential-Stufen 2, 4 und 5.

**Aktivierungsbedingung** (Move nach `in-progress/`): Phase 1 (§3) ist ohne
Vorarbeit implementierbar und kann sofort starten. Phase 2 (§4) blockiert
auf den Vorbedingungen in §6 (Spec-Klärung für `D_MIGRATE_DB_PASSWORD`,
Lastenheft-Backfill „Secret-Management" + eingeplanter `/security-review`).

---

## 1. Ausgangslage — die Credential-Prioritätskette (connection-config-spec §4.1)

```
1. Inline-URL:            postgresql://user:password@host/db     ── implementiert
2. ENV:                   D_MIGRATE_DB_PASSWORD                  ── NICHT gebaut  ← Phase 2 / Spec-Klärung
3. Config ${VAR}:         postgresql://app:${PG_PASS}@host/db    ── implementiert (Legacy-String-Form)
   credentialRef:         env:PG_PASS                            ── implementiert im secret-freien MCP/Runner-Pfad,
                                                                      NICHT im CLI-NamedConnectionResolver
4. Encrypted File:        ~/.d-migrate/credentials.enc (AES-256) ── NICHT gebaut  ← Phase 2
5. Interaktiver Prompt    (nur TTY)                              ── NICHT gebaut  ← Phase 2
```

Stufe 1 und die `${VAR}`-Variante von Stufe 3 werden vom
`NamedConnectionResolver` bedient (Inline-URL + Legacy-String-Form +
`${VAR}`-Substitution + `default_source/target`). `credentialRef`-Einträge in
der LF-012/LN-038-Map-Form werden dagegen durch
`YamlConnectionReferenceLoader` als secret-freie `ConnectionReference`
geladen und erst im autorisierten Runner-Pfad durch
`EnvConnectionSecretResolver` aufgelöst. Der `NamedConnectionResolver`
ignoriert Map-Form-Einträge absichtlich.

Wichtig: der LN-038-`YamlConnectionReferenceLoader` materialisiert Secrets
**bewusst nicht** in die MCP-Discovery-Oberfläche (`resources/list`,
`*_list`) — ein lokaler Encrypted-Store ist eine reine CLI-Operator-Ebene und
darf diese Trennung nicht aufweichen.

## 2. Architektur-Einordnung (beide Phasen)

- Neues Top-Level-`ConfigCommand` in `adapters/driving/cli/.../Main.kt:155`
  registrieren (`buildRootCommand().subcommands(..., ConfigCommand())`), plus
  `ConfigCommands.kt`.
  - Phase 1 registriert nur `ConfigShowCommand()`.
  - Phase 2 ergänzt `ConfigCredentialsCommand()` mit `set`/`list`.
- Drei-Schicht-Muster konsequent: Clikt-`*Command` (Argument-Parsing) im
  Driving-Adapter; Runner/Wiring bleiben dort, wo ihre Abhängigkeiten
  hingehören. Komponenten, die `NamedConnectionResolver`,
  `ConnectionConfigParser` oder `YamlConnectionReferenceLoader` direkt nutzen,
  bleiben im CLI-Adapter, weil `hexagon/application` nicht auf Adapter
  dependieren darf.
- Bestehende Bausteine wiederverwenden, **nicht** duplizieren:
  `EffectiveConfigPathResolver` (CLI `>` ENV `>` Default) für den effektiven
  Pfad; `ConnectionConfigParser` nur für Legacy-String-Form; für
  LF-012/LN-038-Map-Form `YamlConnectionReferenceLoader` bzw. eine neue
  adapter-neutrale Listen-API verwenden.

## 3. Phase 1 — CLI-Hülle über vorhandener Mechanik (keine Krypto)

Reiner „aufgeschobener Komfort": kein neuer Secret-Backend, kein
Security-Review nötig, voll mit Bordmitteln umsetzbar.

### 3.1 `config show [--section <s>]`
- Effektiven Config-Pfad über die vorhandene CLI>ENV>Default-Logik
  auflösen, `.d-migrate.yaml` parsen, Sektionen rendern
  (`database`/`export`/`import`/`pipeline`/`incremental`/`ai`/`i18n`/`ddl`/
  `documentation`/`logging`/`security`), **sensible Werte maskieren** als
  `***` (Passwörter in URLs, `api_key`, `credentialRef`-Werte) gemäß §4.3.
- `--section` filtert auf einen Abschnitt.
- Exit `0`; Exit `7` bei Config-Fehler; Exit `2` bei unbekanntem
  `--section`.
- **Spec-Interpretationspunkt** „gemerged aus allen Quellen": Phase 1 zeigt
  die effektive Datei und markiert erkennbare `D_MIGRATE_*`-ENV-Overrides.
  Ein vollständiger Multi-Source-Merge (Defaults + Datei + ENV + Flags mit
  Provenienz-Spalte) ist eine spätere Verfeinerung → in cli-spec §6.7
  präzisieren, bevor gebaut wird.
- **Akzeptanz**: Masking-Test (URL mit Passwort → `***`, kein Klartext in
  Ausgabe), `--section database` zeigt nur DB-Block, unbekannte Section →
  Exit 2.

### 3.2 Nicht in Phase 1
- `config credentials list` wird **nicht** als Config-Connection-Liste
  implementiert. Der Spec-Kontext ist der verschlüsselte Credential-Store;
  der „Geplant."-Marker bleibt daher bis Phase 2 stehen.
- Falls eine reine Connection-Namen-Liste gewünscht ist, braucht sie einen
  eigenen Spec-Eintrag (z. B. `config connections list`) und darf nicht als
  erfülltes Credential-Store-Feature gezählt werden.

## 4. Phase 2 — `config credentials set/list` + Credential-Resolution (sicherheitssensibel, eigener Slice)

### 4.1 Neuer Driven-Adapter `adapters/driven/credential-store`
- AES-256-Verschlüsselung von `~/.d-migrate/credentials.enc`; Master-Key
  `~/.d-migrate/master.key` (chmod 600, nur Benutzer-lesbar; Erzeugung beim
  ersten `set`).
- Port im Hexagon (`CredentialStorePort`: `put(name,user,password)` /
  `listNames()` / `resolve(name)`), Adapter als Implementierung.
- **Maskierung**: Passwörter/Keys nie in Logs (§4.3), auch nicht in
  Exceptions.

### 4.2 `config credentials set`
- `--name`/`--user`/`--password`; Passwort interaktiv abfragen, wenn nicht
  übergeben **und** TTY (Stufe 5). Schreibt in den Store aus 4.1.
- Exit `0`; Exit `7` bei Config-/Store-Fehler.

### 4.3 `config credentials list`
- Listet ausschließlich Namen aus dem verschlüsselten Credential-Store —
  **keine** Werte, keine Passwörter, keine Config-Connection-Namen.
- Exit `0`; bei fehlendem Store leere Liste (Exit 0); Exit `7` bei
  Store-/I/O-/Decrypt-Fehlern.
- **Akzeptanz**: leerer Store → leere Liste; befüllter Store → Namen; Ausgabe
  enthält nie User/Passwort/Key-Material.

### 4.4 Integration in die Resolution-Kette
- Stufe 2 (`D_MIGRATE_DB_PASSWORD`) ist heute nicht gebaut. Vor Umsetzung
  klären, ob sie nur fehlende Passwörter in URLs ergänzt, einen
  `password`-Query-Parameter setzt oder pro Source/Target getrennte Env-Namen
  braucht. Danach Implementierung + Tests in diesen Slice aufnehmen.
- Encrypted-Store als **Stufe 4** in die zuständige Secret-Resolution
  einhängen, *nach* Inline/ENV/`${VAR}`/`credentialRef`, *vor* dem
  interaktiven Prompt. Der CLI-Legacy-Pfad (`NamedConnectionResolver`) und der
  LF-012/LN-038-Runner-Pfad (`ConnectionSecretResolver`) dürfen dabei nicht
  versehentlich vermischt werden; ggf. braucht es einen gemeinsamen Port oder
  eine explizite Adapter-Komposition.
- **Trennungs-Invariante**: Store-Inhalte dürfen nicht in die
  MCP-Discovery-Oberfläche (LN-038) lecken — Regressions-Test, der
  `resources/list`/`*_list` gegen einen befüllten Store prüft.
- Interaktiver Prompt als **Stufe 5** nur bei TTY; non-TTY fail-closed mit
  operator-tauglicher, secret-freier Fehlermeldung.

## 5. Spec- und Doku-Hygiene (Abschluss jeder Phase)
- „Geplant."-Marker in `cli-spec.md` §6.7 für die gelieferten Kommandos
  entfernen; `schema rollback`-Vorbild (Marker raus + Aufnahme in die
  Implementiert-Liste der Übersicht L7–10).
- Nach Phase 1 nur `config show` als implementiert markieren; `config
  credentials set/list` bleiben geplant.
- Nach Phase 2 `config credentials set/list` als implementiert markieren.
- Top-Level `config` in §1.1 (L29) zur Implementiert-Liste der Commands
  ergänzen, sobald Phase 1 gemerged ist.
- `connection-config-spec.md §4` von „spezifiziert" auf den realen Stand
  nachziehen (was implementiert ist vs. offen).

## 6. Vorbedingungen
- **Phase 1**: keine — sofort startbar.
- **Phase 2**:
  - **Spec-Klärung Stufe 2**: Semantik von `D_MIGRATE_DB_PASSWORD` festlegen
    (global vs. source/target-spezifisch, URL-Ergänzung vs. Override).
  - **Lastenheft-Backfill**: eigener LN-Eintrag „Secret-/Credential-Management"
    (`D_MIGRATE_DB_PASSWORD`, AES-256-Store, Key-Datei-Permissions, Prompt,
    Masking) — §4.2 ist heute nur in `connection-config-spec` verankert,
    nicht im Lastenheft. Ohne diesen Eintrag bleibt Phase 2 ohne Provenienz
    (genau der Befund, der diesen Plan ausgelöst hat).
  - **`/security-review`** für Krypto, Key-Permissions, Logging-Maskierung —
    eingeplant *vor* Merge.

## 7. Offene Fragen / Entscheidungen
1. Bedeutung von „gemerged aus allen Quellen" in `config show` (§3.1) —
   effektive Datei + ENV-Marker (Phase 1) vs. voller Provenienz-Merge?
2. Semantik von `D_MIGRATE_DB_PASSWORD` (Stufe 2): globale Fallback-Variable
   nach Spec-Text vs. getrennte Source-/Target-Variablen oder explizite
   `credentialRef`-Only-Strategie?
3. Master-Key-Strategie in Phase 2: lokale Key-Datei (§4.2-Stand) vs.
   OS-Keychain/`providerRef: secrets-manager` (konsistent mit dem
   LN-038-Modell). Ggf. macht ein `providerRef`-basierter Ansatz den
   eigenen AES-Store teils überflüssig — vor Phase-2-Aktivierung klären.
4. Output-Format `config show`: YAML-Echo vs. normalisierte Tabelle;
   `--json` für Skripting?

## 8. Empfohlener Schnitt
Phase 1 als kleiner, risikoarmer `config show`-Slice zuerst (liefert sofort
sichtbaren Nutzen über vorhandener Mechanik). Phase 2 erst nach
Spec-Klärung, Lastenheft-Backfill und mit eingeplantem Security-Review — oder
bewusst zurückstellen, falls Frage §7.3 zugunsten von
`providerRef`/Keychain ausfällt.
