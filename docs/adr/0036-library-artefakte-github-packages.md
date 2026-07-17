---
status: accepted
date: 2026-07-17
decision-makers: pt9912
consulted: docs/planning/in-progress/roadmap.md, docs/user/releasing.md, docs/planning/open/d-browser-integration-coupling-assessment.md
informed: hexagon/core, hexagon/ports, adapters/driven/driver-common, .github/workflows
---

# Library-Artefakte über GitHub Packages statt Maven Central

> **Status: accepted (2026-07-17).** Der für Milestone 1.0.0 vorgesehene
> **Maven-Central-Portal**-Publish-Workflow entfällt. Stabile Library-Artefakte werden
> stattdessen über **GitHub Packages** (`maven.pkg.github.com`) veröffentlicht. Die Absicht,
> wiederverwendbare Libraries anzubieten, bleibt bestehen — nur der Kanal wechselt. Die
> **1.0.0-Artefaktklassifikation** in [`releasing.md`](../user/releasing.md) bleibt gültig — der
> Kanalwechsel berührt sie nicht. (Sie wurde am selben Tag aus einem **anderen** Grund
> aktualisiert: sie war gegenüber dem tatsächlichen Modulschnitt veraltet.)

## Kontext und Problemstellung

Die Roadmap führte für Milestone 1.0.0 die Zeile „Maven-Central-Portal Publish-Workflow für
stabile Library-Artefakte". Vorbereitet war dafür einiges: die Artefaktklassifikation
(Foundation / Ports / Driver Runtime = Kernartefakte, Optional Extensions = Zusatzartefakte,
Internal Tooling = nicht publiziert) entstand in 0.9.1 Phase G, und der Modul-Refactor
(Read-/Write-Schnitt in den Ports, Extraktion des Profilings aus den Treiberkernen) war
ausdrücklich durch den externen Consumer `d-browser` motiviert
([Coupling-Assessment](../planning/open/d-browser-integration-coupling-assessment.md)).

Verifizierter Code-Ist-Stand (2026-07-17):

1. **Es existiert kein Publish-Pfad — gar keiner.** Kein `maven-publish`-Plugin, keine
   `publishing {}`-Konvention, kein `signing`, kein Sonatype-/Nexus-Setup, nicht einmal ein
   `publishToMavenLocal`-Aufruf. Der Workflow wäre ein Neubau, kein Kanalwechsel an etwas
   Bestehendem.
2. **Kein Consumer konsumiert heute etwas.** `d-browser` bezieht keine Artefakte; der
   `source-d-migrate`-Adapter ist Draft. Der Kanal bedient also genau einen bekannten,
   projekteigenen Consumer — keine anonyme Ökosystem-Nachfrage.
3. **Die Koordinate ist nicht Central-fähig.** `group = "dev.dmigrate"`
   ([`build.gradle.kts`](../../build.gradle.kts)) verlangt auf Central den Nachweis der Domain
   `dmigrate.dev` per DNS-TXT-Eintrag. Die Domain steht dem Projekt nicht zur Verfügung; Central
   erzwänge also zusätzlich einen Namespace-Wechsel auf `io.github.pt9912`.

Maven Central bringt darüber hinaus dauerhafte Lasten mit, die unabhängig vom Erstaufwand
wirken: GPG-Signing mit Schlüsselverwaltung und -rotation bei jedem Release, und vor allem
**Unwiderruflichkeit** — publizierte Koordinaten sind permanent und nicht löschbar. Jede
veröffentlichte API-Fläche wäre damit ab dem ersten Push eine unumkehrbare Zusage.

Frage dieser ADR: Über welchen Kanal werden die Library-Artefakte veröffentlicht, wenn der
Nutzen (ein bekannter Consumer) den Central-Aufwand (Domain-Nachweis, Namespace-Wechsel,
GPG-Dauerlast, Unwiderruflichkeit) nicht trägt?

## Entscheidungstreiber

- **Aufwand im Verhältnis zur realen Nachfrage** — ein projekteigener Consumer, kein
  Ökosystem-Druck.
- **Reversibilität** — die erste Library-Zusage eines Projekts sollte korrigierbar sein.
- **Kein zusätzliches Geheimnis-/Schlüsselmaterial** in der Release-Pipeline.
- **Konsistenz mit vorhandener Praxis** — der GHCR-Push läuft bereits mit `GITHUB_TOKEN`.
- **Der Modul-Refactor trägt sich selbst** — der Read-/Write-Schnitt und die Profiling-Extraktion
  bleiben fachlich richtig, unabhängig davon, ob je etwas publiziert wird.

