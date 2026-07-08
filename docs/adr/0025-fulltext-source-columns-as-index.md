---
status: accepted
date: 2026-06-28
decision-makers: pt9912
consulted: docs/planning/done/fulltext-structural-cross-dialect.md (Slice, Abschnitt 4), docs/adr/0015-fulltext-tsvector-neutral-type.md (parameterloser fulltext-Typ), docs/adr/0016-spatialite-metadata-bootstrap.md (strukturelle Spalten-Expansion)
informed: hexagon/core, adapters/driven/driver-postgresql, adapters/driven/driver-mysql, adapters/driven/driver-sqlite, spec/neutral-model-spec.md
---

# Volltext-Quellspalten als `FULLTEXT`-Index modellieren, nicht am `fulltext`-Typ

## Kontext und Problemstellung

[ADR 0015](0015-fulltext-tsvector-neutral-type.md) hat PostgreSQL-`tsvector`-Spalten
als parameterlosen neutralen Typ `NeutralType.FullText` modelliert — bewusst **ohne**
typ-spezifische Attribute und mit der Text-Search-Konfiguration (`pg_catalog.english`)
ausdrücklich **nicht** im Typ („die Config schmuggeln wir bewusst **nicht** in den Typ").

Die strukturelle Cross-Dialect-Übersetzung (Slice
[`../planning/done/fulltext-structural-cross-dialect.md`](../planning/done/fulltext-structural-cross-dialect.md))
braucht aber die **Quelltext-Spalten** (z. B. Pagila `film.title`, `film.description`)
plus die Config:

- **MySQL** hat einen `FULLTEXT`-**Index** über reguläre TEXT-Spalten.
- **SQLite** hat eine **FTS5-Virtual-Table** über Quelltext-Spalten.
- **PostgreSQL** hält einen vorberechneten `tsvector` in einer eigenen Spalte; die
  Quellspalten + Config stehen nur im befüllenden Trigger
  (`tsvector_update_trigger(fulltext,'pg_catalog.english',title,description)`) bzw. in
  einer `GENERATED … AS (to_tsvector(…))`-Expression.

**Frage dieses ADR:** Wo im neutralen Modell leben die Quellspalten (+ Config)?

## Entscheidungstreiber

- ADR-0015-Treue: `fulltext` ist bewusst parameterlos; Config bewusst nicht im Typ.
- Keine Kopplung eines Spalten-*Typs* an *andere* Spalten derselben Tabelle (Rename-Fragilität).
- Strukturelle Treue zu den Zieldialekten (MySQL/SQLite: Volltext = Index/Tabelle über Quellspalten).
- Wiederverwendung erprobter Muster (strukturelle Spalten-Expansion, [ADR 0016](0016-spatialite-metadata-bootstrap.md)).

## Betrachtete Optionen

### Option (a) — Quellspalten am `fulltext`-Typ

`NeutralType.FullText` wird vom parameterlosen `data object` zu
`data class FullText(sourceColumns: List<String>, textSearchConfig: String?)`.

- **Reverse (PG):** Trigger-/Generated-Expression parsen → Attribute am `fulltext`-Spaltentyp füllen.
- **Generate PG:** `FullText(cols, config)` → `tsvector`-Spalte + Trigger + GiST-Index (Typ trägt alles Nötige).
- **Generate MySQL/SQLite:** `fulltext`-Spalte degradiert (TEXT/entfällt) + `FULLTEXT`-Index bzw. FTS5 über `sourceColumns`.
- **Spannung:**
  - Revidiert **zwei** explizite ADR-0015-Entscheidungen (parameterloser Typ + Config nicht im Typ) → Teil-Supersession nötig.
  - Ein Spalten-*Typ* trägt die Namen *anderer* Spalten → der Typ ist an die Tabellen-Nachbarschaft gekoppelt und bricht bei Spalten-Renames.
  - Serialisierung + `schema.json`-Vertrag wachsen um typ-interne Cross-Referenzen.

### Option (b) — Quellspalten als `IndexType.FULLTEXT`-Index *(empfohlen)*

`IndexType.FULLTEXT` neu (analog `SPATIAL`); `IndexDefinition` bekommt ein optionales
`textSearchConfig: String?` (wie `where` / `prefixLength` bereits optionale Index-Attribute
sind). `NeutralType.FullText` bleibt **parameterlos**.

- **Reverse (PG):** Trigger parsen → `IndexDefinition(type=FULLTEXT, columns=Quellspalten, textSearchConfig=Config)` synthetisieren; die `tsvector`-Spalte bleibt parameterloser `FullText`. Der GiST-über-`tsvector`-Index wird von der FULLTEXT-Abstraktion ersetzt.
- **Generate PG:** `FULLTEXT`-Index → strukturelle **Expansion** zu `tsvector`-Spalte (aus dem `FullText`-Spaltentyp) + Trigger (aus `columns` + `config`) + GiST-Index — dasselbe „ein Objekt → mehrere DDL-Objekte"-Muster wie SpatiaLite ([ADR 0016](0016-spatialite-metadata-bootstrap.md)).
- **Generate MySQL:** `FULLTEXT(Quellspalten)` — **nativ, direkt**; die `FullText`-Spalte degradiert (W132).
- **Generate SQLite:** FTS5-Virtual-Table über die Quellspalten + Sync-Trigger; die `FullText`-Spalte degradiert (W132).
- **Eigenschaften:**
  - ADR-0015-treu: Typ bleibt parameterlos, Config sitzt am Index (wo Text-Search-Config — wie eine Op-Class — hingehört), nicht im Typ → **keine** Supersession.
  - Cross-Spalten-Referenzen leben am **Index** (Indizes referenzieren naturgemäß Spalten) → keine Typ-Nachbarschafts-Kopplung.
  - Strukturell deckungsgleich mit MySQL/SQLite (Volltext = Index/Tabelle über Quellspalten); deckt sich mit Slice-Entscheidung 4.2 (eigener `IndexType.FULLTEXT`).
  - **Kosten:** PG-Generate muss `tsvector`-Spalte + Trigger + GiST aus dem Index *re-derivieren* (strukturelle Expansion) — mitigiert durch das bereits erprobte SpatiaLite-Muster.

## Entscheidung

**Option (b).** Quelltext-Spalten und optionale Text-Search-Config werden als
`IndexType.FULLTEXT`-Index (mit optionalem `textSearchConfig`) über die Quellspalten
modelliert; `NeutralType.FullText` bleibt der parameterlose `tsvector`-Spaltentyp.

Begründung: (b) hält die in ADR 0015 bewusst getroffenen Entscheidungen (parameterloser
Typ, Config nicht im Typ) ein — keine Supersession —, platziert Cross-Spalten-Referenzen
strukturell korrekt (am Index), ist deckungsgleich mit der nativen MySQL-/SQLite-Repräsentation
und nutzt für den einzigen Mehraufwand (PG-Re-Derivation) das bereits etablierte strukturelle
Expansions-Muster aus ADR 0016.

## Konsequenzen

**Positiv:**
- ADR 0015 bleibt unangetastet gültig; `fulltext` bleibt ein sauberer parameterloser Typ.
- Cross-Dialect-Generate ist strukturell direkt (MySQL/SQLite-nativ); PG-Round-Trip via Expansion.
- `textSearchConfig` reiht sich als optionales Index-Attribut neben `where` / `prefixLength` ein.

**Negativ / Abwägung:**
- PG-Generate trägt die Re-Derivations-Komplexität (Index → Spalte + Trigger + GiST). Akzeptiert: identisches Muster wie SpatiaLite, mit Idempotenz im Diff-Renderpfad.
- Reverse muss `tsvector`-Spalte, Trigger und `FULLTEXT`-Index konsistent verknüpfen (welche Quellspalten zu welcher Vektorspalte) — die eigentliche „Quelltext-Herleitung" (Slice-Phase P2).

**Verfeinerung (P2-Review-Härtung):** Die PG-Rekonstruktion braucht zwei Detail-Angaben, die
nicht zur Volltext-*Fähigkeit* gehören: die Backing-`tsvector`-Spalte
(`IndexDefinition.fullTextVectorColumn`) und die Zugriffsmethode GIN/GiST
(`fullTextAccessMethod`). Beide sind **Generate-only-Rekonstruktions-Hinweise**: vom Reverse
gefüllt und vom Generate genutzt (eindeutige Vektorspalte auch bei mehreren `tsvector`-Spalten;
GIN→GIN statt GIN→GiST-Normalisierung), aber **aus der Vergleichs-Semantik ausgeschlossen**
(Comparator/Fingerprint/`CanonicalPayload`, analog `ordinal`) — sonst erzeugt ein authored Index
(Hinweise fehlen) gegen den reversed Index (Hinweise gesetzt) Phantom-Diffs. `textSearchConfig`
bleibt dagegen semantisch und wird verglichen.

## Abgrenzung (Nicht-Ziele)

- **Keine** Volltext-Query-Übersetzung (`to_tsquery` / `MATCH … AGAINST`).
- **Keine** MySQL/SQLite→PG-Reverse-Richtung struktureller Volltext-Konstrukte (eigene Entscheidung).
- Erweiterte Text-Search-Konfigurationen jenseits des aus Trigger/Expression sauber Ableitbaren.
