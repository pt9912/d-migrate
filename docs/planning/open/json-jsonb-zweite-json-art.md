# Modellfrage: zweite JSON-Art (`json` gegen `jsonb`)

> **Status:** **Geschlossen (2026-09-18) — P8 ist gebaut.** Der
> PostgreSQL-Reverse meldet eine `json`-Spalte mit `R402` (`WARNING`), an der
> Spalte, am Element eines `json[]` (der Generator rendert es seit S3
> als `JSONB[]`) **und** am Feld eines zusammengesetzten Typs (`CREATE TYPE …
> AS (…)` schreibt den Feldtyp durch denselben Mapper wie eine Spalte, also
> als `jsonb`); `jsonb` meldet nichts. Die Regel steht in
> [`spec/type-mapping.md`](../../../spec/type-mapping.md), Abschnitt 3.3.
> Entschieden am 2026-09-16, gebaut als P8 in Plan 2 des Reader-Slices
> ([`../in-progress/reader-treue-2-meldungen.md`](../in-progress/reader-treue-2-meldungen.md)).
> Eine spätere Trennung in zwei neutrale JSON-Arten wäre eine neue
> Modellfrage mit eigenem Eintrag; der Text unten bleibt als
> Entscheidungsgrundlage stehen.
> **Eigner-Entscheidung: gleichsetzen, aber laut.** Das Modell behält **einen**
> JSON-Typ (keine Modellerweiterung). Der PostgreSQL-Reverse meldet eine
> `json`-Spalte mit eigenem Code: sie wird als `jsonb` gerendert, und was `json`
> bewahrt (Schlüsselreihenfolge, doppelte Schlüssel, Leerraum), geht dabei
> verloren. Das folgt dem Ziel des Reader-Slices — kein Verlust bleibt still. Die
> Spec begründet den Rückweg mit.
> **Umsetzung (2026-09-17):** wird in **P8** gebaut, Code `R402`, Severity
> `WARNING` (beim Übertragen ändern sich die Daten). Beim Schnitt in vier
> Pläne kam dazu: `json[]` liest mit Element `json`, rendert heute aber gar
> nicht als `jsonb[]`, sondern als `TEXT[]` — der PostgreSQL-Generator kennt
> nur vier Element-Typen (Posten S3 desselben Plans). Die Note für `json[]`
> kommt deshalb erst nach S3; vorher wäre ihre Aussage falsch.
> **Trigger:** Konsumentenmessung gegen 1.7.1 (Posten B3 des Reader-Slices,
> jetzt in [`../in-progress/reader-treue-2-meldungen.md`](../in-progress/reader-treue-2-meldungen.md)): eine PostgreSQL-`json`-Spalte kommt nach Reverse und erneuter
> Renderung als `jsonb` zurueck. Der Vorgang ist **spec-konform** (s. u.) — die
> Frage ist, ob er es bleiben soll.
> **Aktivierungsbedingung:** erfüllt — der Eigner hat „gleichsetzen, aber
> laut" entschieden. Eine spätere Trennung (zweiter neutraler Typ oder ein
> Attribut) wäre eine neue Modellfrage mit eigenem Eintrag. Der Text unten ist
> die Entscheidungsgrundlage vom 2026-09-16.

## Worum es geht

`PostgresTypeMapping.kt:142` bildet **beide** PG-Typen auf denselben neutralen
`Json` ab; der PG-Generator rendert daraus `JSONB`. Eine `json`-Spalte verliert
damit ihre Art — ohne Finding, aber auch ohne Vertragsbruch:
`spec/neutral-model-spec.md:148` fuehrt genau **eine** Zeile `json` → PG `JSONB`
(MySQL `JSON`, SQLite `TEXT`). Der Rueckweg `json → json` mit erneuter Renderung
nach `JSONB` ist also die kanonische Form des Modells.

Die Tabelle ist eine **Soll**-Tabelle fuer die **Render**richtung und sagt zum
Rueckweg `jsonb` → `json` nichts. Genau dort sitzt die Frage: das Modell
unterscheidet die beiden nicht, und `json` ist die verlustbehaftetere der beiden
Arten (kein Index, keine Gleichheit, keine Ordnung).

## Warum das nicht nebenbei gebaut wird

- **Kein Native-Passthrough.** Rohe Dialekt-Typ-Strings werden nicht durchs
  neutrale Modell gereicht; ein zweiter Typ wird *abstrahiert* modelliert
  (`geometry`-/`fulltext`-Muster) oder es bleibt beim Ist-Zustand.
- **Drei Dialekte, nicht einer.** PG kennt `json`/`jsonb`, MySQL nur `JSON`,
  SQLite gar keinen eigenen Typ (heute `TEXT`). Eine Trennung muss fuer alle drei
  beantworten, was sie erzeugt — sonst entsteht dieselbe stille Degradierung auf
  einem Nachbarweg.
- **Der bestehende Tracker traegt sie nicht.**
  [`pg-only-types-first-class-candidates.md`](pg-only-types-first-class-candidates.md)
  schliesst `json`/`jsonb` ausdruecklich aus (`:24`: „bereits gemappt und
  **nicht** betroffen") — dieser Eintrag ist deshalb eigenstaendig.

## Abgrenzung zur Nachbarschaft

Nicht zu verwechseln mit dem **Array**-Verlust (Slice-Posten B1/B2): dort geht
eine Modelleigenschaft verloren, die das Modell **kennt** (`NeutralType.Array`),
und der Verlust entsteht erst am MySQL- und am SQLite-Renderer. Hier kennt das Modell die
Unterscheidung gar nicht — es ist eine Modellfrage, keine Render-Luecke.

## Referenzen

- Befund und Belege (im Slice als Posten B3 gefuehrt):
  [`../in-progress/reader-treue-2-meldungen.md`](../in-progress/reader-treue-2-meldungen.md).
- Soll-Tabelle des Modells: [`spec/neutral-model-spec.md`](../../../spec/neutral-model-spec.md).
- Muster fuer eine solche Erweiterung:
  [`ADR 0015`](../../adr/0015-fulltext-tsvector-neutral-type.md) (`tsvector` → `fulltext`).
  (Die frühere Fassung nannte hier auch ADR 0016; der regelt den
  SpatiaLite-Bootstrap, keine Modellerweiterung.)