## Betrachtete Optionen

- **A — Maven-Central-Portal.** Der ursprüngliche Roadmap-Plan.
- **B — GitHub Packages** (`maven.pkg.github.com`).
- **C — Kein Library-Publish.** Konsumenten gehen ausschließlich über CLI und MCP.
- **D — Nur `publishToMavenLocal` / Composite Build.** Consumer baut aus dem Quellstand.

## Entscheidung

**Gewählt: Option B — GitHub Packages.**

Der Kanal deckt den einen realen Consumer vollständig ab und kostet dabei weder Domain-Nachweis
noch GPG-Verwaltung: Authentifizierung läuft über dasselbe `GITHUB_TOKEN`, mit dem der Tag-Build
schon nach GHCR pusht. Die Koordinate `dev.dmigrate` bleibt nutzbar, weil GitHub Packages die
`groupId` nicht gegen eine Domain verifiziert — der Namespace-Wechsel entfällt. Und Pakete
bleiben löschbar, was zur Unsicherheit einer allerersten API-Zusage passt.

Option A wurde verworfen, weil ihr Preis (Domain-Nachweis oder Namespace-Wechsel, GPG-Dauerlast,
Unwiderruflichkeit) auf einen Nutzen trifft, den heute niemand einfordert. Option C wurde
verworfen, weil sie den bereits geleisteten, auf Wiederverwendung zugeschnittenen Modul-Refactor
ohne Abnehmer ließe. Option D wurde verworfen, weil sie keine versionierten, reproduzierbaren
Stände liefert und jeden Consumer an den Quellbaum bindet.

## Konsequenzen

**Positiv**

- Kein GPG-Signing, keine Schlüsselrotation, kein Domain-Nachweis, kein Namespace-Wechsel.
- Die Auth-Mechanik ist dieselbe wie beim GHCR-Push — kein neues Geheimnis in der Pipeline.
- Fehlpublikationen sind revidierbar (Pakete löschbar).
- Die Artefaktklassifikation in [`releasing.md`](../user/releasing.md) bleibt gültig; der
  Kanalwechsel ändert nichts daran, welche Module publiziert werden.

**Negativ — bewusst in Kauf genommen**

- **Konsumenten müssen sich authentifizieren, auch bei öffentlichen Paketen.** GitHub Packages
  verlangt für die Maven-Registry ein Token mit `read:packages`; ein anonymes
  `dependencies { implementation("dev.dmigrate:core:…") }` funktioniert nicht. Für den
  projekteigenen `d-browser` ist das tragbar, für unbekannte Dritte ist es eine echte Hürde.
  **Damit bleibt d-migrate praktisch werkzeug-first:** der Library-Konsum ist ein Angebot an
  bekannte Consumer, kein offener Ökosystem-Kanal. Wer d-migrate ohne Anmeldung nutzen will,
  nimmt CLI, OCI-Image oder MCP.
- **Bindung an GitHub** (Konto und Repository). Ein späterer Wechsel nach Central bleibt möglich,
  da die Koordinaten stabil bleiben; er wäre über eine Supersession dieser ADR zu führen.

**Neutral**

- Der Publish-Workflow ist mit dieser Entscheidung **noch nicht gebaut**. Die Roadmap-Zeile bleibt
  ausstehend und trägt lediglich den neuen Kanal.

## Confirmation

- Die Roadmap-Zeile in Milestone 1.0.0 nennt GitHub Packages statt Maven-Central-Portal; der
  vorgeschaltete Hinweis im 0.9.1-Abschnitt ebenso.
- Der Vorbemerkungs-Hinweis in [`releasing.md`](../user/releasing.md) nennt den geänderten Kanal.
- `make docs-check` ist grün (Link- und Ankerprüfung über `docs/` und `spec/`).

## Weitere Informationen

- [Coupling-Assessment `d-browser`](../planning/open/d-browser-integration-coupling-assessment.md)
  — Herkunft der Wiederverwendungs-Motivation.
- [`releasing.md`](../user/releasing.md) — 1.0.0-Artefaktklassifikation (Gruppen und Publish-Ziele).
- [ADR 0004](0004-documentation-and-planning-structure.md) — Planungs- und Dokumentationsstruktur.
