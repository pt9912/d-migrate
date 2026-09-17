# Vertragsfrage: E052 verwirft die ganze Tabelle (SpatiaLite-Profil)

> **Status:** Entschieden (2026-09-16) — wandert bei der Aktivierung des
> Reader-Slices als eigenes Paket dorthin (Posten A6).
> **Eigner-Entscheidung: NOT NULL nativ.** Gemessen im Tooling-Image
> (SpatiaLite 5.1.0): `AddGeometryColumn('t','geom',4326,'POINT','XY',1)` legt die
> Spalte als `"geom" POINT NOT NULL DEFAULT ''` an, eine Zeile ohne Geometrie wird
> abgewiesen (`violates Geometry constraint`), eine mit Geometrie angenommen.
> `NOT NULL` ist damit **keine** Metadatenlücke von SpatiaLite; heute blockiert der
> Generator trotzdem (`column.required` in der Konfliktprüfung von
> `SqliteTableDdlSupport`). Gebaut wird: `required` über das `not_null`-Argument,
> `E052` bleibt nur für PK, UNIQUE, Default und Fremdschlüssel. Die Regel „keine
> partielle DDL" bleibt; die Spec-Stellen unten nennen `NOT NULL` nicht mehr als
> Auslöser. Einen ADR braucht es dafür nicht: ADR 0016 hat den Generate-Pfad
> aufgeschoben, nicht festgelegt.
> **Umsetzung (2026-09-17):** wird in **P7** des Reader-Slices gebaut
> ([`../next/reader-treue-3-spatial.md`](../next/reader-treue-3-spatial.md),
> Aktivierungsschnitt); dieser Eintrag schliesst mit dessen Graduation, nicht
> vorher. Beim Schneiden nachgemessen und dort festgehalten: die drei
> Spec-Stellen unten haben `NOT NULL` **nie** als Auslöser genannt — die
> Auslöser stehen nirgends in `spec/`, P7 trägt sie erstmals in den
> Profilabschnitt 16.5 ein; dieselbe Regel steht ein zweites Mal im
> Migrate-Pfad (`SqliteSpatialDiffOps`); und der SQLite-Reverse liest
> SpatiaLites `DEFAULT ''` sonst als Anwender-Default zurück.
> **Trigger:** Konsumentenmessung gegen 1.7.1, festgehalten in
> [`../next/reader-treue-3-spatial.md`](../next/reader-treue-3-spatial.md)
> (Posten A6): `schema generate --target sqlite --spatial-profile spatialite` auf
> einer MySQL-Quelle mit **NOT NULL**-Geometrie erzeugt `E052` und laesst die
> **komplette** Tabelle aus der Ausgabe fallen — die uebrigen, darstellbaren
> Spalten gehen mit.
> **Aktivierungsbedingung:** Eigner-Entscheidung, kein Termin. Faellt sie fuer
> einen der beiden Aenderungswege, entsteht daraus ein eigener `next/`-Plan (drei
> Spec-Stellen plus ADR-Nachtrag); faellt sie fuer „so lassen", ist nur ein
> Doku-Nachtrag zu machen und dieser Eintrag schliesst.

## Worum es geht

Der Ausgang ist **laut** — `E052` steht auf stderr und in `skipped_objects` des
Reports, und die Meldung nennt die Spalte — und die Spec schreibt ihn an drei
Stellen fest, jeweils mit Begruendung:

- `spec/cli-spec.md:467` — „Die gesamte Tabelle wird uebersprungen; keine partielle DDL."
- `spec/neutral-model-spec.md:1503` — „E052 | Blockiert die gesamte betroffene Tabelle."
- `spec/ddl-generation-rules.md:2688` — die **generische** E052-Regel: „Wird erzeugt, wenn ein Spatial-Objekt mit dem gewaehlten Spatial-Profil nicht generiert werden kann. Die gesamte betroffene Tabelle wird blockiert — partielle DDL ohne die Spatial-Spalte wird nicht erzeugt."

Ausgeloest wird der Fall vom Profil **`spatialite`**, nicht von `none`: eine
Geometriespalte, die `NOT NULL` traegt (oder PK/UNIQUE/Default/Referenz), fuehrt
Metadaten, die SpatiaLite nicht halten kann (`SqliteTableDdlSupport.kt:315-333`).
Die Profilabschnitte 16.5 (`spec/ddl-generation-rules.md:2479`, `spatialite`) und
16.6 (`:2507`, `none`) sagen zu diesem Fall nichts.

## Die Wege und was sie kosten

| Weg | Wirkung | Preis |
| --- | ------- | ----- |
| Geometriespalte allein entfallen lassen | die Tabelle bleibt, die uebrigen Spalten kommen mit | kippt „keine partielle DDL" (`spec/cli-spec.md:467`) — der Anwender bekommt eine Tabelle, der eine Spalte fehlt |
| Tabelle mit Hinweis anlegen, Spalte als Text | nichts faellt aus | kippt „kein stiller Fallback auf `TEXT`/`BLOB`" — als **lauter** Fallback mit Note diskutabel, als stiller nicht |
| so lassen | die Spec bleibt konsistent | der Anwender verliert eine ganze Tabelle wegen eines Spaltenproblems |

Die Frage ist ausdruecklich **nicht** „ist das ein Defekt": der Ausgang ist
gemeldet und die Regel begruendet. Sie ist, ob der Zuschnitt der Regel richtig
ist — Tabellenblockade als Antwort auf eine Spalteneinschraenkung.

## Beruehrt

- [`ADR 0016`](../../adr/0016-spatialite-metadata-bootstrap.md) traegt
  `status: accepted` und ist im Kern eingefroren (`make doc-immutable`). Die dort
  als bewusste Scope-Grenze aufgeschobene **Generate**-Frage laesst sich deshalb
  nicht im Vorbeigehen nachziehen, sondern nur ueber einen neuen ADR oder eine
  Statusaenderung.
- Die drei Spec-Stellen oben, sobald eine Aenderung beschlossen ist.

## Referenzen

- Befund und Belege (Konsumentenmessung, im Slice als Posten A6 gefuehrt):
  [`../next/reader-treue-3-spatial.md`](../next/reader-treue-3-spatial.md).
- [`ADR 0016`](../../adr/0016-spatialite-metadata-bootstrap.md) — SpatiaLite-Metadaten-Bootstrap
  im Migrate-Diff-Pfad; der Generate-Pfad ist dort ausdruecklich aufgeschoben.
