# Vertragsfrage: E052 verwirft die ganze Tabelle (SpatiaLite-Profil)

> **Status:** Entschieden (2026-09-16); wird als P7 in Plan 3 des
> Reader-Slices gebaut
> ([`../in-progress/reader-treue-3-spatial.md`](../in-progress/reader-treue-3-spatial.md),
> Schnitt 2026-09-17). Dieser Eintrag schließt mit der Lieferung von P7.
> **Eigner-Entscheidung: NOT NULL nativ.** Gemessen im Tooling-Image
> (SpatiaLite 5.1.0): `AddGeometryColumn('t','geom',4326,'POINT','XY',1)` legt die
> Spalte als `"geom" POINT NOT NULL DEFAULT ''` an, eine Zeile ohne Geometrie wird
> abgewiesen (`violates Geometry constraint`), eine mit Geometrie angenommen.
> `NOT NULL` ist damit **keine** Metadatenlücke von SpatiaLite; heute blockiert der
> Generator trotzdem (`column.required` in der Konfliktprüfung von
> `SqliteTableDdlSupport`). Gebaut wird: `required` über das `not_null`-Argument,
> `E052` bleibt nur für PK, UNIQUE, Default und Fremdschlüssel. Die Regel „keine
> partielle DDL" bleibt; die Spec-Stellen unten nennen `NOT NULL` nicht mehr als
> Auslöser. **Einen ADR braucht es dafür nicht:** die Auslöser von `E052` sind
> nicht ADR-gebunden; der Gegenstand von ADR 0016 (Ort und Form des Bootstrap)
> bleibt unberührt.
> **Korrektur (2026-09-17, Architektur-Prüfung):** Die frühere Begründung
> „ADR 0016 hat den Generate-Pfad aufgeschoben, nicht festgelegt" stimmte
> nicht. ADR 0016 hat im Generate-Pfad nur den **Bootstrap** aufgeschoben
> (Kandidat 2 unter „Verworfene/aufgeschobene Alternativen"); die Auslöser von
> `E052` waren nie Gegenstand eines ADR.
> **Umsetzung (2026-09-17):** wird in **P7** gebaut
> ([`../in-progress/reader-treue-3-spatial.md`](../in-progress/reader-treue-3-spatial.md));
> dieser Eintrag schließt mit dessen Lieferung, nicht vorher. Beim
> Aktivierungsschnitt nachgemessen und dort festgehalten: die drei
> Spec-Stellen unten haben `NOT NULL` **nie** als Auslöser genannt — die
> Auslöser stehen nirgends in `spec/`, P7 trägt sie erstmals in den
> Profilabschnitt 16.5 ein; dieselbe Regel steht ein zweites Mal im
> Migrate-Pfad (`SqliteSpatialDiffOps`); und der SQLite-Reverse liest
> SpatiaLites `DEFAULT ''` sonst als Anwender-Default zurück. Beim Schnitt in
> vier Pläne kamen dazu: der Rebuild des Migrate-Pfads legt eine
> Geometriespalte inline an, ohne `AddGeometryColumn`, und die Spec
> widerspricht sich darin, ob `E052` auch aus `schema migrate` kommt.
> **Trigger:** Konsumentenmessung gegen 1.7.1 (Posten A6 des Reader-Slices,
> jetzt in [`../in-progress/reader-treue-3-spatial.md`](../in-progress/reader-treue-3-spatial.md)):
> `schema generate --target sqlite --spatial-profile spatialite` auf einer
> MySQL-Quelle mit **NOT NULL**-Geometrie erzeugt `E052` und laesst die
> **komplette** Tabelle aus der Ausgabe fallen — die uebrigen, darstellbaren
> Spalten gehen mit.
> **Aktivierungsbedingung:** erfüllt — die Eigner-Entscheidung ist gefallen,
> der Scope steht in P7. Der Text unten ist die Entscheidungsgrundlage vom
> 2026-09-16 und bleibt als solche stehen.

## Worum es geht

Der Ausgang ist **laut** — `E052` steht auf stderr und in `skipped_objects` des
Reports, und die Meldung nennt die Spalte — und die Spec schreibt ihn an drei
Stellen fest, jeweils mit Begruendung:

- `spec/cli-spec.md:467` — „Die gesamte Tabelle wird uebersprungen; keine partielle DDL."
- `spec/neutral-model-spec.md:1503` — „E052 | Blockiert die gesamte betroffene Tabelle."
- `spec/ddl-generation-rules.md:2688` — die **generische** E052-Regel: „Wird erzeugt, wenn ein Spatial-Objekt mit dem gewaehlten Spatial-Profil nicht generiert werden kann. Die gesamte betroffene Tabelle wird blockiert — partielle DDL ohne die Spatial-Spalte wird nicht erzeugt."

Ausgeloest wird der Fall vom Profil **`spatialite`**, nicht von `none`: eine
Geometriespalte, die `NOT NULL` traegt (oder PK/UNIQUE/Default/Referenz), fuehrt
Metadaten, die SpatiaLite nicht halten kann (`SqliteTableDdlSupport`,
`checkSpatialMetadataBlocks` mit `hasSpatialMetadataConflict`; dieselbe Regel
im Migrate-Pfad in `SqliteSpatialDiffOps`).
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
  `status: accepted` und ist im Kern eingefroren (`make doc-immutable`). Er
  regelt Ort und Form des SpatiaLite-Bootstrap; im Generate-Pfad hat er nur
  den **Bootstrap** als bewusste Scope-Grenze aufgeschoben. Die Auslöser von
  `E052` berührt er nicht — P7 braucht deshalb weder einen neuen ADR noch eine
  Statusänderung. (Die frühere Fassung dieses Punkts verlangte beides; das
  widersprach dem Kopf und beruhte auf der korrigierten Begründung.) Wer den
  Bootstrap in den Generate-Pfad holt, bewegt sich dagegen in ADR 0016.
- Die drei Spec-Stellen oben, sobald eine Aenderung beschlossen ist.

## Referenzen

- Befund, Belege und Paket (Posten A6, Paket P7):
  [`../in-progress/reader-treue-3-spatial.md`](../in-progress/reader-treue-3-spatial.md);
  Umbrella [`../in-progress/reader-treue.md`](../in-progress/reader-treue.md).
- [`ADR 0016`](../../adr/0016-spatialite-metadata-bootstrap.md) — SpatiaLite-Metadaten-Bootstrap
  im Migrate-Diff-Pfad; im Generate-Pfad ist dort nur der Bootstrap
  aufgeschoben.
