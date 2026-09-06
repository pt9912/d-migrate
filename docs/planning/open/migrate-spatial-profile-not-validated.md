---
id: migrate-spatial-profile-not-validated
title: "`schema migrate` prueft `--spatial-profile` nicht — ein Tippfehler verschwindet still"
status: open
---

# `--spatial-profile` wird auf dem Migrate-Pfad nicht validiert

## Befund

`SchemaMigrateRenderPipeline` loest das Profil so auf:

```kotlin
val spatialProfile = request.spatialProfile?.let { SpatialProfile.fromCliName(it) }
    ?: SpatialProfilePolicy.defaultFor(dialect)
```

`SpatialProfilePolicy.resolve` / `allowedFor` werden nie befragt. Die
Schwesterkommandos machen es anders: `SchemaGenerateRunner`,
`ToolExportRunner` und der MCP-`SchemaGenerateHandler` melden sowohl
`UnknownProfile` als auch `NotAllowedForDialect` mit Exit 2.

Zwei Fehlszenarien, beide **still**:

1. **Tippfehler.** `--spatial-profile postgs` → `fromCliName` liefert
   `null`, das Elvis setzt lautlos den Default ein. Kein Exit 2, keine
   Meldung — der Anwender glaubt, das Profil sei aktiv.
2. **Dialektfremdes Profil.** `--dialect oracle --spatial-profile postgis`
   wird angenommen und an den Renderer gereicht, obwohl Oracles Allowlist
   nur `NONE` enthaelt.

Nicht Oracle-spezifisch — der Pfad ist fuer alle fuenf Dialekte gleich.
Oracle ist nur der einzige Dialekt mit einelementiger Allowlist und
faellt deshalb am staerksten auf.

## Aktivierungsbedingung

Fall 1 wirkt fuer jeden Dialekt und jederzeit. Fall 2 wurde mit dem
Oracle-Gate-Fall (Sub-Slice 5e-2) erreichbar; die Oracle-Renderer blocken
Geometrie-Spalten seitdem eigenstaendig
(`ORACLE_SPATIAL_UNSUPPORTED`), sodass kein `SDO_GEOMETRY` entsteht — die
fehlende Optionspruefung bleibt davon unberuehrt.

## Moegliche Loesungsrichtung

`SpatialProfilePolicy.resolve(...)` an derselben Stelle aufrufen, an der
`SchemaGenerateRunner` es tut, und beide Fehlerformen mit Exit 2 melden.
Der Pruefpfad existiert bereits; er wird auf dem Migrate-Pfad nur nicht
benutzt.
