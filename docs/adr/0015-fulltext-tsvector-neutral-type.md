---
status: accepted
date: 2026-06-19
decision-makers: pt9912
consulted: docs/planning/in-progress/sample-db-roundtrip-findings.md (F3-Restbefund), spec/neutral-model-spec.md (Typ-Katalog), docs/adr/0014-sample-db-harness-fetch-and-compose.md (Harness, der den Befund aufdeckte)
informed: examples/sample-db/expected/pagila-smoke.md (Baseline), adapters/driven/driver-postgresql, adapters/driven/formats, hexagon/core
---

# Volltext-Spalten (PostgreSQL `tsvector`) als first-class neutraler Typ

## Kontext und Problemstellung

Der Pagila/PostgreSQL-Round-Trip des Sample-DB-Harness
([ADR 0014](0014-sample-db-harness-fetch-and-compose.md)) ist nach Behebung von
F1–F4 auf **einen** verbliebenen Schema-Diff geschrumpft: der GiST-Index
`film.film_fulltext_idx` fehlt im Ziel.

Ursachenkette:
1. Pagilas Spalte `film.fulltext` ist `tsvector` (PostgreSQLs Volltext-Such-Vektor).
2. Der Reverse kennt `tsvector` nicht — das neutrale Modell (`NeutralType`) hat
   keine passende Variante. Die Spalte degradiert zu `text` (Note `R301`).
3. Beim Generate liegt nun `text` vor; ein GiST-Index braucht eine Default-
   Operator-Klasse. `tsvector` **hat** eine (`tsvector_ops`), `text` **nicht** →
   der Index wird übersprungen (Note `W123`).
4. `schema compare` sieht den Index in der Quelle, nicht im Ziel → der Diff.

Beide Notes (R301/W123) melden den Verlust ehrlich; die Daten round-trippen (als
Text). Die Degradierung ist für **Cross-Dialect** (PG→MySQL/SQLite) auch korrekt —
es gibt dort kein `tsvector`. Für den **Same-Dialect-Round-Trip (PG→PG)** ist sie
aber unnötiger Fidelity-Verlust: die Information `tsvector` liegt beim Reverse vor
(sie steht wörtlich in der R301-Meldung), das Modell hat nur keinen Platz dafür.

Verworfene Option (Native-Typ-Passthrough): Den rohen Dialekt-Typstring `tsvector`
durch das Modell durchzureichen, wurde **abgelehnt** — das koppelt das neutrale
Modell an einen Dialekt und höhlt seine Neutralität aus. Es soll **kein** nativer
Typ durchgereicht werden.

## Entscheidung

Volltext-Such-Vektoren werden als **first-class neutraler Typ** modelliert —
exakt nach dem Muster von `NeutralType.Geometry`, also als im Modell *abstrahierter*
Typ, **nicht** als durchgereichte Dialekt-Zeichenkette.

- **Modell:** neue parameterlose Variante `NeutralType.FullText` (kanonischer
  Name **`fulltext`**). Anders als `geometry`
  (`geometry_type`/`srid`) trägt der Typ **keine** typ-spezifischen Attribute: die
  `tsvector`-Spalte selbst ist parameterlos; die Text-Search-Konfiguration
  (`pg_catalog.english`) gehört zur befüllenden Funktion/zum Trigger
  (`tsvector_update_trigger(...)`), nicht zum Spaltentyp. Wir schmuggeln die Config
  bewusst **nicht** in den Typ.
- **PostgreSQL Reverse:** `tsvector` (udt) → `NeutralType.FullText` (Info-Note statt
  R301-Degradierung).
- **PostgreSQL Generate:** `NeutralType.FullText` → `tsvector`. Die GiST-Op-Class-
  Prüfung (`PostgresIndexOpClass.hasDefaultOpClass`) erkennt `FullText` als
  `tsvector_ops`-fähig → der GiST-Index überlebt (kein W123 mehr).
- **Cross-Dialect (MySQL/SQLite Generate):** `FullText` degradiert zu `TEXT` mit
  einer Note (kein nativer Volltext-Vektor in MySQL/SQLite) — dasselbe
  Degradierungs-Muster wie `geometry` bei `--spatial-profile none`.
- **Serialisierung/Spec:** `type: fulltext` als kanonischer Name; aufgenommen in
  die Typ-Tabelle von `spec/neutral-model-spec.md` (Zielbild, ohne Rückverweis auf
  dieses ADR).

## Namenswahl

Kanonischer Typname **`fulltext`** (entschieden 2026-06-19). Verworfen:
`textsearch` (ungewohnt) und `tsvector` (PG-Vokabular im neutralen Modell, grenzt
an den abgelehnten Passthrough). Hinweis: MySQLs `FULLTEXT` ist ein *Index*-Konzept,
nicht ein Spaltentyp — die Begriffsnähe ist bewusst in Kauf genommen.

## Konsequenzen

**Positiv:**
- Pagila/PG-Baseline → **0 Diffs** (der letzte verbliebene Diff verschwindet).
- `tsvector`-Spalten + ihre GiST-Indizes round-trippen PG→PG vollständig; die
  Notes R301/W123 entfallen für diesen Fall.
- Cross-Dialect bleibt ehrlich: `fulltext` → `text` + Note (vorher implizit über
  R301, künftig explizit über den Typ).

**Negativ / Abwägung:**
- Ein weiterer PG-naher Typ im Modell (wie `xml`/`json`/`uuid`/`geometry`) — mit
  schwachem Cross-Dialect-Profil. Akzeptiert: dasselbe gilt für `geometry`, und der
  Degradierungspfad ist sauber definiert.
- Neuer Typname = Erweiterung des Modell-Vertrags (Spec + Serialisierung +
  Contract-Test).

## Abgrenzung (Nicht-Ziele)

- **Kein** Durchreichen beliebiger nativer Typen (`inet`, `cidr`, `tsquery`,
  Ranges, `ltree`, …). Diese degradieren weiterhin zu `text` + R301. Dieses ADR
  deckt **nur** Volltext-Vektoren ab; jeder weitere Typ wäre eine eigene
  first-class-Entscheidung.
- **Keine** Text-Search-Konfiguration im Spaltentyp (s. o.).
- **Keine strukturelle Volltext-Übersetzung nach MySQL/SQLite** in diesem Slice.
  Beide *haben* Volltext, aber strukturell anders als ein Spaltentyp:
  - **SQLite FTS5** ist eine **virtuelle Tabelle**
    (`CREATE VIRTUAL TABLE … USING fts5(…)`), die Quelltext indexiert (keine
    vorberechneten Vektoren). Ein `tsvector`-Spalte→FTS5-Mapping ist ein
    *struktureller Umbau* (separate virtuelle Tabelle + Sync-Trigger), nicht eine
    Typ-Abbildung.
  - **MySQL** kennt einen `FULLTEXT`-**Index** auf einer regulären TEXT/CHAR-Spalte
    — ebenfalls kein Vektor-Spaltentyp.

  Cross-Dialect degradiert der `fulltext`-Spaltentyp daher hier zu `text`; die
  strukturelle FTS5-/FULLTEXT-Übersetzung ist ein **eigener Folge-Slice** (Phase-2/2b
  des Sample-DB-Harness), idealerweise mit eigener ADR und einer Degradierungs-Note,
  die auf den manuellen FTS5-/FULLTEXT-Pfad hinweist.

Diese beiden Abgrenzungen sind als **Provisional-Carve-Outs** getrackt:
siehe [`carveout.md`](../planning/in-progress/carveout.md) §8 (strukturelle
Cross-Dialect-Volltext-Übersetzung + weitere PG-only-Typen first-class).
