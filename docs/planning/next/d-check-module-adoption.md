# d-check-Modul-Adoption (Voll-Ausbau, kalibriert)

> **Status:** Entwurf (2026-06-15)
> **Ziel:** Entscheiden und umsetzen, welche d-check-Module (seit v0.9.0)
> dauerhaft ins Doku-Gate (`make docs-check`) wandern — und die echte Drift
> beheben, die der Probe-Audit gefunden hat.
> **Vorbedingungen:** keine. Image ist bereits auf v0.9.0 gepinnt
> ([`Makefile`](../../../Makefile) `D_CHECK_IMAGE`); Config in
> [`.d-check.yml`](../../../.d-check.yml).

## Trigger

d-check ist von v0.1.0 → v0.9.0 angehoben worden und bietet jetzt **acht
Module** statt zwei. Mehrere mechanisieren Doku-Prinzipien, die wir bisher von
Hand durchsetzen (Referenzrichtung, Kennungs-Verlinkung, Maschinenpfad-Leaks).
Aktuell aktiv: nur `links`, `anchors`.

## Messdaten (Probe-Durchgang 2026-06-15, Voll-Config)

`make docs-check` mit allen Modulen ergab **386 Befunde**:

| Modul | Befunde | Einordnung |
| ----- | ------- | ---------- |
| `matrix` | 0 | sauber — bestätigt: Specs verlinken **nicht** abwärts auf ADRs (Referenzrichtung hält) |
| `hostpaths` | 0 | sauber — keine Maschinenpfad-Leaks |
| `spans` | 0 | sauber — keine Span-Artefakte |
| `codepaths` | 69 | Repo-Pfad-Refs in Inline-Code; Wert konzentriert in spec/planning |
| `ids` | 317 | **Artefakt eines zu breiten Hand-Musters** — siehe unten |

### Erkenntnis `ids`

Das Hand-Muster `(?:LF|LN)-\d+` erzwang Linkpflicht für 297 Requirement-
Erwähnungen. `d-check --suggest-config` (read-only getestet) zeigt das richtige
Modell: das Tool leitet Kennungen **nur aus Definitions-Headings** ab. In
`spec/lastenheft-d-migrate.md` sind das `UC-01…UC-06`; `LF`/`LN` stehen in
Tabellen/Prosa und werden **nicht** mandatiert. Folgt man dem Tool-Modell, ist
`ids` klein (UC-NN; optional `ADR-NNNN` mit Ziel `docs/adr/` ≈ 19 Treffer).

### Erkenntnis `codepaths`

Der Drift-Wert sitzt in **spec/** und **planning/** (dort werden Code-Pfade
genannt). Genau dort ist es aber als Dauer-Gate verrauscht: Specs sind Zielbild
(referenzieren *künftige* Pfade — z. B. jsqlparser-/rest-/grpc-Adapter), Pläne
referenzieren *vergangene* Pfade, beide nutzen abgekürzte `.../`-Pfade. Auf
reinen Ist-Docs (`docs/user/`) findet `codepaths` fast nur Nutzer-CWD-Pfade
(`./.d-migrate.yaml`, `./export`) und Build-Ausgaben.

**Echte Drift, die der Audit fand (in Phase 2 zu fixen):**

- `CHANGELOG.md` referenziert das **gelöschte** `scripts/verify-doc-refs.sh`.
- Plan-Pfade ohne `/done/` (z. B. `docs/planning/ImpPlan-0.8.0-E.md` statt
  `docs/planning/done/…`).
- Verschobene Quelldateien (z. B. `JdbcUrlBuilder.kt` liegt unter
  `hexagon/ports-common/src/main/kotlin/dev/dmigrate/driver/connection/`,
  Doku zeigt auf alten Pfad).
- `examples/bi-demo/README.md` nennt `./examples/bi-demo/scripts/smoke.sh`
  (Doppelpfad — datei-relativ falsch).

## Offene Entscheidungen

- **D1 — `codepaths`-Strategie.** (a) **Audit statt Dauer-Gate** (empfohlen):
  echte Funde fixen, nicht ins Gate; (b) Gate eng auf Ist-Docs
  (`docs/user`, `docs/adr`, `examples`), spec/planning ausgeschlossen,
  ~14 Marker; (c) Gate breit inkl. spec/planning mit ~50+ `d-check:ignore`-
  Markern (hohe Dauerlast).
- **D2 — `ids`-Scope.** Nur `--suggest-config`-Ableitung (UC-NN) / zusätzlich
  `ADR-NNNN` (≈ 19 Fixes) / vorerst gar nicht. Kein `LF`/`LN`-Mandat.
- **D3 — Eingefrorene Pläne.** Done-Archiv-Ordner anlegen, der vom Scan
  **ausgeschlossen** ist (Vorschlag des Nutzers) **oder** `scan.ignore`
  für `docs/planning/done/**`. Entscheidet, ob `codepaths`/`ids` auf
  historischen Plänen überhaupt laufen.

## Scope-Skizze (Phasen)

1. **Phase 1 — saubere Module ins Gate.** `matrix`, `hostpaths`, `spans` zu
   `modules` hinzufügen (0 Befunde → sofort grün). `matrix` mit Klassen
   `spec`/`adr` und Regel „spec → adr verboten" + verbotenen Stati
   `superseded`/`deprecated`. Eingefrorene Pläne gemäß D3 ausschließen.
2. **Phase 2 — echte Drift fixen.** Die oben gelisteten Audit-Funde beheben
   (gelöschte/verschobene Pfade, fehlendes `/done/`, Doppelpfad). Unabhängig
   von D1 wertvoll.
3. **Phase 3 — `codepaths`/`ids` gemäß D1/D2.** Umsetzen; jede temporäre
   Auskommentierung trägt im `.d-check.yml` **Grund + Auflösungs-Trigger**
   (Carveout-Disziplin, [ADR 0004](../../adr/0004-documentation-and-planning-structure.md)
   / Regelwerk Modul 7 — kein stilles Absenken des Gates).

## Akzeptanzkriterien

- `make docs-check` grün mit der erweiterten `.d-check.yml`.
- `matrix`/`hostpaths`/`spans` dauerhaft aktiv.
- Audit-Funde aus Phase 2 entweder gefixt oder bewusst als
  Zielbild-/Zukunfts-Referenz markiert.
- Jede Auskommentierung im `.d-check.yml` trägt Grund + Trigger.

## Referenzen

- [`.d-check.yml`](../../../.d-check.yml), [`Makefile`](../../../Makefile) `D_CHECK_IMAGE`
- d-check-Spezifikation: `pt9912/d-check` `spec/spezifikation.md` (Module, Grund-Codes, `--suggest-config`)
- Verwandt: [`../open/mcp-server-spec-hygiene-residuals.md`](../open/mcp-server-spec-hygiene-residuals.md)
  (Befund 1 §-Referenzen — `ids`/`matrix` könnten das mechanisieren)
- [`ADR 0004`](../../adr/0004-documentation-and-planning-structure.md) — Planungs-Lebenszyklus
