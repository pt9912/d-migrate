---
status: accepted
date: 2026-07-13
decision-makers: pt9912
consulted: spec/connection-config-spec.md, spec/lastenheft-d-migrate.md, docs/planning/next/config-cli-management-surface.md
informed: adapters/driven/connection-config, adapters/driving/cli, hexagon/ports-common
---

# Master-Key-Architektur für den verschlüsselten Credential-Store (LN-025)

> **Status: accepted (2026-07-13).** Architektur-**Richtung** der gesamten Krypto-Fläche für
> [`LN-025`](../../spec/lastenheft-d-migrate.md#ln-025) („Datenbankzugangsdaten müssen verschlüsselt
> gespeichert werden") — **nicht** der Slice-Zuschnitt (die beiden werden bewusst entkoppelt).
> Entschieden: passphrase-abgeleiteter lokaler Store (**O2**) **plus** strukturierte
> `credentialRef`/`providerRef`-Delegation (**O4**), **gestaffelt** ausgeliefert (Store zuerst); **O1
> verworfen**, **O3 zurückgestellt**. Der Plan
> [`config-cli-management-surface.md`](../planning/next/config-cli-management-surface.md) (§7.3) hielt
> diese Frage bewusst offen, weil ein falscher Ansatz die gesamte Krypto-Fläche prägt.

## Kontext und Problemstellung

[`LN-025`](../../spec/lastenheft-d-migrate.md#ln-025) verlangt, dass Zugangsdaten **verschlüsselt
gespeichert** werden. Der Spec-Vertrag `connection-config-spec.md` §4.2 beschreibt dazu **wörtlich** einen
lokalen AES-256-Store: `~/.d-migrate/credentials.enc`, entschlüsselt mit einem **Master-Key in
`~/.d-migrate/master.key` (chmod 600)**. Die Frage dieser ADR: Ist diese wörtliche Bauweise die richtige,
oder gibt es einen sichereren/konsistenteren Ansatz?

**Verifizierter Code-Ist-Stand** (Explore, 2026-07-13):

1. **Keine vorhandene Krypto für Credentials.** Der einzige `javax.crypto`-Einsatz ist HMAC-Signing von
   MCP-Cursorn (`McpCursorCodec`), keine Verschlüsselung. Ein AES-Store ist komplett neu.
2. **Zwei getrennte Credential-Welten.** Der CLI-`--source`-Pfad (`NamedConnectionResolver`) ist eine
   konkrete if/else-Kette **ohne Port**; das Secret existiert nur als URL-Substring, bis
   `ConnectionUrlParser.parseUserInfo` es herauslöst. Der MCP/[`LF-012`](../../spec/lastenheft-d-migrate.md#lf-012)/[`LN-038`](../../spec/lastenheft-d-migrate.md#ln-038)-Pfad hat dagegen einen
   Driven-Port `ConnectionSecretResolver` (`credentialRef: env:VAR`), den die CLI **nie** aufruft.
3. **Masking existiert bereits** (`ConnectionSecretMasker` → `***` für URL-Passwörter + sensible Params;
   `ConnectionConfig.toString()` maskiert). Der §4.3-Masking-Teil von [`LN-025`](../../spec/lastenheft-d-migrate.md#ln-025)
   ist also weitgehend erfüllt.
4. **Stufe 2 (`D_MIGRATE_DB_PASSWORD`) ist im Code nicht vorhanden** (nur Spec).

**Das Kern-Sicherheitsproblem des Spec-Wortlauts:** Liegt der Master-Key als Datei **neben** dem
Ciphertext, schützt ihn nur die Dateiberechtigung (`chmod 600`). Gegen das realistische Bedrohungsmodell
eines CLI-Werkzeugs — geleakte/cloud-synchronisierte Home-Verzeichnisse, Backup-Snapshots, versehentliches
Teilen, Git-Unfälle — ist ein Key-neben-Ciphertext **kaum stärker als eine `chmod 600`-Klartextdatei**
(wer die eine Datei liest, liest auch die andere). Gegen Same-User-Malware schützt ohnehin nichts Lokales.

**Umgebungs-Randbedingung:** d-migrate läuft interaktiv (Laptop, TTY, evtl. Keychain) **und** headless
(CI/CD, Docker — **kein** TTY, **kein** Keychain, Secrets kommen per Env). Jeder Ansatz braucht daher
zwingend einen nicht-interaktiven Secret-Pfad; ein reiner Keychain-Ansatz kann nie alleinstehen.

## Entscheidungsoptionen

- **O1 — Lokale `master.key`-Datei (Spec-Wortlaut §4.2).** Zufälliger 256-Bit-Key in
  `~/.d-migrate/master.key` (chmod 600), AES-256-GCM verschlüsselt `credentials.enc`.
- **O2 — Passphrase-abgeleiteter Key (kein Key auf Platte).** Master-Secret aus TTY-Prompt **oder**
  `D_MIGRATE_MASTER_PASSWORD` (headless); Key via PBKDF2-HMAC-SHA256 (JCE-eingebaut, keine neue
  Abhängigkeit; ~600k Iterationen) abgeleitet, AES-256-GCM. Der Key liegt **nie** auf Platte.
- **O3 — OS-Keychain** (macOS Keychain / Windows DPAPI / Linux libsecret). Master-Key/Credentials im
  OS-Secret-Store.
- **O4 — `providerRef`/Env-Delegation (kein lokaler Store), [`LN-038`](../../spec/lastenheft-d-migrate.md#ln-038)-konsistent.** Credentials bleiben
  in der bestehenden Operator-Infrastruktur (Env, später Vault/AWS Secrets Manager via `providerRef`);
  d-migrate speichert **nichts** im Klartext-Ruhezustand. Der CLI-Pfad übernimmt das schon existierende
  World-B-`credentialRef`-Modell.

## Bewertung

| Kriterium | O1 Key-Datei | O2 Passphrase | O3 Keychain | O4 providerRef/Env |
| --------- | ------------ | ------------- | ----------- | ------------------ |
| At-Rest-Sicherheit (realist. Bedrohung) | schwach (Key neben Ciphertext) | **stark** (kein Key at rest) | stark (OS-gebunden) | **stark** (nichts at rest) |
| Headless/CI/Docker | ok | **ok** (via Env) | **nein** (Fallback nötig) | **ok** (nativ) |
| Neue Abhängigkeiten | keine | keine (PBKDF2 in JCE) | **native/JNA je OS** | keine |
| Cross-Platform (Win-`chmod`?) | schwach (Win-ACL-Fallback) | **gut** | 3 Backends | **gut** |
| Spec-§4.2-Treue (Wortlaut) | **exakt** | Abweichung (Spec-Update nötig) | Abweichung | Abweichung |
| Komplexität / RC-Risiko | niedrig | niedrig–mittel | **hoch** | niedrig (baut auf World B) |
| Erfüllt die Anforderung „verschlüsselt gespeichert" | ja (formal) | **ja (materiell)** | ja | ja (nichts gespeichert → nichts unverschlüsselt) |

## Entscheidung

**O2 + O4 (geschichtet); O1 verworfen; O3 zurückgestellt.** Diese ADR fixiert die **Richtung** der
gesamten Krypto-Fläche, **nicht** den Slice-Zuschnitt — der „größere Scope" von O2+O4 ist eine Frage der
Lieferstaffelung (D3), nicht der Richtung.

**Warum die ganze Fläche und nicht nur der lokale Store:** Legte die ADR nur O2 fest, würde der lokale
Store zur De-facto-Default-Antwort auf „wo liegen Credentials?" — dabei ist **Delegation (d-migrate
speichert nichts) die sicherere Grundhaltung**, und der nächste Bauende entwürfe den Store ohne die
Resolver-Naht im Blick. Genau dieses Muster trat bei [`LN-026`](../../spec/lastenheft-d-migrate.md#ln-026)
auf: der Review-1-Kern war die fehlende SPI-Naht, die nachträglich eingezogen werden musste. Das
vermeiden wir, indem die Naht von Anfang an Teil der Richtung ist.

### D1 — Lokaler Store: passphrase-abgeleitet (O2), nicht Key-Datei (O1)
AES-256-GCM; Master-Secret aus TTY-Prompt **oder** `D_MIGRATE_MASTER_PASSWORD`; Key via
PBKDF2-HMAC-SHA256 (JCE-eingebaut, keine neue Abhängigkeit; ~600k Iterationen, OWASP-konform), Nonce
zufällig pro Verschlüsselung. Der Key liegt **nie** auf Platte. Das ist das Werkzeug für den
**interaktiven** Fall (Passwort einmal setzen, wiederverwenden).

### D2 — Delegation als sichere Default-Haltung (O4)
Das World-B-`credentialRef`/`providerRef`-Modell auf den CLI-`--source`-Pfad ausdehnen. O4s Beitrag ist
**nicht** „ermöglicht Secrets per Env" — nichts-im-Klartext-at-rest kann der CLI-Pfad heute schon via
`${VAR}`-Substitution (`NamedConnectionResolver.substituteEnvVars`, `connection-config-spec` Abschnitt
3.3). O4s **echter** Wert ist das **strukturierte** `credentialRef`/`providerRef`-Modell konsistent zu
World B, die **gemeinsame Port-Naht** statt der heutigen if/else-Kette, und der **Zukunftspfad**
Vault/Secrets-Manager.

### D3 — Lieferstaffelung (Richtung ≠ Zuschnitt)
- **Slice 1 = O2-Store** — self-contained, kippt [`LN-025`](../../spec/lastenheft-d-migrate.md#ln-025)
  ⛔→✅ und entsperrt nebenbei `config credentials set/list` aus
  [`cli-unimplemented-commands.md`](../planning/open/cli-unimplemented-commands.md).
- **Slice 2 = O4-Naht** — World-A-Anschluss ans `credentialRef`-Modell (gemeinsamer Port, gespiegelt an
  `ConnectionSecretResolver`) als eigener Schnitt.

In der **Auslieferung** ist O2+O4 damit nicht langsamer als „nur O2": der erste Slice ist identisch, O4
kommt als Folge-Schnitt — der Store muss die Naht nicht nachträglich retrofitten.

### D4 — O3 (Keychain) zurückgestellt
Nach 1.0: plattformspezifische Native-Integrationen (DPAPI/Keychain/libsecret) brauchen ohnehin immer
einen headless-Fallback; der RC-Aufwand lohnt nicht.

## Konsequenzen

- **Spec-Folgeänderung:** `connection-config-spec.md §4.2` wird vom wörtlichen `master.key`-Datei-Design
  auf das passphrase-/delegations-basierte Design nachgezogen (die Spec ist Zielbild; diese ADR gibt die
  Richtung vor). Das Update muss die **Schicht-Zuordnung** explizit machen (s. nächster Punkt).
- **Schicht-Wahl / Anti-Pattern:** `D_MIGRATE_MASTER_PASSWORD` in CI/Docker ist sicherheitstechnisch
  **schlechter** als die DB-Credentials direkt per Env/`credentialRef` — eine Indirektion mehr ohne
  Gewinn (wer die Env liest, hat beides). Der Store (O2) ist das Werkzeug für den **interaktiven** Fall;
  **headless ist die Delegation (O4) der Default.** Doku (Spec-Update + Handbuch) muss das aussprechen,
  damit Nutzer nicht die falsche Schicht wählen.
- **Vorbedingungen bleiben** (aus dem Plan), unabhängig von der Key-Frage:
  - **Lastenheft-Backfill:** Eigener LN-Eintrag „Secret-/Credential-Management" — die Store-Fläche
    (`D_MIGRATE_DB_PASSWORD`, Store, Key-Handling, Prompt, Masking) ist heute nur in
    `connection-config-spec`, nicht im Lastenheft verankert.
  - **Spec-Klärung Stufe 2:** Semantik von `D_MIGRATE_DB_PASSWORD` (global vs. source/target,
    URL-Ergänzung vs. Override).
  - **Security-Review vor Merge:** Krypto (GCM-Nonce-Handling, PBKDF2-Parameter), Key-Permissions,
    Log-/Exception-Masking der neuen Store-Felder (das vorhandene `ConnectionSecretMasker` deckt URLs ab,
    nicht die Store-User/-Passwort-Felder).
- **Positiv:** O2/O4 vermeiden native Abhängigkeiten und den Windows-`chmod`-Portabilitätsbruch; O4 baut
  auf bereits getestetem World-B-Code auf.

## Verworfene Alternativen

- **O1 (Spec-Wortlaut `master.key`-Datei)** — verworfen: Key neben Ciphertext ist gegen das reale
  Bedrohungsmodell (Backup-/Sync-/Sharing-Leaks) praktisch wertlos, dazu der Windows-`chmod`-Bruch. O2
  ist die strikt bessere lokale Option **ohne Mehraufwand für den Nutzer** — ein Low-Security-Opt-in
  würde nur zur Fehlnutzung einladen.
- **O3 (Keychain) für RC** — verworfen wegen Native-Abhängigkeiten pro OS und dem in headless-Umgebungen
  ohnehin nötigen Fallback; Kandidat für einen späteren Milestone.
