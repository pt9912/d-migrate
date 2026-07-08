# SQLite-Reverse: AUTOINCREMENT (64-bit) → `identifier` (32-bit-Vertrag) — Entscheidung

> Status: **Vorabklärung/Entscheidung** (Sofortmaßnahme geliefert, Grundsatzentscheidung offen).
> Trigger: Externer Consumer-Befund (m-trace-Pilot, 2026-07-02) — SQLite→PG-Transfer verengt
> den Wertebereich einer AUTOINCREMENT-PK still von 64-bit auf 32-bit (`SERIAL`);
> Overflow ab 2,15 Mrd bei Dauerlast-Sequenzen real erreichbar.
> Aktivierungsbedingung: belegter Bedarf über die R202-Note + den dokumentierten
> `biginteger`+Identity-Pfad hinaus (z. B. wiederholte Consumer-Befunde oder ein
> SQLite-lastiger Migrationszyklus).

## Befund (verifiziert 2026-07-02)

- [`SqliteTypeMapping.mapColumn`](../../../adapters/driven/driver-sqlite/src/main/kotlin/dev/dmigrate/driver/sqlite/SqliteTypeMapping.kt)
  mappt **jede** `INTEGER PRIMARY KEY AUTOINCREMENT`-Spalte auf
  `NeutralType.Identifier(autoIncrement = true)`. SQLite-AUTOINCREMENT ist 64-bit (rowid).
- `identifier` rendert per Spec 32-bit: PG `SERIAL`
  ([`PostgresTypeMapper`](../../../adapters/driven/driver-postgresql/src/main/kotlin/dev/dmigrate/driver/postgresql/PostgresTypeMapper.kt)),
  MySQL `INT NOT NULL AUTO_INCREMENT`
  ([`MysqlTypeMapper`](../../../adapters/driven/driver-mysql/src/main/kotlin/dev/dmigrate/driver/mysql/MysqlTypeMapper.kt)).
- Die Spec ist hier eindeutig ([`neutral-model-spec.md`](../../../spec/neutral-model-spec.md)):
  „`identifier` ist der aktuelle 32-bit-Auto-Increment-Vertrag"; 64-bit-Identity ist **kein**
  eigener semantischer Typ, sondern `biginteger` + `ColumnGeneration.Identity`. Das Problem ist
  also nicht das Generate-Mapping, sondern der **stille** Reverse auf den engeren Vertrag.

## Sofortmaßnahme (geliefert 2026-07-02)

- **R202-Reverse-Note** (INFO) an jeder AUTOINCREMENT→`identifier`-Abbildung: benennt die
  64→32-bit-Verengung und verweist auf den `biginteger` + `generation: identity`-Pfad.
- `generation` in [`spec/schema.json`](../../../spec/schema.json) nachgezogen (war vom
  YAML-Codec + PG-Generate längst unterstützt, fehlte aber im JSON-Schema — ein
  schema.json-validierender Consumer hätte genau diesen Workaround abgelehnt);
  Contract-Fixture (`full-feature-schema.json`) nutzt es jetzt.

## Optionen für die Grundsatzentscheidung

1. **Note-only belassen (Status quo nach Sofortmaßnahme).** Spec-konform, kein Drift-Risiko;
   Consumer patchen gezielt auf `biginteger` + Identity (dokumentierter Pfad).
2. **SQLite-Reverse emittiert `biginteger` + `generation: identity`.** Quelltreu (64-bit),
   aber: (a) braucht einen SQLite-Generate-Pfad für Identity-Spalten (Round-Trip-Parität),
   (b) erzeugt ohne dialektbewusste Typ-Kanonisierung im Post-Compare genau die
   Drift-Klasse aus [`../done/postcompare-type-canonicalization-slice.md`](../done/postcompare-type-canonicalization-slice.md)
   (authored `identifier` vs. reversed `biginteger`) — Kanonisierung muss zuerst landen.
3. **`identifier`-Vertrag auf 64-bit heben** (PG `BIGSERIAL`/Identity, MySQL `BIGINT`).
   Sauber langfristig, aber Spec-Änderung (ADR nötig), ändert generiertes DDL aller
   Bestandsnutzer und alle Goldens/Fingerprint-Semantik. Nur mit Migrationspfad.

Empfehlung bei Aktivierung: Option 2 nach Landung der Typ-Kanonisierung; Option 3 nur, wenn
mehrere Dialekt-Verträge ohnehin angefasst werden (ADR-würdig).

**Update 2026-07-05 (2) — Option 2 durch Präferenz-Muster abgelöst.** Die Exploration von
Option 2 (fester Reverse-Umbau + struktureller Post-Compare-Fold `identifier ≡
biginteger+identity`) ergab: der Fold spannt `type` **und** `generation` und erzwänge einen
Fingerprint-Bump (Artefakt-/Overlay-Invalidierung) plus eine invasive Sonderfaltung — hoher
Preis für einen Einzelfall. Stattdessen löst der Slice
[`../done/reverse-preferences.md`](../done/reverse-preferences.md) den
64-bit-**Transfer**-Bedarf über eine **deklarierte Präferenz** an der Reverse-Wurzel
(`--sqlite-autoincrement-width 64` / `reverse.sqlite.autoincrement_width: 64`) — **ohne**
Fingerprint-Bump, konservativer Default. Die im Ticket höher gesetzte Aktivierungsschwelle
(„wiederholte Consumer-Befunde") ist damit legitim gesenkt: das Kostenprofil ist niedriger
(kein Bump) und das Ergebnis ein wiederverwendbares Muster. **Verbleibend offen** in diesem
Ticket ist nur noch der `migrate`-**gegen-SQLite**-Fall (authored `biginteger`+`identity`),
getrackt in
[`sqlite-migrate-biginteger-identity-render-gap.md`](sqlite-migrate-biginteger-identity-render-gap.md);
Option 3 (64-bit-Vertrag) bleibt separat.

## PG-Evidenz (Nachtrag 2026-07-03, AP0-Probe des Kanonisierungs-Slices)

Dieselbe Rekonstruktions-Familie existiert auf PostgreSQL: der Reverse eines
`SERIAL`-**ohne**-PK (Generate aus identifier-only-Soll) liest `integer` +
`sequence_nextval`-Default zurück — kein `identifier`, kein `auto_increment` →
Post-Compare-Drift mehrteilig (Typ + Default + effektiver PK). **Mit** explizitem
`primary_key` rekonstruiert der PG-Reverse `identifier`/`auto_increment` dagegen
korrekt (verbleibende `required`-Asymmetrie übernimmt der Kanonisierungs-Slice,
Abnahme 8). Verwandter Generate-Aspekt (implizite PK-Materialisierung):
[`generate-implicit-identifier-pk-materialization.md`](../done/generate-implicit-identifier-pk-materialization.md).

**Update 2026-07-05:** Der PG-`identifier`-only-PK-Aspekt ist mit jenem Slice
**geliefert** — Generate materialisiert den effektiven PK jetzt zentral im
`OperationMapper`, PG `SERIAL` trägt einen `PRIMARY KEY` im Ziel, und der Reverse
liest ihn korrekt als `identifier` zurück. Für dieses Ticket bleibt damit nur noch
die 64-bit-Wertebereichsfrage (Option 2).

## Nicht-Scope

- Kein Wert-Bereichs-Preflight beim Transfer (Datenebene; eigener Schnitt, falls je nötig).
