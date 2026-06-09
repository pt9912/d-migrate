# CLI-Verwaltungsoberfläche `config` (`config show` / `config credentials list` / `config credentials set`)

**Status**: Entwurf (2026-06-09 — Scope aus cli-spec §6.7 + connection-config-spec §4 abgeleitet, Phasenschnitt + Vorbedingungen ausgearbeitet; bereit für Review).

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

Daraus folgt: Es fehlt keine Kernfähigkeit, sondern (a) die **CLI-Hülle**
über bereits gebauter Mechanik und (b) **eine** bislang ungebaute,
sicherheitssensible Secret-Ebene (Stufe 4 der Kette).

**Aktivierungsbedingung** (Move nach `in-progress/`): Phase 1 (§3) ist ohne
Vorarbeit implementierbar und kann sofort starten. Phase 2 (§4) blockiert
auf den Vorbedingungen in §6 (Lastenheft-Backfill „Secret-Management" +
eingeplanter `/security-review`).

---

## 1. Ausgangslage — die Credential-Prioritätskette (connection-config-spec §4.1)

```
1. Inline-URL:            postgresql://user:password@host/db     ── implementiert
2. ENV:                   D_MIGRATE_DB_PASSWORD                  ── implementiert
3. Config ${VAR}/credentialRef (z. B. env:PG_PASS)              ── implementiert
4. Encrypted File:        ~/.d-migrate/credentials.enc (AES-256) ── NICHT gebaut  ← Phase 2
5. Interaktiver Prompt    (nur TTY)                              ── NICHT gebaut  ← Phase 2
```

Stufen 1–3 werden vom `NamedConnectionResolver` (Inline-URL +
`${VAR}`-Substitution + Named Connections + `default_source/target`) und
`EnvConnectionSecretResolver` (`env:`-Refs) bedient. Stufen 4–5 existieren
nicht. Wichtig: der LN-038-`YamlConnectionReferenceLoader` materialisiert
Secrets **bewusst nicht** in die MCP-Discovery-Oberfläche
(`resources/list`, `*_list`) — ein lokaler Encrypted-Store ist eine reine
CLI-Operator-Ebene und darf diese Trennung nicht aufweichen.

## 2. Architektur-Einordnung (beide Phasen)

- Neues Top-Level-`ConfigCommand` in `adapters/driving/cli/.../Main.kt:155`
  registrieren (`DMigrate().subcommands(..., ConfigCommand())`), plus
  `ConfigCommands.kt` mit `subcommands(ConfigShowCommand(),
  ConfigCredentialsCommand())` (Letzteres mit `set`/`list`-Subkommandos).
- Drei-Schicht-Muster konsequent: Clikt-`*Command` (Argument-Parsing) im
  Driving-Adapter; `*Runner` + `*Wiring` `internal` in
  `hexagon/application/.../cli/commands/` (Hilfstypen bleiben intern).
- Bestehende Bausteine wiederverwenden, **nicht** duplizieren:
  `NamedConnectionResolver.resolveConfigPath()` (CLI `>` ENV `>` Default)
  für den effektiven Pfad, `ConnectionConfigParser` für das Lesen.

## 3. Phase 1 — CLI-Hülle über vorhandener Mechanik (keine Krypto)

Reiner „aufgeschobener Komfort": kein neuer Secret-Backend, kein
Security-Review nötig, voll mit Bordmitteln umsetzbar.

### 3.1 `config credentials list`
- Listet die Keys aus `database.connections` via
  `ConnectionConfigParser.parseConnections()` — **nur Namen, nie Werte**.
- Berücksichtigt sowohl Legacy-String-Form als auch LF-012/LN-038-Map-Form.
- Exit `0`; Exit `7` bei Config-Parse-Fehler.
- **Akzeptanz**: leere Config → leere Liste (Exit 0); Mischform → beide
  gelistet; kaputte YAML → Exit 7 mit §6.14.3-Meldung. Kein Wert/Passwort
  erscheint je in der Ausgabe (Test pinnt das).

### 3.2 `config show [--section <s>]`
- Effektiven Config-Pfad über die vorhandene CLI>ENV>Default-Logik
  auflösen, `.d-migrate.yaml` parsen, Sektionen rendern
  (`database`/`export`/`import`/`pipeline`/`incremental`/`ai`/`i18n`/`ddl`/
  `docs`/`logging`), **sensible Werte maskieren** als `***` (Passwörter in
  URLs, `api_key`, `credentialRef`-Werte) gemäß §4.3.
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

## 4. Phase 2 — `config credentials set` + Encrypted-Store (sicherheitssensibel, eigener Slice)

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

### 4.3 Integration in die Resolution-Kette
- Encrypted-Store als **Stufe 4** in `NamedConnectionResolver` /
  `…SecretResolver` einhängen, *nach* Inline/ENV/`${VAR}`, *vor* dem
  interaktiven Prompt.
- **Trennungs-Invariante**: Store-Inhalte dürfen nicht in die
  MCP-Discovery-Oberfläche (LN-038) lecken — Regressions-Test, der
  `resources/list`/`*_list` gegen einen befüllten Store prüft.
- `config credentials list` (aus Phase 1) erweitert sich additiv um die
  Store-Namen, sobald der Store existiert.

## 5. Spec- und Doku-Hygiene (Abschluss jeder Phase)
- „Geplant."-Marker in `cli-spec.md` §6.7 für die gelieferten Kommandos
  entfernen; `schema rollback`-Vorbild (Marker raus + Aufnahme in die
  Implementiert-Liste der Übersicht L7–10).
- Top-Level `config` in §1.1 (L29) zur Implementiert-Liste der Commands
  ergänzen.
- `connection-config-spec.md §4` von „spezifiziert" auf den realen Stand
  nachziehen (was implementiert ist vs. offen).

## 6. Vorbedingungen
- **Phase 1**: keine — sofort startbar.
- **Phase 2**:
  - **Lastenheft-Backfill**: eigener LN-Eintrag „Secret-/Credential-Management"
    (AES-256-Store, Key-Datei-Permissions, Masking) — §4.2 ist heute nur in
    `connection-config-spec` verankert, nicht im Lastenheft. Ohne diesen
    Eintrag bleibt Phase 2 ohne Provenienz (genau der Befund, der diesen
    Plan ausgelöst hat).
  - **`/security-review`** für Krypto, Key-Permissions, Logging-Maskierung —
    eingeplant *vor* Merge.

## 7. Offene Fragen / Entscheidungen
1. Bedeutung von „gemerged aus allen Quellen" in `config show` (§3.2) —
   effektive Datei + ENV-Marker (Phase 1) vs. voller Provenienz-Merge?
2. Master-Key-Strategie in Phase 2: lokale Key-Datei (§4.2-Stand) vs.
   OS-Keychain/`providerRef: secrets-manager` (konsistent mit dem
   LN-038-Modell). Ggf. macht ein `providerRef`-basierter Ansatz den
   eigenen AES-Store teils überflüssig — vor Phase-2-Aktivierung klären.
3. Output-Format `config show`: YAML-Echo vs. normalisierte Tabelle;
   `--json` für Skripting?

## 8. Empfohlener Schnitt
Phase 1 als kleiner, risikoarmer Slice zuerst (liefert sofort sichtbaren
Nutzen über vorhandener Mechanik). Phase 2 erst nach Lastenheft-Backfill
und mit eingeplantem Security-Review — oder bewusst zurückstellen, falls
Frage §7.2 zugunsten von `providerRef`/Keychain ausfällt.
