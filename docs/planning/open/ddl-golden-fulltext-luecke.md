# Tracker: kein DDL-Golden deckt Volltext ab

> **Status:** Tracker / Vorabklärung (29.08.2026)
> **Trigger:** Beim Review von Sub-Slice 8b gemessen: keine Fixture unter
> `adapters/driven/formats/src/test/resources/fixtures/schemas/` trägt einen
> `fulltext`-Index.
> **Aktivierungsbedingung:** Wird priorisiert → direkt umsetzbar, kein Plan
> nötig.

## Befund

`DdlGoldenMasterTest` vergleicht erzeugtes DDL gegen festgeschriebene Dateien
und ist damit das Netz, das eine ungewollte Änderung am Rendern auffängt.
Volltext liegt außerhalb dieses Netzes — für **alle vier** Dialekte, nicht nur
für SQL Server.

Die Unit-Tests decken den Fall ab, aber sie prüfen einzelne Zusicherungen; ein
Golden prüft die vollständige Ausgabe, auch die Teile, an die beim Ändern
niemand denkt.

## Warum eine eigene Fixture und nicht `full-featured.yaml`

Ein `fulltext`-Eintrag dort bewegte **alle vier** Dialekt-Goldens auf einmal,
weil PostgreSQL, MySQL, SQLite und SQL Server Volltext je eigen rendern. Eine
kleine eigene Fixture ist der schonendere Schnitt und macht den Diff lesbar,
wenn sich am Volltext-Rendern etwas ändert.
