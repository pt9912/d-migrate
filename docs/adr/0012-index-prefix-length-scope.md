---
status: accepted
date: 2026-06-17
decision-makers: pt9912
consulted: docs/planning/done/index-prefix-length-model.md (D-4), Regelwerk Modul 5 (Scope-Schnitt)
informed: Driver-Generatoren MySQL/PostgreSQL/SQLite; Schema-Modell-Pflege
---

# Index-Präfixlänge nur auf Index-Spalten — PK-/Constraint-Spalten tragen keine Länge

## Kontext und Problemstellung

Die Präfixlängen-Modellscheibe
([`index-prefix-length-model.md`](../planning/done/index-prefix-length-model.md))
hat das Modellfeld `IndexColumn.prefixLength` eingeführt, damit MySQL-Präfix-
Indizes (`CREATE INDEX i ON t (body(100))`) round-trip-fähig sind.

MySQL erlaubt Präfixlängen jedoch nicht nur in eigenständigen Indizes, sondern
auch in **PRIMARY KEY** und **UNIQUE**-Constraints:

```sql
PRIMARY KEY (body(100))
UNIQUE KEY uq_body (body(50))
```

Im neutralen Schema-Modell werden diese Spaltenlisten **als reine
`List<String>`** geführt:

- `TableDefinition.primaryKey: List<String>`
- `ConstraintDefinition.columns: List<String>`

Eine `List<String>` kann keine Per-Spalten-Präfixlänge tragen. Die Scheibe stand
damit vor der Frage, ob sie diese beiden Strukturen für Präfixlängen mit
aufbohrt (strukturierte Spalten-Einträge statt nackter Strings) oder den
Präfix-Support bewusst auf Index-Spalten beschränkt.

## Entscheidung

**Präfixlängen werden in 0.9.9 ausschließlich auf `IndexColumn` modelliert.**
`TableDefinition.primaryKey` und `ConstraintDefinition.columns` bleiben
`List<String>` und tragen **keine** Präfixlänge.

Begründung:

- Der häufige, vom Piloten getroffene Fall ist der **eigenständige Präfix-Index**
  auf `TEXT`/`BLOB` — genau der ist abgedeckt.
- **UNIQUE als Index** ist ebenfalls abgedeckt: der MySQL-Reverse mappt
  `UNIQUE KEY` auf `IndexDefinition(unique = true)` (nicht auf
  `ConstraintDefinition`), läuft also über `IndexColumn` und erbt `prefixLength`.
- Offen bleiben damit nur das **literale `PRIMARY KEY`** und die
  **PG-artige Constraint-Modellierung** von UNIQUE (`ConstraintDefinition`).
- Beide Strukturen für Präfixlängen aufzubohren ist eine modellweite Änderung
  (Serialisierung, Vergleich, Fingerprint, alle Dialekte) für einen selteneren
  Fall — sie verdient einen eigenen Slice statt eines Anhängsels.

## Konsequenzen

- **Round-Trip-Lücke (bewusst):** Eine MySQL-Tabelle mit einer Präfixlänge im
  `PRIMARY KEY` oder in einem Constraint-modellierten `UNIQUE KEY` über einer
  `TEXT`/`BLOB`-Spalte verliert die Länge beim Reverse. Beim Regenerieren würde
  ein `PRIMARY KEY (body)` ohne Länge emittiert, was MySQL mit `ERROR 1170`
  ablehnt. Dieser Fall ist selten (Präfix-PK auf Text); er ist hier als bewusste
  Einschränkung festgehalten, **nicht** stillschweigend.
- **Kein neues Modell-Carve-out im Code:** Es wird kein nullable Präfix-Feld an
  die generischen Spaltenlisten gehängt. Die Strukturen bleiben unverändert.
- Abgedeckt bleibt der Pilot-Blocker (eigenständiger Präfix-Index, I-08-MySQL)
  vollständig.

## Aktivierungsbedingung

Sobald ein Pilot- oder Anwenderfall Präfixlängen in `PRIMARY KEY`/Constraints
benötigt, aktiviert der Trigger
[`pk-constraint-prefix-length.md`](../planning/open/pk-constraint-prefix-length.md)
einen Folge-Slice: strukturierte Spalten-Einträge (Name + optionale Länge) für
`primaryKey`/`ConstraintDefinition.columns`, durchgezogen über Serialisierung,
Vergleich, Fingerprint und alle Dialekte.
