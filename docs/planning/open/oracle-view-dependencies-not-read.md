---
id: oracle-view-dependencies-not-read
title: "Oracles Reverse liest keine View-Abhaengigkeiten — Sichten brechen still beim Tabellen-Rename"
status: open
---

# View-Abhaengigkeiten fehlen im Oracle-Reverse

## Befund

Eine Oracle-View ueberlebt das Umbenennen ihrer Basistabelle **nicht**: sie
geht auf `INVALID`, und jede Abfrage darauf scheitert mit `ORA-04063: view
… has errors`. Live gemessen (2026-09-06,
`gvenzl/oracle-free:23-slim-faststart`, Sub-Slice-5c-Sonde):

```sql
CREATE TABLE b_old (id NUMBER(9));
CREATE OR REPLACE VIEW b_v AS SELECT id FROM b_old;
ALTER TABLE b_old RENAME TO b_new;
-- user_objects.status = INVALID; SELECT ... FROM b_v -> ORA-04063
```

PostgreSQL zieht abhaengige Sichten bei einem Tabellen-Rename automatisch
nach (es fuehrt die Abhaengigkeit ueber OIDs, nicht ueber den Namen) — die
Bruchstelle ist Oracle-spezifisch.

## Warum das heute nicht greift

Der Mechanismus existiert bereits:
`RenameViewReprojector.reprojectViewsForTableRename`
(`RenameDependencyPolicy.kt`) erzeugt fuer betroffene Sichten ein
`DropView` + `CreateView` aus dem Zielrumpf, und die Policies von
PostgreSQL, MySQL, SQLite und MSSQL rufen ihn auf.

Fuer Oracle fehlen **zwei** Stuecke:

1. **Keine Policy.** `RenameDependencyPolicy.forDialect(ORACLE)` ist ein
   `error(...)`-Stub, dessen Begruendung („DialectCommandGate blocks schema
   migrate for oracle before Slice 5") mit Slice 5 selbst veraltet ist.
   `ObjectRenamePolicyRegistry` fuehrt ORACLE ebenfalls nicht (`getValue`
   wuerfe).
2. **Keine Daten.** Selbst mit Policy faende der Reprojector nichts:
   `OracleSchemaReader.readViews` befuellt `ViewDefinition.dependencies`
   nicht. Oracle stellt die Information ueber `ALL_DEPENDENCIES` bereit.

## Einordnung

Nicht von Sub-Slice 5c verursacht — der View-Renderer gibt wieder, was die
Operation sagt, und ein `RenameTable` erzeugt gar keine View-Operation.

Teil (1) ist eine **harte Vorbedingung fuer Sub-Slice 5e**: sobald der
Gate-Fall `schema migrate` fuer Oracle oeffnet, waehlt
`SchemaMigrateRunner` die Rename-Policy ueber `RenameProjectionDialect` aus
und liefe in den `error(...)`-Stub. Das ist im 5e-Eintrag des
Slice-Schnitts vermerkt.

Teil (2) ist die eigentliche Fidelity-Luecke und kann auch danach noch
nachgezogen werden: ohne sie bleibt der Reprojector fuer Oracle wirkungslos
und eine per `migrate` umbenannte Tabelle laesst ihre Sichten invalid
zurueck.

## Moegliche Loesungsrichtungen

1. `OracleSchemaReader.readViews` um eine `ALL_DEPENDENCIES`-Abfrage
   erweitern (`referenced_name`/`referenced_type = 'TABLE'`), damit
   `ViewDefinition.dependencies` traegt.
2. `OracleRenameDependencyPolicy` nach dem Muster der vier bestehenden
   Policies bauen und in beide Registries eintragen.
3. Als Zwischenschritt fuer 5e: eine Policy, die den Reprojector aufruft,
   aber mangels Abhaengigkeitsdaten nichts findet — sie ersetzt den
   `error(...)` durch definiertes Verhalten, schliesst die Fidelity-Luecke
   aber nicht.
