---
id: identity-column-shape-mismatch
title: "Zwei Schreibweisen fuer dieselbe Identity-Spalte — die naheliegende plant eine blockierte Typaenderung"
status: open
---

# `identifier + auto_increment` gegen `Integer + generation`

## Befund

Eine Oracle-IDENTITY-Spalte laesst sich im neutralen Modell auf zwei
Arten hinschreiben:

```yaml
# (a) naheliegend
id: { type: identifier, auto_increment: true }

# (b) was der Reverse liefert
id: { type: integer, generation: { type: identity, mode: always } }
```

`OracleTypeMapping.mapIdentity` legt eine gelesene IDENTITY-Spalte als
**(b)** ab: der Typ ist der reine numerische Typ (`Integer` bei Praezision
≤ 9), die Identity-Eigenschaft steckt in `generation`.

Live gemessen (2026-09-06, Round-Trip gegen
`gvenzl/oracle-free:23-slim-faststart`, Tabelle unveraendert):

| Soll-Schreibweise | Plan | Ausgang |
|---|---|---|
| (a) `Identifier(autoIncrement = true)` + `generation` | `AlterColumnType` auf `id` | **Exit 8**, `ORACLE_ADD_IDENTITY_UNSUPPORTED` |
| (b) `Integer` + `generation` | leer | Exit 0 |

In beiden Faellen ist die Spalte in der Datenbank **unveraendert**. Der
Blocker ist inhaltlich korrekt (Oracle kann eine Spalte nicht
nachtraeglich zur Identity-Spalte machen, `ORA-30673`) — nur ist die
Operation ueberhaupt nicht noetig.

Beide Schreibweisen sind im Fingerabdruck aequivalent: die
Typ-Kanonisierung faltet (a) auf `type=integer`, also auf dasselbe wie
(b). Der **Vergleich**, der den Plan erzeugt, tut das nicht.

## Warum das zaehlt

(a) ist die Form, die ein Anwender schreibt, der ein Schema von Hand
verfasst — `auto_increment` ist die dokumentierte Art, eine
Autowert-Spalte auszudruecken. Sie fuehrt gegen eine bestehende Oracle-
Tabelle zu einem Blocker auf einer Spalte, an der sich nichts geaendert
hat, mit einer Meldung, die von einem Tabellen-Neubau spricht.

## Offen

Ob das Oracle-spezifisch ist, wurde **nicht** geprueft. Zu klaeren:

1. Verhalten sich MSSQL und PostgreSQL an derselben Stelle gleich?
   (`MssqlTypeMapping`/`PostgresTypeMapping` legen Identity-Spalten
   ebenfalls als Typ + `generation` ab.)
2. Ist der richtige Ort der Vergleich (`SchemaComparator` sollte beide
   Schreibweisen als gleich sehen), der Validator (eine der beiden
   Formen ablehnen), oder die Doku (eine als kanonisch benennen)?

## Herkunft

Beim Bau von `OracleMigrateRoundTripIntegrationTest` (Oracle-Sub-Slice
5e-3) aufgefallen — der erste Live-Beleg fuer `schema migrate` gegen
Oracle. Ein Unit-Test haette es nicht gezeigt: er haette beide Seiten in
derselben Schreibweise gebaut.
