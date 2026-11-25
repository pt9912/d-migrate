---
status: proposed
date: 2026-11-25
decision-makers: pt9912
consulted: docs/adr/0048-enum-wertevorrat-im-fingerprint.md, docs/adr/0055-enum-wertevorrat-im-zielbewussten-vergleich.md, docs/adr/0067-schema-compare-wertelisten-check-gleicher-wertevorrat.md, docs/adr/0068-werteliste-erkennung-array-cast-auf-text-array.md, docs/planning/next/enum-wertevorrat-constraint-name.md
informed: hexagon/core (ColumnDefinition, SchemaComparator, MigrationFingerprint), adapters/driven/formats (SchemaNodeParser, SchemaNodeBuilder), adapters/driven/driver-postgresql, driver-sqlite, driver-mssql, driver-oracle, spec/schema-reference.md, spec/schema.json, spec/ddl-generation-rules.md, docs/user/anwenderhandbuch.md, CHANGELOG.md
---

# Name des Wertevorrat-CHECKs einer Inline-Enum — ein Feld an der Spalte

> **Status: proposed (2026-11-25).** Eine Inline-Enum (`type: enum, values: [...]`) bekommt optional einen Namen für
> den CHECK, der ihren Wertevorrat durchsetzt. Ohne den Namen schreiben Konsumenten, die einen festen Namen
> brauchen, einen rohen `CHECK … IN (…)` als Constraint. Der ist SQL-Text und damit nicht neutral. Die Entscheidung
> liegt beim Eigner; dieser ADR legt Optionen, Folgen und eine Empfehlung vor.

## Kontext und Problemstellung

Neutral wird eine Werteliste als Typ der Spalte geschrieben: `a_state: { type: enum, values: [ACTIVE, DISABLED] }`.
Wie der Wertevorrat durchgesetzt wird, entscheidet der Dialekt. Gemessen an `schema generate`:

| Dialekt | Ergebnis | Name des CHECK |
| --- | --- | --- |
| PostgreSQL | `TEXT` + `CHECK` | keiner; der Server vergibt `<tabelle>_<spalte>_check` (bei Kollision mit Ziffer, auf 63 Byte gekürzt) |
| SQLite | `TEXT` + `CONSTRAINT "ck_<tabelle>_<spalte>" CHECK` | Konvention |
| SQL Server | `NVARCHAR(n)` + `CONSTRAINT [ck_<tabelle>_<spalte>] CHECK` | Konvention |
| Oracle | `VARCHAR2(n)` + `CONSTRAINT "ck_<tabelle>_<spalte>" CHECK` | Konvention |
| MySQL | `ENUM('…')`, kein CHECK | — |

Das Modell hat dafür **kein Feld**. Wer den Namen festlegen muss (Anwendungen lesen ihn in der Fehlerbehandlung einer
verletzten Bedingung), kann das heute nur mit einem rohen CHECK ausdrücken. Das zeigt Issue #76: Der Konsument führt
`constraints: [{ name: ck_0123456789abcdef, type: check, expression: "a_state IN ('ACTIVE', 'DISABLED')" }]`.
Die Folgen dieser Schreibweise sind die Issues #76, #77, #80 und #81 sowie ADR 0067 und ADR 0068: Der Vergleich
muss SQL-Text gegen die Umschreibung des Servers halten. Das ist eine Erkennung und bleibt eine Heuristik.

