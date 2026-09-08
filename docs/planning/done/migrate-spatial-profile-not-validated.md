---
id: migrate-spatial-profile-not-validated
title: "`schema migrate` prueft `--spatial-profile` nicht — ein Tippfehler verschwindet still"
status: resolved
---

# `--spatial-profile` wird auf dem Migrate-Pfad nicht validiert

> **Erledigt, beide Teile.**
>
> - **Geprueft.** `SchemaMigrateRunner` fragt `SpatialProfilePolicy.resolve`,
>   bevor irgendeine Verbindung aufgeht — unbekannter Name und dialektfremdes
>   Profil enden mit Exit 2 und einer Meldung, die die erlaubten Werte nennt.
>   Dieselbe Pruefung wie auf dem Generate-Pfad.
> - **Gelesen.** `SpatialProfileStage` haelt `none` auch auf dem Migrate-Pfad
>   ein: ein Plan, der Geometrie einfuehrt, wird mit `E052` blockiert
>   (Exit 8), statt sie am Profil vorbei zu rendern.
>
> **Der Lauf wird abgelehnt, nicht die einzelne Tabelle** — anders als auf
> dem Generate-Pfad, wo `E052` mit `blocksTable` eine Tabelle ueberspringt.
> Ein Migrationsplan ist abhaengigkeitssortiert; eine Tabelle daraus zu
> entfernen liesse die uebrigen Anweisungen auf etwas verweisen, das nicht
> entsteht.
>
> Nebenbefund mitbehoben: die Wertetabelle in `spec/cli-spec.md` fuehrte nur
> drei Dialekte, und `--spatial-profile` fehlte in der Flag-Tabelle von
> `schema migrate` ganz.

## Befund

`SchemaMigrateRenderPipeline` loeste das Profil so auf:

```kotlin
val spatialProfile = request.spatialProfile?.let { SpatialProfile.fromCliName(it) }
    ?: SpatialProfilePolicy.defaultFor(dialect)
```

`SpatialProfilePolicy.resolve` / `allowedFor` wurden nie befragt. Die
Schwesterkommandos machen es anders: `SchemaGenerateRunner`,
`ToolExportRunner` und der MCP-`SchemaGenerateHandler` melden sowohl
`UnknownProfile` als auch `NotAllowedForDialect` mit Exit 2.

Zwei Fehlszenarien, beide **still**:

1. **Tippfehler.** `--spatial-profile postgs` → `fromCliName` liefert
   `null`, das Elvis setzte lautlos den Default ein. Kein Exit 2, keine
   Meldung — der Anwender glaubte, das Profil sei aktiv.
2. **Dialektfremdes Profil.** `--dialect oracle --spatial-profile postgis`
   wurde angenommen und an den Renderer gereicht, obwohl Oracles Allowlist
   es nicht fuehrt.

Nicht Oracle-spezifisch — der Pfad ist fuer alle fuenf Dialekte gleich.

Der zweite Teil: der Migrate-Pfad las `options.spatialProfile` fuer Oracle
**nirgends** — er rendert `SDO_GEOMETRY` unabhaengig vom Profil.
`--spatial-profile none` blieb auf `schema migrate` also wirkungslos,
waehrend es auf `schema generate` die Tabelle mit `E052` blockt. Dasselbe
galt fuer SQL Server; nur der SQLite-Renderer wertete das Profil im
Diff-Pfad aus.

## Live verifiziert

`SchemaMigrateSpatialProfileE2ETest` (`test/e2e-cli`) faehrt die echte CLI
als eigenen Prozess, containerlos (Datei-zu-Datei, `--plan-only`): Tippfehler
→ Exit 2, dialektfremdes Profil → Exit 2, `none` mit Geometrie im Plan →
Exit 8 mit `E052`, und derselbe Plan unter dem Dialekt-Default → Exit 0 ohne
Blocker.
