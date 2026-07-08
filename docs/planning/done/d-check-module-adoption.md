# d-check-Modul-Adoption (Voll-Ausbau, kalibriert)

> **Status:** Geliefert (2026-06-16). Closure unten.
> **Ziel:** Entscheiden und umsetzen, welche d-check-Module (seit v0.9.0)
> dauerhaft ins Doku-Gate (`make docs-check`) wandern — und die echte Drift
> beheben, die der Probe-Audit fand.
> **Ergebnis:** alle acht Module bewertet; sieben aktiv (`links`, `anchors`,
> `matrix`, `hostpaths`, `spans`, `codepaths`, `ids`), `external` bewusst aus
> (Netzzugriff). Config in [`.d-check.yml`](../../../.d-check.yml).

## Trigger

d-check ist von v0.1.0 → v0.9.0 angehoben worden und bietet acht Module statt
zwei. Mehrere mechanisieren Doku-Prinzipien, die zuvor von Hand durchgesetzt
wurden (Referenzrichtung, Kennungs-Verlinkung, Maschinenpfad-Leaks). Vor dieser
Adoption aktiv: nur `links`, `anchors`.

## Entscheidungen (2026-06-16)

- **D1 — `codepaths`: breit inkl. spec/planning.** codepaths läuft über `docs/`
  und `spec/` (`scope.roots: [docs, spec]`; Root-Markdown wie das Changelog
  ausgenommen — unveränderliche Historie). Echte Drift wurde gefixt; die
  legitimen Rest-Klassen sind regelwerk-konform behandelt statt als
  Marker-Kaskade (Modul 7), siehe
  [ADR 0011](../../adr/0011-d-check-codepaths-scope-und-dauerhafte-ausnahmen.md):
  Abkürzungen als `…`-Notation, dauerhafte Nicht-Repo-Pfade (Nutzer-CWD,
  Build-Ausgaben) als ADR-benannte Marker, homogene Zukunfts-Adapter-Specs als
  `scope.ignore` mit Graduations-Trigger, verstreute Zukunftspfade als
  getriggerte Einzel-Marker.
- **D2 — `ids`: UC-NN + ADR-NNNN.** `UC` → `spec/lastenheft-d-migrate.md`,
  `ADR` → `docs/adr/`. Kein LF/LN-Mandat (zu breites Hand-Muster verworfen).
  Mechanisiert die Kennungs-Verlinkung aus dem verwandten §-Referenz-Tracker.
- **D3 — eingefrorenes `done-archive/`.** Neuer Ordner parallel zu `done/`; die
  historischen Done-Pläne dorthin verschoben und vom Scan ausgenommen; `done/`
  bleibt im Scan (frisch abgeschlossen). Siehe
  [ADR 0010](../../adr/0010-done-archive-und-gate-scan-ausschluss.md).

Zusätzlicher Befund während der Umsetzung: die `matrix`-Referenzrichtung (SDP)
deckte 23 echte Spec→Plan-Abwärtsverweise auf (Body-Links und Inline-Provenance,
inkl. „folgt dem Phase-X-Vertrag"-Delegationen). Alle behoben — die Spec ist
jetzt selbst-normativ und verweist nicht mehr abwärts auf Pläne oder
Plan-Phasen.

## Lieferung (Phasen)

1. **Phase 1 — saubere Module + SDP.** `matrix` (spec verweist nicht abwärts
   auf ADRs oder Pläne; verbotene Stati superseded/deprecated; Historie-/
   Versions-Sektionen ausgenommen), `hostpaths`, `spans` aktiv. 23 Spec→Plan-
   Abwärtsverweise entfernt bzw. neutralisiert.
2. **D3 — done-archive.** 195 Pläne verschoben, Querverweise nachgezogen,
   ADR 0010.
3. **Phase 3 — codepaths + ids.** Notation/Fixes/Marker/`scope` gemäß D1/D2;
   ADR 0011; `docs/archive/` als realer Pfad angelegt; 6 Kennungen verlinkt.

## Akzeptanzkriterien — erfüllt

- `make docs-check` grün mit der erweiterten `.d-check.yml`.
- `matrix`/`hostpaths`/`spans`/`codepaths`/`ids` dauerhaft aktiv.
- Echte Drift gefixt; legitime Rest-Referenzen als Zielbild/permanent markiert
  (Grund + Trigger) bzw. via `scope` ausgenommen.
- Jede Ausnahme trägt Grund und — wo zutreffend — Auflösungs-Trigger; permanente
  Klassen als Architekturentscheidung in ADR 0011 benannt (kein stilles Absenken
  des Gates, Carveout-Disziplin Modul 7).

## Closure

Alle drei Entscheidungen getroffen und umgesetzt; sieben Module aktiv; Gate
grün. Offene Folgearbeit existiert nur als dokumentierte Graduations-Trigger in
ADR 0011: beim tatsächlichen Bau der jsqlparser-/grpc-/rest-Adapter wird der
jeweilige `scope.ignore`-Eintrag entfernt, damit codepaths die dann
existierenden Pfade prüft.

## Referenzen

- [`.d-check.yml`](../../../.d-check.yml), [`Makefile`](../../../Makefile) `D_CHECK_IMAGE`
- [ADR 0010](../../adr/0010-done-archive-und-gate-scan-ausschluss.md),
  [ADR 0011](../../adr/0011-d-check-codepaths-scope-und-dauerhafte-ausnahmen.md),
  [ADR 0004](../../adr/0004-documentation-and-planning-structure.md) — Planungs-Lebenszyklus
- Verwandt: [`../open/mcp-server-spec-hygiene-residuals.md`](../open/mcp-server-spec-hygiene-residuals.md)
  (Befund 1 §-Referenzen — durch `ids` mechanisiert)
