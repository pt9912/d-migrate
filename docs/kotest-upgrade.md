# Kotest-Upgrade auf 6.x

> **Status**: Erledigt (2026-04-15)
> **Prioritaet**: Niedrig
> **Erstellt**: 2026-04-15

---

## Ausgangslage

Das Projekt nutzt aktuell Kotest **5.9.1** (`gradle.properties: kotestVersion`).
Kotest **6.1** ist die aktuelle stabile Version und bringt API-Aenderungen mit,
die bei einem Upgrade beruecksichtigt werden muessen.

## Bekannte Aenderungen in Kotest 6.x

- `EnabledCondition` entfernt — Ersatz: `enabledIf` auf Spec- oder Test-Ebene
- `NamedTag`-API ggf. veraendert
- Neue Spec-Lifecycle-Hooks
- Kotlin 2.x Kompatibilitaet verbessert

## Betroffene Module

Alle Module mit Kotest-Tests:

- `hexagon:core`
- `hexagon:ports`
- `hexagon:application`
- `adapters:driven:driver-common`
- `adapters:driven:driver-postgresql`
- `adapters:driven:driver-mysql`
- `adapters:driven:driver-sqlite`
- `adapters:driven:formats`
- `adapters:driven:integrations`
- `adapters:driven:streaming`
- `adapters:driving:cli`

## Vorgehen

1. `kotestVersion` in `gradle.properties` auf `6.1.x` anheben
2. Kompilierungsfehler beheben (entfernte/umbenannte APIs)
3. Alle Tests lokal und via Docker gruenen lassen
4. Integrations-Tests via `test-integration-docker.sh` pruefen

