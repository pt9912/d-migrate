# Slice: Reverse-Präferenzen — deklarierte Auflösung inhärenter Reverse-Mehrdeutigkeiten (Erstfall: SQLite-AUTOINCREMENT-Breite)

> Status: **Scope-Schnitt 2026-07-05; Plan-Review erledigt (3 Runden), bau-reif.** Neuer Slice, aus der
> Exploration zu [`../open/sqlite-reverse-identifier-64bit-narrowing.md`](../open/sqlite-reverse-identifier-64bit-narrowing.md)
> hervorgegangen: die dortige **Option 2 (Post-Compare-Fold + Fingerprint-v8)** wird
> durch das leichtere **Präferenz-Muster ersetzt**. Löst den 64-bit-Transfer-Bedarf
> des Tickets ohne Fingerprint-Bump. Wandert mit dem ersten Code-Commit nach
> `in-progress/`.
> Severity: **P3** (Fidelity-Verbesserung + wiederverwendbares Muster; kein akuter
> Korrektheitsdefekt — der Status quo ist spec-konform).
> Trigger: Externer Consumer-Befund (m-trace, SQLite→PG-Transfer verengt 64-bit → 32-bit)
> + User-Idee „Präferenzen im Config-File statt Tool-Rateschluss" (2026-07-05).
> Präzedenz-Muster: [ADR 0015](../../adr/0015-fulltext-tsvector-neutral-type.md) und
> [ADR 0021](../../adr/0021-column-ordinal-fidelity.md) ankern querschnittliche Prinzipien.

## Ziel

Ein **wiederverwendbares Muster** verankern: **inhärente Reverse-Mehrdeutigkeiten**
(Fälle, in denen ein Ziel-DDL mehrere gleichwertige Neutraltyp-Repräsentationen
zulässt) löst d-migrate über eine **deklarierte Anwender-Präferenz** (Config-Datei +
CLI-Flag, konservativer Default) auf — **nicht** über eine Auto-Heuristik oder eine
Post-Compare-Fold-Maschinerie. Erster konkreter Fall: die SQLite-AUTOINCREMENT-Breite
(`identifier` 32-bit ↔ `biginteger`+`generation:identity` 64-bit). Damit wird der
SQLite→PG/MySQL-Transfer 64-bit-treu, **ohne** Fingerprint-Bump und **ohne** den
authored-`identifier`→SQLite-Post-Compare (aus dem Kanonisierungs-/PK-Slice) zu brechen.

## Kontext & Befund (Exploration 2026-07-05)

**Die Mehrdeutigkeit ist inhärent.** SQLite rendert sowohl `identifier` als auch
`biginteger`+`generation:identity` zum **identischen** `INTEGER PRIMARY KEY
AUTOINCREMENT` (immer 64-bit-Rowid) — speicher-ununterscheidbar. Der Reverse
([`SqliteTypeMapping.mapColumn`](../../../adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteTypeMapping.kt),
Zeilen 30–41) wählt heute **fest** `identifier` (+ INFO-Note R202). PG/MySQL haben die
Mehrdeutigkeit nicht (int4 ≠ int8) und reversen 64-bit bereits korrekt als
`biginteger`+`identity`
([`PostgresTypeMapping`](../../../adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresTypeMapping.kt),
[`MysqlTypeMapping`](../../../adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlTypeMapping.kt))
— **SQLite ist der einzige Ausreißer**.

**Warum Präferenz statt Fold.** Die verworfene Option 2 hätte den Reverse fest auf
`biginteger`+`identity` umgestellt und die entstehende Post-Compare-Drift
(authored `identifier` vs. reversed `biginteger`+`identity`) mit einem strukturellen
Fold `identifier ≡ biginteger+identity` versteckt. Dieser Fold spannt **zwei** Felder
(`type` **und** `generation`), die Fingerprint
([`MigrationFingerprint`](../../../hexagon/core/src/main/kotlin/dev/dmigrate/core/diff/migration/MigrationFingerprint.kt)
Zeile 209 vs. 218) und Comparator getrennt projizieren — der gelandete Typ-Kanonisierer
ist `(NeutralType)->NeutralType` und kann `generation` nicht anfassen. Ergebnis wäre ein
**Fingerprint-v7→v8-Bump** (erneute Artefakt-/Overlay-Invalidierung) plus eine
architektur-invasive Sonderfaltung. Die **Präferenz eliminiert das Raten an der Wurzel**:
der Reverse produziert je nach deklarierter Absicht ein anderes Schema; der Fingerprint
hasht unverändert (kein Bump), und der konservative Default hält den bestehenden Stand.