Dazu kommt ein Defekt, der am fehlenden Namen hängt. **PostgreSQL vergibt den Namen selbst.** Datei gegen Datei kennt
der Up den Namen des alten CHECKs nicht und blockiert (Issue #81, `POSTGRES_ENUM_CHECK_NAME_UNKNOWN`). Der Down
blockiert in derselben Lage (Issue #61, `POSTGRES_ENUM_CHECK_ROLLBACK_NAME_UNKNOWN`).

Es gibt zwei Vorbilder im Modell, bei denen ein Name am Constraint hängt, den das Feld sonst nur voraussetzt:
`ColumnDefinition.uniqueConstraintName` (zu `unique: true`) und `ReferenceDefinition.constraintName`. Beide sind
`null`, wo die Quelle keinen Namen führt, und „ob der Name zur Identität gehört, entscheidet die Fähigkeit des
Zieldialekts".

## Betrachtete Optionen

### A — ein Feld an der Spalte (empfohlen)

`ColumnDefinition.valuesConstraintName: String?`, in der Datei `values_constraint: <name>` neben `type: enum`.

- **Erzeugen.** PostgreSQL, SQLite, SQL Server und Oracle schreiben `CONSTRAINT <name> CHECK (…)`, wenn das Feld
  gesetzt ist; sonst bleibt alles, wie es ist (PostgreSQL unbenannt, die anderen `ck_<tabelle>_<spalte>`).
  MySQL hat keinen CHECK und ignoriert das Feld mit einer Warnung, wie es bei Namen ohne Entsprechung üblich ist.
- **Up und Down.** Steht der Name in der Ist-Seite (Datei oder Datenbank), löst der Up den alten CHECK **unter diesem
  Namen**. Damit entfällt für eine benannte Inline-Enum die Blockade aus #81 und #61, auch Datei gegen Datei.
- **Vergleich.** Ist der Name nur auf der Soll-Seite gesetzt und die Datenbank führt den CHECK unter anderem Namen,
  ist das eine Änderung (Abbau und Aufbau, wie bei `unique`: Umbenennen ist kein In-place-Befehl). Ist er nicht
  gesetzt, ändert sich nichts gegenüber heute: Der Name zählt nicht.
- **Fingerabdruck.** Der Name steht in der kanonischen Nutzlast nur, wenn gesetzt (Muster von `DomainTraits`,
  ADR 0066). Bestehende Schemas hashen bis auf den Algorithmus-Tag wie zuvor. Der Tag steigt auf `v26`.
- **Reverse.** Unverändert: Er liefert eine Textspalte mit benanntem CHECK. Der Reader kann nicht wissen, dass der
  Autor eine Aufzählung meinte. Die Folgen daraus (Faltung beim Vergleich, ADR 0055) bleiben.

### B — nichts bauen, die Antwort dokumentieren

Ein Wertevorrat mit festem Namen wird als rohes `CHECK` geschrieben; seit 1.12.1 (#76) und 1.13.0 (ADR 0067/0068)
funktioniert das in allen Dialekten. Das Handbuch sagt, dass `enum` der neutrale Weg ist und der rohe CHECK nur für
den Fall mit festem Namen gilt.

- Kein Modell-, Format- und Fingerabdruck-Eingriff.
- Der Konsument bleibt bei SQL-Text und bei den Heuristiken des Vergleichs. Neue Rückformen der Server (wie die
  `varchar`-Form in ADR 0068) bleiben ein wiederkehrender Defekt.
- Datei gegen Datei bleibt für eine **unbenannte** Inline-Enum auf PostgreSQL blockiert (#81).

### C — die Konvention auch für PostgreSQL

PostgreSQL schriebe den CHECK der Inline-Enum als `CONSTRAINT "ck_<tabelle>_<spalte>" CHECK (…)` wie die anderen
drei Dialekte. Der Name wäre bekannt und stabil, die Blockaden aus #81 und #61 entfielen für neu erzeugte Datenbanken.

- Ändert das erzeugte DDL **jedes** PostgreSQL-Schemas mit Inline-Enum (Golden-Master, Fixtures, Skripte, die
  Anwender archiviert haben).
- Bestehende Datenbanken tragen weiter `<tabelle>_<spalte>_check`; ein Up gegen sie liest den Katalognamen wie heute.
  Zwei Namensstände im Bestand.
- Löst nicht, was der Konsument will: einen **selbst gewählten** Namen.
- Kürzung auf 63 Byte und Kollisionen bleiben für lange Tabellen- und Spaltennamen.

## Empfehlung

**A**, in zwei Schritten, mit C als getrennter, späterer Entscheidung:

1. **Modell, Datei, Erzeugen, Up und Down, Vergleich, Fingerabdruck** wie oben. Der Konsument aus #76 schreibt dann
   `type: enum` mit `values_constraint`, und seine Constraint-Namen bleiben stabil, ohne SQL-Text im Schema.
2. **C nicht jetzt.** Es ändert das Ergebnis für jeden PostgreSQL-Anwender, um ein Problem zu lösen, das mit A für
   den Fall mit Namen bereits entfällt. Der unbenannte Fall bleibt in Datei gegen Datei blockiert, mit einer
   Diagnose, die den Ausweg nennt: `values_constraint` setzen oder gegen die Datenbank migrieren.

Gegen A spricht der Aufwand: neues YAML-Feld und `schema.json`, vier Renderer (Erzeugen, Up, Down), Vergleich,
Fingerabdruck, Tests je Dialekt (live für PostgreSQL), Dokumentation. Er ist gemessen an den vier Defekten, die der
rohe CHECK bisher erzeugt hat, vertretbar. Wenn das Gewicht nicht stimmt, ist **B** die ehrliche Alternative:
dann sagt das Handbuch, wofür der rohe CHECK da ist, und nimmt dem Konsumenten die Erwartung, dass `enum` dasselbe
kann.

## Offene Fragen für die Entscheidung

1. **A, B oder C?** (Empfehlung: A, C später.)
2. **Feldname:** `values_constraint` (Empfehlung; parallel zu `unique_constraint`) oder `check_name`.
3. **MySQL:** Warnung oder Fehler, wenn das Feld gesetzt ist? Empfehlung: Warnung. Der Name hat dort kein Gegenstück,
   das Schema soll trotzdem auf allen Dialekten laufen.
4. **Namensänderung als Änderung:** Soll ein gesetzter Name, der von dem in der Datenbank abweicht, `DROP`/`ADD`
   planen (Empfehlung), oder bleibt der Name für den Vergleich ohne Wirkung (wie heute bei unbenannten CHECKs)?

## Konsequenzen, falls A angenommen wird

- `schema-fingerprint-v26`; Plan- und Rücknahme-Artefakte früherer Versionen sind neu zu erzeugen (wie bei v25).
- `values_constraint` an einer Spalte ohne Inline-Enum (anderer Typ, `ref_type`) ist ein Validierungsfehler.
- ADR 0055 und ADR 0067 bleiben gültig; der Name ist eine weitere Dimension neben dem Wertevorrat und ändert die
  Gleichsetzung der Formen nicht.
