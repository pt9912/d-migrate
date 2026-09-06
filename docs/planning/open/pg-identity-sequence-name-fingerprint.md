---
id: pg-identity-sequence-name-fingerprint
title: "PostgreSQL-IDENTITY-Spalten koennen nach `migrate --execute` falsche Drift melden (Sequenzname im Fingerabdruck)"
status: open
---

# Der PG-Sequenzname einer IDENTITY-Spalte geht in den Fingerabdruck ein

## Befund

`ColumnGeneration.Identity.sequenceName` wird von **keinem** Dialekt
gerendert — auch PostgreSQL nicht:
`PostgresColumnConstraintHelper.buildIdentityClause` schreibt
`"<basetyp> GENERATED <modus> AS IDENTITY"`, ohne `(SEQUENCE NAME …)`.
Der PG-Reverse liest den Namen aber sehr wohl
(`PostgresTypeMapping.identityGeneration` → `sequenceName =
input.generatedSequenceName`), und zwar **schema-qualifiziert**
(`public.orders_id_seq`, so auch im Reader-Test gepinnt).

`FingerprintValueProjection.generation` bettet den Wert unveraendert ein.
Ein user-authored Soll-Schema kann ihn nicht tragen (er entsteht erst beim
`CREATE TABLE`), das zurueckgelesene Ist-Schema traegt ihn.

**Fehlszenario:** ein `desired` mit einer `biginteger`-Spalte und
explizitem `generation: {type: identity}`. `schema migrate --execute`
gegen PostgreSQL legt sie korrekt an; der Post-Compare vergleicht das Soll
(`sequenceName = null`) gegen das frisch gelesene Ist
(`sequenceName = "public.orders_id_seq"`) → Fingerabdruecke ungleich →
**Drift gemeldet fuer eine exakt wie gewuenscht angewendete Migration.**
Auch ein von Hand geschriebener `sequence_name: orders_id_seq` hilft
nicht: unqualifiziert gegen qualifiziert driftet ebenso.

Die 32-Bit-Variante (`Identifier(autoIncrement = true)`) ist nicht
betroffen — sie laeuft ohne `generation` durch.

## Warum nicht im Oracle-Sub-Slice 5e-2 mitbehoben

Der Mechanismus dafuer steht seit 5e-2: der Fingerabdruck-Vertrag hat
einen `canonicalizeGeneration`-Hook, gesteuert ueber
`DialectCapabilities.namesIdentitySequences`. Fuer Oracle steht die
Faehigkeit auf `false` und der Name wird herausprojiziert.

PostgreSQL auf `false` zu stellen waere eine Zeile — aber es **aendert
bestehende PostgreSQL-Fingerabdruecke**. Ein Rollback-Artefakt, das vor
der Umstellung erzeugt wurde, traegt den Abdruck mit Sequenznamen; danach
rechnet `schema rollback` einen anderen aus und lehnt mit
`TARGET_STATE_MISMATCH` ab. Betroffen sind genau die Schemata, um die es
geht (PG mit reverse-gelesenen IDENTITY-Spalten).

Fuer Oracle stellte sich die Frage nicht: dort gab es nie ein Artefakt,
weil `schema migrate` bis 5e-2 gegated war.

## Moegliche Loesungsrichtungen

1. **`namesIdentitySequences = false` fuer PostgreSQL** plus Anhebung von
   `MigrationFingerprint.ALGORITHM` auf `v10`. Sauber, aber die Anhebung
   entwertet **alle** bestehenden Artefakte, nicht nur die betroffenen.
2. **Nur die Faehigkeit umstellen, ohne Versionsanhebung.** Billig und
   trifft nur die betroffenen Schemata — bricht dort aber still, statt
   mit einer Meldung, die den Grund nennt.
3. **Den Namen gar nicht mehr lesen** (PG-Reverse setzt `sequenceName`
   nicht mehr). Loest es an der Wurzel, kostet aber Informationswert im
   `schema reverse`-Ergebnis, den Slice 1 fuer Oracle ausdruecklich
   erhalten wollte (Grants/Monitoring auf der Sequenz).

Richtung 1 ist die ehrliche, Richtung 2 die pragmatische; die Wahl
gehoert zu einer Entscheidung ueber Artefakt-Kompatibilitaet, nicht in
einen Dialekt-Slice.

## Herkunft

Aufgefallen im Review des Oracle-Sub-Slice 5e-2. Der Vermerk stand schon
im 4a-Ticket `oracle-identity-sequence-fingerprint-drift.md`
(„Betrifft potenziell auch PostgreSQL …"); dieses Ticket ist mit 5e-2
geloest und geloescht worden, und der PG-Rest waere dabei ersatzlos
verschwunden.