## Code-Fakten (Exploration 2026-07-05)

- **Options-Port:** [`SchemaReadOptions`](../../../hexagon/ports-read/src/main/kotlin/dev/dmigrate/driver/SchemaReadOptions.kt)
  (ports-read) ist die schlanke Reverse-Options-Tasche (heute nur `include*`-Flags,
  Default `true`). Wird an [`SchemaReader.read`](../../../hexagon/ports-read/src/main/kotlin/dev/dmigrate/driver/SchemaReader.kt)
  durchgereicht. **Natürliche Heimat der Präferenz.**
- **Konstruktions-Sites (CLI→Options):**
  [`SchemaReverseRunner`](../../../hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/SchemaReverseRunner.kt)
  (Zeile 157) und [`DataTransferRunner`](../../../hexagon/application/src/main/kotlin/dev/dmigrate/cli/commands/DataTransferRunner.kt)
  (Zeile 102) bauen `SchemaReadOptions` — hier wird die Präferenz aus Flag/Config gefüllt.
- **CLI:** [`SchemaReverseCommand`](../../../adapters/driving/cli/src/main/kotlin/dev/dmigrate/cli/commands/SchemaReverseCommand.kt)
  trägt die `schema reverse`-Flags.
- **Reverse-Umbaupunkt:** [`SqliteSchemaReader`](../../../adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteSchemaReader.kt)
  (Zeilen 184–208) konsumiert `mapColumn` und konstruiert die `ColumnDefinition`
  **ohne** `generation`; hier (gespeist von einer präferenz-bewussten `mapColumn`) wird
  bei `BIGINTEGER_IDENTITY` `type = biginteger` + `generation = Identity` gesetzt.
- **Modell:** [`ColumnGeneration.Identity`](../../../hexagon/core/src/main/kotlin/dev/dmigrate/core/model/ColumnGeneration.kt)
  (`mode`/`sequenceName`/`legacySerialSyntax`) + `ColumnDefinition.generation` existieren
  bereits; SQLite-Generate für `biginteger`+`identity` existiert **schon** und ist
  getestet (`SqliteColumnConstraintHelper.generateRowidIdentityColumn`).
- **Config-Datei:** [`connection-config-spec.md`](../../../spec/connection-config-spec.md)
  Abschnitt 3.2 trägt bereits `export:`/`import:`/`pipeline:`-Sektionen im `.d-migrate.yaml`
  — Präzedenz für einen `reverse:`-Block.
- **Kein Fingerprint-Berührungspunkt:** die Präferenz ändert nur den **Reverse-Output**,
  nicht die Fingerprint-Berechnung. `ALGORITHM` bleibt `schema-fingerprint-v7`.

## Design-Entscheidungen

**D1 — Präferenz statt Heuristik/Fold (das ADR-Prinzip).** Inhärente Reverse-Mehr­
deutigkeiten werden durch **deklarierte Anwender-Präferenz** aufgelöst; der Default ist
**konservativ** (= der bisherige, spec-konforme Stand). Für die SQLite-AUTOINCREMENT-
Breite: Default `IDENTIFIER` (32-bit-Vertrag, unverändert, R202-Note bleibt), Opt-in
`BIGINTEGER_IDENTITY` (64-bit-treu).

**D2 — Heimat = `SchemaReadOptions`.** Ein neues Feld auf der bestehenden Reverse-Options-
Tasche (z. B. `sqliteAutoincrement: SqliteAutoincrementReverse = IDENTIFIER`), durch
`SchemaReader.read` an den SQLite-Reader durchgereicht. Kein neuer Port, keine
Signatur-Störung anderer Reader (der Default trägt sie unverändert).

