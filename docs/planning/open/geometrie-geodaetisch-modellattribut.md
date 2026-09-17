# Modellfrage: ein Attribut „geodätisch" für Geometrien

> **Status:** Vorabklärung / Modellfrage (2026-09-17)
> **Trigger:** Eigner-Entscheidung F1 beim Schnitt des Reader-Slices
> ([`../next/reader-treue-3-spatial.md`](../next/reader-treue-3-spatial.md),
> Posten A5): PostgreSQL `geography` wird **nur auf dem Rückweg** als
> Geometrie mit SRID gelesen, mit einer Note, dass PostgreSQL → PostgreSQL
> daraus `geometry` wird. Vorwärts rendert PostgreSQL weiter `geometry`. Ein
> Modell-Attribut, das „geodätisch" trägt, ist dabei ausdrücklich **nicht**
> gebaut, sondern hierher gelegt.
> **Aktivierungsbedingung:** ein belegter Fidelity-Bedarf, bei dem die Note
> nicht reicht (etwa ein PostgreSQL → PostgreSQL-Weg mit `geography`, dessen
> Abfragen an der Einheit hängen), und eine Eigner-Entscheidung für die
> Modellerweiterung. Dann entsteht ein `next/`-Plan samt ADR nach dem Muster
> von [ADR 0015](../../adr/0015-fulltext-tsvector-neutral-type.md).

## Worum es geht

PostGIS kennt zwei Spaltenarten: `geometry` rechnet planar in den Einheiten
seines Bezugssystems, `geography` auf dem Ellipsoid in Metern. Bei SRID 4326
liefert `ST_Distance` auf `geometry` also Grad, auf `geography` Meter. Das
neutrale Modell kennt nur `geometry` mit optionalem `srid`
([`spec/neutral-model-spec.md`](../../../spec/neutral-model-spec.md) führt
`geography` ausdrücklich als „nicht Teil des neutralen Geometry-Modells").

Heute trägt allein der **SRID** die Unterscheidung, und nur SQL Server wertet
ihn aus: ein SRID im EPSG-Geographic-Block 4000–4999 ergibt dort `geography`
([`spec/type-mapping.md`](../../../spec/type-mapping.md), Abschnitt 6.4).
PostgreSQL rendert jede Geometrie als `geometry(<Subtyp>, <srid>)`
([`spec/ddl-generation-rules.md`](../../../spec/ddl-generation-rules.md),
Abschnitt 16.2). Nach F1 gilt deshalb: eine `geography`-Spalte übersteht den
Weg PostgreSQL → PostgreSQL nicht; sie kommt als `geometry` mit demselben SRID
an, und Abfragen, die mit Metern rechnen, rechnen danach in Grad. Der
Spatial-Plan macht das laut, behebt es aber nicht.

Das Lastenheft nennt `GEOGRAPHY` als PostGIS-Zielform
([`LF-003`](../../../spec/lastenheft-d-migrate.md#lf-003)). Ohne Attribut
entsteht sie aus einer neutralen Datei nie.

## Warum der naheliegende Weg nicht gewählt ist

PostgreSQL könnte vorwärts wie SQL Server nach dem SRID-Bereich wählen. Dann
würde aber **jede** `geometry(Point,4326)`-Spalte zu `geography` — auch jede,
die heute bewusst planar ist und so im Golden
`spatial.postgresql.sql` steht. Das ändert die Bedeutung bestehender
Schemata. F1 hat diesen Weg deshalb ausgeschlossen; der Spatial-Plan führt
die Gegenprobe (`geometry(Point,4326)` bleibt `geometry`).

## Die Wege

| Weg | Wirkung | Preis |
| --- | ------- | ----- |
| Attribut am neutralen `geometry` (etwa eine Angabe „geodätisch") | PostgreSQL → PostgreSQL erhält `geography`; jedes Ziel entscheidet mit einer eigenen Render-Regel | Modellerweiterung: `spec/neutral-model-spec.md`, `spec/schema.json`, JSON-Schema-Golden, eine Render-Regel je Dialekt, Folgen für Fingerabdruck und Vergleich |
| so lassen | die Note des Spatial-Plans benennt den Verlust | `geography` entsteht aus einer neutralen Datei nie |

## Was eine Entscheidung beantworten muss

- Wie verhält sich das Attribut zum SRID? SQL Server wählt heute nach dem
  SRID; ein Attribut und ein SRID außerhalb des geodätischen Blocks können
  sich widersprechen.
- Was rendern MySQL, SQLite/SpatiaLite und Oracle daraus? Je Dialekt ist zu
  messen, ob es eine geodätische Spaltenform gibt oder ob eine Note entsteht.
- Wie liest der SQL-Server-Reverse `geography` dann? Heute als `srid: 4326`
  mit `R345`.
- Ändert das Attribut den Fingerabdruck (eine neue Projektion) und damit
  bestehende Rollback-Artefakte und Overlay-Pins?

## Berührt

- [`spec/neutral-model-spec.md`](../../../spec/neutral-model-spec.md) — das
  Geometry-Modell.
- [`spec/type-mapping.md`](../../../spec/type-mapping.md) — PostgreSQL und
  Abschnitt 6.4.
- [`spec/ddl-generation-rules.md`](../../../spec/ddl-generation-rules.md) —
  Abschnitt 16 je Dialekt.

## Referenzen

- Befund und Entscheidung F1:
  [`../next/reader-treue-3-spatial.md`](../next/reader-treue-3-spatial.md)
  (Posten A5).
- Muster für eine Modellerweiterung:
  [ADR 0015](../../adr/0015-fulltext-tsvector-neutral-type.md).
