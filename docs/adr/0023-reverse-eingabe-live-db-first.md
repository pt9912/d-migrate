---
status: accepted
date: 2026-06-28
decision-makers: pt9912
consulted: spec/neutral-model-spec.md (§12 DDL-Parser-Zielbild), docs/archive/design.md (Reverse-Eingabepfade)
informed: spec/cli-spec.md (schema reverse), docs/planning/in-progress/roadmap.md
---

# Reverse-Eingabe: Live-DB-first; DDL-Datei-Parser als additiver, späterer Funktionsschnitt

> **Status: accepted (2026-06-28).** `schema reverse` rekonstruiert das neutrale
> Modell ausschließlich aus **Live-Datenbankverbindungen via JDBC**. Der
> DDL-Datei-Parser (SQL-Datei-Parsing, Dialekt-Erkennung aus Dateien,
> stdin-DDL) ist ein **additiver, künftiger** Funktionsschnitt — nicht Teil des
> Reverse-Kernvertrags. Diese ADR hält die *Begründung und Richtung* fest; das
> **Was** beschreibt das Zielbild (`spec/neutral-model-spec.md` §12), das
> **Wann** die Roadmap.

## Kontext und Problemstellung

Das Zielbild (`spec/neutral-model-spec.md`, Abschnitt „DDL-Parser") beschreibt
einen Parser, der SQL-Dateien (`CREATE TABLE`, `CREATE TYPE`, `CREATE FUNCTION`
…) liest, den Quell-Dialekt aus Datei-Indikatoren erkennt und das Ergebnis ins
neutrale Modell projiziert. Daneben gibt es den heute gelieferten Reverse-Pfad,
der dieselbe neutrale Projektion **aus einer laufenden Datenbank** über
JDBC-Metadaten gewinnt.

Beide Pfade führen zum selben neutralen Modell, sind aber **nicht
gleichrangig**: der Live-DB-Pfad deckt die Kern-Use-Cases (Migration aus einer
betriebenen Quelle), der Datei-Pfad erweitert die Eingabe additiv. Diese
Trennung — und vor allem ihre **Begründung** — ergibt sich weder aus dem Code
noch aus dem Zielbild-Text von selbst.

Bisher stand die Trennung als **versionsgestempelter Satz** im Spezifikations-
Body (`## 12. DDL-Parser (späterer Milestone — nicht Teil von 0.6.0)`,
`(geplant)`-Marker, Status-Footer). Das verletzt das **Stable Dependencies
Principle**: das Zielbild (`spec/`) ist die stabilste Schicht und trägt **keine**
Milestone-/Phasen-Provenienz im Anforderungstext. Die „Wann/Ob"-Information
gehört an einen stabileren Ort für Entscheidungs-*Begründungen* — also in eine
ADR plus die Roadmap.

## Entscheidungstreiber

- **SDP / „Spec = Zielbild ohne Status/Phasen":** der Spec-Body darf keine
  `0.6.0`-/`(geplant)`-/`Milestone`-Stempel als Teil der Soll-Aussage tragen.
- **Use-Case-Deckung:** Live-DB-Reverse adressiert den Kernfall (Quelle läuft);
  Datei-Parsing ist Komfort/Erweiterung, kein Blocker für Migrationen.
- **Additivität:** der Datei-Parser ergänzt nur die **Eingabe** und ändert
  weder das neutrale Modell noch nachgelagerte Generate-/Transfer-Verträge.
- **Eigenes Risiko-/Test-Profil:** robuste Dialekt-Erkennung und SQL-Parsing
  sind ein eigener, größerer Schnitt mit eigenem Fidelity-Profil — sinnvoll
  separat schneidbar, statt den Reverse-Kernvertrag zu belasten.

## Betrachtete Optionen

- **A — Live-DB-first als Kernvertrag, DDL-Datei-Parser additiv-später.**
- **B — DDL-Datei-Parser als gleichrangiger Teil des Reverse-Kernvertrags.**
- **C — DDL-Datei-Parser ganz aus dem Zielbild streichen.**

## Entscheidung

**Gewählt: Option A.**

`schema reverse` liest ausschließlich aus Live-DB-Verbindungen via JDBC. Der
DDL-Datei-Parser bleibt **Teil des Zielbilds** (`spec/neutral-model-spec.md`
§12 beschreibt sein Modell weiterhin vollständig), wird aber als **additiver,
nachgelagerter Funktionsschnitt** geführt. Konkret:

- Der §12-Body wird **entstempelt**: keine `(später Milestone — nicht Teil von
  0.6.0)`-Klammer, keine `(geplant)`-Marker, kein milestone-begründeter
  Status-Footer. Er beschreibt das Parser-Modell zeitlos als Zielbild.
- Die **additive/nachgelagerte Natur** und ihre Begründung stehen **hier**
  (ADR 0023) und in `docs/planning/in-progress/roadmap.md`, **nicht** im
  Spec-Body.
- **Linkrichtung (SDP):** Diese ADR verweist auf das Zielbild (`§12`,
  `design.md`); das Zielbild verweist **nicht** auf diese ADR. Die Spec bleibt
  selbsttragend und referenziert nicht abwärts.

Option B wurde verworfen, weil sie den Reverse-Kernvertrag mit einem
risiko-/aufwandsreichen Parser belastet, der für die Kern-Migration nicht
erforderlich ist. Option C wurde verworfen, weil Datei-basiertes Reverse ein
echtes, gewolltes Zielbild-Element ist (DDL-only-Quellen, Review-Workflows) —
es ist verschoben, nicht verworfen.

## Konsequenzen

- **Gut:** Der Spec-Body ist frei von Milestone-Provenienz; die Trennungs-
  *Begründung* liegt an einem stabilen, auffindbaren Ort.
- **Gut:** Reverse-Kernvertrag bleibt klein und gegen die Live-DB-Realität
  testbar.
- **Preis:** Leser von §12 sehen die Eingabe-Reihenfolge nicht mehr inline —
  bewusst, weil §12 Zielbild ist; Reihenfolge/Timing liefert die Roadmap.
- **Folgearbeit (Sub-Slice
  [`spec-milestone-hygiene-slice.md`](../planning/done/spec-milestone-hygiene-slice.md),
  WP1):** §12-Heading/Hinweis/Status-Footer entstempeln (Anker wird zu
  `#12-ddl-parser`), das DDL-Parser-Echo in `docs/archive/design.md` entstempeln und den
  bestehenden Peer-Link auf den neuen §12-Anker nachziehen, den additiven
  Schnitt in der Roadmap führen.

## Bestätigung

- `make docs-check` grün (Anker/Links, ADR-IDs verlinkt).
- Grep-Beleg: kein `0.6.0`-/`Milestone`-/`(geplant)`-Stempel mehr im
  §12-/`design.md`-DDL-Parser-Text; `schema reverse` in `cli-spec.md` nennt
  ausschließlich den Live-DB-Eingabepfad.