**D3 — Oberfläche = CLI-Flag + Config-Datei, Flag übersteuert Config; Width-Framing
(Review F3).** Flag auf `schema reverse` **und** `data transfer` (Transfer ist der
Hauptanwendungsfall); `.d-migrate.yaml` bekommt einen `reverse:`-Block (Muster wie
`export:`). Präzedenz: CLI > Config > Default. **Anwender-Vokabular dialekt-neutral als
Breite** — `reverse.sqlite.autoincrement_width: 32 | 64`, Flag
`--sqlite-autoincrement-width <32|64>` — damit der stabile Config-Vertrag **nicht** an
interne Neutraltyp-Namen koppelt; der interne Enum bleibt
`SqliteAutoincrementReverse{IDENTIFIER, BIGINTEGER_IDENTITY}` (Trennung Vertrag ↔
Implementierung). Fügt sich ins bestehende Options-/Flag-Muster.

**D4 — SQLite-spezifische Kante, generisches Muster.** Nur SQLite kollabiert die zwei
Formen; PG/MySQL bleiben unberührt (korrekt). Das **Präferenz-Muster** ist aber generisch
angelegt (weitere Mehrdeutigkeiten docken als Registry-Einträge an), ohne jetzt ein
generisches Framework zu bauen — genau **eine** Präferenz wird implementiert.

**D5 — Muster-Verankerung (Doku).**
- **Neue ADR** (nächste freie Nummer, vorauss. 0027): das Prinzip D1 (Präferenz statt
  Fold/Heuristik für inhärente Reverse-Mehrdeutigkeit) + die Ablehnung der v8-Fold-Option
  mit Begründung.
- **Neues Zielbild-Spec `reverse-preference-mechanism.md` (neu, unter spec/):** der generische Mechanismus
  (Präzedenz Config↔Flag, Default-konservativ-Invariante) + eine **Registry der
  Mehrdeutigkeiten** (Eintrag 1: SQLite-AUTOINCREMENT-Breite, Querverweis auf
  `type-mapping.md`). Reines Zielbild, keine Phasen/Status, keine Abwärts-Verweise (SDP).
- **Oberflächen-Specs:** [`connection-config-spec.md`](../../../spec/connection-config-spec.md)
  Abschnitt 3.2 (YAML-Keys), [`cli-spec.md`](../../../spec/cli-spec.md) (das Flag),
  [`type-mapping.md`](../../../spec/type-mapping.md) (die SQLite-Kante + Präferenz-Querverweis).

**D6 — Scope = nur Reverse-Präferenz (Transfer-Fidelity).** Bewusst **nicht** dabei:
der Post-Compare-Fold `identifier ≡ biginteger+identity` (entfällt — der Default ändert
nichts, das Opt-in ist Anwender-Absicht) und die SQLite-`migrate`-Renderpfad-Parität für
authored `biginteger`+`identity` (`SqliteDiffSqlBuilders` implementiert Identity nicht —
eigener Folge-Befund, siehe Nicht-Scope). **Kein Fingerprint-Bump.**

