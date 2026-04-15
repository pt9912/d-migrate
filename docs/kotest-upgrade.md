# Kotest-Upgrade auf 6.x

> **Status**: Erledigt (2026-04-15)
> **Prioritaet**: Niedrig
> **Erstellt**: 2026-04-15

---

## Ergebnis

Upgrade von Kotest **5.9.1** auf **6.1.11** in Commit `c492d1e`.
Keine API-Anpassungen noetig — alle bestehenden Tests kompilieren und
laufen unveraendert. Verifiziert ueber vollstaendigen Docker-Build
(alle Module, alle Tests).

## Geprueft gegen bekannte Breaking Changes in Kotest 6.x

| Aenderung | Betroffen? | Bemerkung |
|---|---|---|
| `EnabledCondition` entfernt | Nein | War nie im Projekt genutzt |
| `NamedTag` ist jetzt `data class` | Nein | Kompatibel, keine Referenzgleichheit genutzt |
| `listeners()` deprecated → `extensions()` | Nein | Nicht genutzt |
| `InstancePerTest`/`InstancePerLeaf` deprecated | Nein | Nicht genutzt |
| `kotest-assertions-api` entfernt | Nein | Projekt nutzt `kotest-assertions-core` |
| `kotest-datatest` in Core gemergt | Nein | `withData` nicht genutzt |
| `io.kotest.matchers.maps.contain` umbenannt | Nein | Nicht genutzt |
| `System.exit`/`System.env` Extensions entfernt | Nein | Nicht genutzt |

## Betroffene Module

Alle 11 Module mit Kotest-Tests — alle unveraendert kompatibel.