**D7 — Opt-in ist nicht stumm: bestätigende INFO-Note statt Unterdrückung (Review F5).**
Wählt der Anwender die 64-bit-Breite, ist die R202-„narrowing"-Note gegenstandslos (keine
Verengung) — sie wird aber **nicht ersatzlos unterdrückt**, sondern durch eine
bestätigende INFO-Note ersetzt (Text „64-bit-Identity per deklarierter Präferenz
rekonstruiert"), damit die dialektbewusste 64-bit-Entscheidung im Audit-Trail sichtbar
bleibt. **Code = R204 (Review F1):** R200/201/202/203/220 sind belegt (R203 =
unbenannte-CHECK-Verwerfung, `SqliteSchemaReader.kt` Zeile 229) → **R204**; der neue Code
wird mit dem Ledger-Ticket
[`warn-code-ledger-completeness.md`](../open/warn-code-ledger-completeness.md) abgestimmt,
damit das Ledger autoritativ bleibt. **Interim (Review B4):** da `spec/ledger.md` heute nur
W-Codes führt, wird R204 am Emit-Punkt inline dokumentiert, sodass das spätere Ledger es
autoritativ übernehmen kann. Im Default-Modus bleibt R202.

## Arbeitspakete

**AP0 — Reproducer + Verdrahtungs-Bestätigung.** SQLite-DB mit 64-bit-AUTOINCREMENT-PK
reversen → heute `identifier` (belegt). Bestätigen: (a) exakte Stelle, an der
`SqliteSchemaReader` die `ColumnDefinition` baut (generation-Setz-Punkt); (b) der
Config→Options-Fluss (wie `export:`/`import:`-Config die Runner erreicht) als Vorlage für
`reverse:`. DoD: Rot-Fall als Asserts, Verdrahtungs-Sites benannt.

**AP1 — Port-Feld.** `SchemaReadOptions` um `sqliteAutoincrement`-Präferenz (Enum,
Default `IDENTIFIER`) erweitern; durch `SchemaReader.read` durchreichen. Unit-Test:
Default unverändert; andere Reader ignorieren das Feld.

**AP2 — SQLite-Reverse präferenz-bewusst.** `SqliteTypeMapping.mapColumn` /
`SqliteSchemaReader` honorieren die Präferenz: 64-bit → `biginteger` + `generation:
Identity` (bestätigende INFO-Note R204, D7); Default → unverändert `identifier` + R202.
**Identity-Feldwerte gepinnt (Review F2 + B1):** `ColumnGeneration.Identity(mode =
BY_DEFAULT, sequenceName = null, legacySerialSyntax = true)` — die Feldwahl ist **nicht**
kosmetisch: der PG-Generate verzweigt auf `legacySerialSyntax` (`true` → `BIGSERIAL`,
`false` → `BIGINT GENERATED … AS IDENTITY`). **`true` (Review B1):** spiegelt den einzigen
Repo-Präzedenzfall — der MySQL-Reverse desselben Legacy-Auto-Increment-Konstrukts setzt
`true` (`MysqlTypeMapping` Zeile 42) — und ist zugleich das **treue** Modell: SQLite-
AUTOINCREMENT ist wie MySQL-AUTO_INCREMENT ein Legacy-Auto-Increment **ohne**
SQL-Standard-IDENTITY; `false` würde fälschlich Standard-IDENTITY behaupten.
Cross-Dialect-Konsequenz: SQLite→PG und MySQL→PG erzeugen dasselbe `BIGSERIAL` (statt
quelldialekt-abhängig divergierendem DDL). Legt DoD-3 auf **genau ein** Ziel-Rendering fest
(`BIGSERIAL`).
**Umbaupunkt gepinnt (Review B2):** `SqliteTypeMapping.MappingResult` bekommt einen
`generation: ColumnGeneration? = null`-Kanal (exakt wie `MysqlTypeMapping.MappingResult`
Zeile 15) — `mapColumn` liefert Typ **und** `generation`, der Reader reicht beides durch;
so wird der Setz-Punkt (Mapping vs. Reader) nicht erst beim Bau ausgehandelt.
**Auffindbarkeit (Review F1):** der R202-Hint (heute `SqliteTypeMapping.kt` Zeilen 38–39,
nennt nur „biginteger plus generation: identity") wird um den **konkreten Flag/Config-Key**
ergänzt (`--sqlite-autoincrement-width 64` bzw. `reverse.sqlite.autoincrement_width: 64`) —
sonst ist die Fluchtluke genau für die Zielgruppe unsichtbar, die auf die stille Verengung
läuft. Unit-Tests beider Modi + **flag-benannte** Round-Trip-Probe (Review F5): unter
64-bit-Präferenz reverst→generatet `biginteger`+`identity` stabil, unter Default
`identifier` — `generateRowidIdentityColumn` rendert für beide dasselbe
`INTEGER PRIMARY KEY AUTOINCREMENT`, die Neutralmodell-Stabilität hängt also an der
Flag-Stellung. Zusätzlich Assert, dass der R202-Hint den Key nennt.

**AP3 — Oberfläche (CLI + Config).** Flag auf `schema reverse` + `data transfer`;
`.d-migrate.yaml`-`reverse:`-Block parsen + mergen (Flag > Config > Default). Tests:
Präzedenz, Default-Weglassbarkeit.

**AP4 — Muster-Verankerung + Doku.** Neue ADR (D5); `reverse-preference-mechanism.md`
(neu, unter spec/) (Mechanismus + Registry); Oberflächen-Specs (`connection-config-spec`
3.2, `cli-spec`, `type-mapping`); Anwenderhandbuch-Notiz; CHANGELOG. Das 64bit-Ticket auf
„Option 2 durch Präferenz-Muster abgelöst" aktualisieren **und dabei die geänderte
Aktivierungsbegründung explizit ziehen (Review F6):** das Ticket setzte eine höhere
Schwelle („wiederholte Consumer-Befunde"); dieser Slice aktiviert auf einem Befund + Idee
— legitim durch das gesenkte Kostenprofil (kein Fingerprint-Bump, wiederverwendbares
Muster), aber benannt statt offengelassen. **Neues Folge-Ticket anlegen (Review F2 + B3):**
`open/sqlite-migrate-biginteger-identity-render-gap.md` — es nennt **zwei** Ursachen, sonst
gilt es nach dem Render-Fix als erledigt, obwohl Drift bleibt: (1) die
`SqliteDiffSqlBuilders`-Identity-Render-Lücke (Fehler-Charakteristik siehe Nicht-Scope);
(2) das **Präferenz-Threading im Post-Compare-Re-Read** — bei aktivierter 64-bit-Präferenz
+ migrate-gegen-SQLite driftet der Re-Read weiter (Default-Re-Read liefert `identifier`,
authored ist `biginteger`+`identity` → Exit 5), solange die Präferenz nicht bis in den
Post-Compare-Read fädelt. `make docs-check` grün.

**AP5 — Live-Abnahme.** SQLite→PG-`data transfer` (SQLite = **Quelle**) mit Breite 64 →
Ziel-Spalte `BIGSERIAL` (64-bit erhalten, Rendering gepinnt via B1 — spiegelt MySQL→PG);
Default → PG `SERIAL` (unverändert). Als Sensor im Sample-DB-Harness oder
gezielter E2E. **Semantik der Präferenz (Review F3):** sie wirkt auf **jeden SQLite-Read —
Quelle ODER Ziel** (`DataTransferRunner` speist denselben `readOpts` an Quell- (Zeile 107)
und Ziel-Read (Zeile 109)). Im Headline-Fall ignoriert das PG/MySQL-Ziel das Feld. Für
**SQLite-als-Ziel** (z. B. PG→SQLite) beeinflusst die Präferenz den ziel-seitigen
Preflight-Compat-Read; ein SQLite-als-Ziel-Sensor ist **optionaler Kann-Assert** in AP5
(nicht Headline-blockierend, kein eigener Slice).

## Abnahme (Slice-DoD)

1. `schema reverse` einer SQLite-DB mit 64-bit-AUTOINCREMENT-PK **mit**
   `--sqlite-autoincrement-width 64` → Neutralschema trägt `biginteger` +
   `generation: identity`; statt R202 die bestätigende INFO-Note (D7).
2. **Default** (kein Flag/Config, Breite 32) → unverändert `identifier` + R202-Note
   (Null-Regression).
3. SQLite→PG-Transfer mit Breite 64 → Ziel-PK ist **`BIGSERIAL`** (64-bit, gepinnt via
   `legacySerialSyntax = true`, B1 — spiegelt MySQL→PG), nicht `SERIAL`; Default-Transfer
   → `SERIAL` (unverändert).
4. Config-`reverse:`-Block wirkt; CLI-Flag übersteuert Config; Weglassen = Default.
5. **Auffindbarkeit (F1):** der R202-Hint im Default-Modus nennt den konkreten Flag/
   Config-Key (`--sqlite-autoincrement-width 64` / `reverse.sqlite.autoincrement_width`),
   sodass die Zielgruppe die Fluchtluke findet.
6. **Kein Fingerprint-Change** (`schema-fingerprint-v7` unverändert); der
   authored-`identifier`→SQLite-Post-Compare (aus dem PK-Slice) bleibt grün.
7. Tests grün; Kover ≥ 90 % je berührtem Modul; ADR + `reverse-preference-mechanism.md`
   + Oberflächen-Specs + Folge-Ticket angelegt; `make docs-check` grün.

## Nicht-Scope

- **Kein Post-Compare-Fold `identifier ≡ biginteger+identity`** und **kein
  Fingerprint-Bump** (die Präferenz löst an der Reverse-Wurzel; Default unverändert).
- **SQLite-`migrate`-Renderpfad-Parität für authored `biginteger`+`identity`**
  ([`SqliteDiffSqlBuilders`](../../../adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteDiffSqlBuilders.kt)
  implementiert Identity nicht — nur der Voll-Generate-Pfad tut es) → **eigenes Ticket**
  (in AP4 angelegt), nicht dieser Slice (adressiert Transfer, nicht migrate-gegen-SQLite).
  **Fehler-Charakteristik (Review F2):** authored `biginteger`+`identity` → SQLite-`migrate`
  rendert `id INTEGER` + separates `PRIMARY KEY(id)` — valides DDL, **appliziert**, aber
  **AUTOINCREMENT still verloren**; der Post-Compare liest `integer` zurück → **Exit-5-Drift
  (laut)**. Also lauter Exit-Code bei stillem Semantik-Verlust; Severity **P3** (schmal,
  nur opt-in-nah), im Ticket final einzustufen.
- **Keine Pro-Spalten-Granularität** — global/pro-Lauf zuerst (Pro-Spalte später, analog
  Overlay-Mechanismus).
- **Kein generisches Präferenz-Framework** — genau eine Präferenz, Struktur erweiterbar.
- **PG/MySQL unverändert** (reversen 64-bit bereits korrekt).

## Plan-Review-Entscheidungen (erledigt 2026-07-05)

Drei Review-Runden (alle code-faktengeprüft, alle Behauptungen bestätigt).
**Runde 1** — 6 Findings eingearbeitet (F1 → AP2 + DoD-5, F2 → AP4 + Nicht-Scope,
F3 → D3/R1, F4 → Spec-Umbenennung, F5 → D7, F6 → AP4).
**Runde 2** — 5 Nachschärfungen: R203-Kollision → **INFO-Code R204** (D7, mit
Ledger-Abstimmung); `ColumnGeneration.Identity`-Feldwerte gepinnt; `data transfer`-
Präferenz-Semantik „wirkt auf jeden SQLite-Read, Quelle oder Ziel" + SQLite-als-Ziel als
AP5-Kann-Assert; flag-benannte Round-Trip-Probe (AP2); Status-Header nachgezogen.
**Runde 3** — 4 Befunde: **B1 (tragend)** `legacySerialSyntax = true` statt `false` →
Ziel `BIGSERIAL` (spiegelt den einzigen Repo-Präzedenzfall MySQL→PG + treues Legacy-Modell;
AP2/DoD-3/AP5); B2 `SqliteTypeMapping.MappingResult`-`generation`-Kanal gepinnt (wie MySQL);
B3 Folge-Ticket nennt **zwei** Ursachen (Render-Lücke **+** Präferenz-Threading im
Post-Compare-Re-Read); B4 R204 interim am Emit-Punkt inline dokumentiert.
Beide offenen Fragen entschieden:

- **R1 — Benennung: ENTSCHIEDEN — Width-Framing an der Oberfläche (Review F3).** Config
  `reverse.sqlite.autoincrement_width: 32 | 64`, Flag `--sqlite-autoincrement-width <32|64>`
  (dialekt-neutral, koppelt den Config-Vertrag nicht an interne Typ-Namen); interner Enum
  bleibt `SqliteAutoincrementReverse{IDENTIFIER, BIGINTEGER_IDENTITY}` (Vertrag ↔
  Implementierung getrennt). Eingearbeitet in D3.
- **R2 — Granularität: ENTSCHIEDEN — global/pro-Lauf zuerst** (Pro-Spalte via
  Overlay-Analogie als Folge).
- **R3 — `data transfer`: ENTSCHIEDEN — ja** (`DataTransferRunner` Zeile 102 baut die
  Options bereits; sonst bliebe der Fix theoretisch). Eingearbeitet in D3/AP3.
- **R4 — Registry-Format:** Tabelle Dialekt · Mehrdeutigkeit · Präferenz-Werte · Default ·
  Detail-Spec-Verweis; die Registry-Zeilen verweisen **nur lateral** auf Detail-Specs
  (SDP — das Spec verweist nie abwärts auf ADR/Plan; Review-Hinweis zu R5). Form beim Bau.
- **R5 — Muster-Ambition: ENTSCHIEDEN — eigenes Spec jetzt**, mit **distinktem Namen**
  `reverse-preference-mechanism.md` (Review F4, vermeidet die Basename-Kollision mit diesem
  Plan) und SDP-lateral gehaltener Registry.
