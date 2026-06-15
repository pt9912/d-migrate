# Tracker: Rest-Hygiene in spec/mcp-server.md (§-Referenzen + Verhaltensaussage)

> **Status:** Sammlung/Tracker (2026-06-15)
> **Trigger:** Bei der Entphasung von `spec/mcp-server.md` (Commit
> „docs(mcp): spec/mcp-server.md entphasen …", develop) wurden zwei
> Rest-Befunde sichtbar, die über die beauftragte Runde (Phasen-Marker +
> `(Plan §…)`-Provenienzen entfernen) hinausgehen und je eigene Klärung
> brauchen.
> **Aktivierungsbedingung:** Wird einer der Punkte priorisiert, entsteht ein
> `next/`-Plan bzw. die Korrektur wird direkt im Spec-Commit erledigt; der
> Eintrag verweist dann darauf.

## Befund 1 — bare `§`-Referenzen (Stil + mehrdeutiges Ziel)

`spec/mcp-server.md` trägt **19** bloße Paragraphen-Referenzen ohne
Markdown-Link, die in einen anderen Vertrag/Plan zeigen:

| Referenz | Häufigkeit | vermutetes Ziel |
| -------- | ---------- | --------------- |
| `§12.13`–`§12.18` (`§12.14`, `§12.17`, `§12.12`, `§12.8`, `§12.7`, `§12.15`, `§12.18`, `§12.13`) | 12 | `docs/planning/done/ImpPlan-0.9.6-B.md` „Implementation Contracts" |
| `§4.2`, `§4.3` | 3 | Auth-/Sicherheitsmodell (ImpPlan-B bzw. `ki-mcp.md`) |
| `§5.5`, `§6.9`, `§6.11` | 3 | ImpPlan-B / `ki-mcp.md` |
| `§8.3` | 1 | explizit `spec/ki-mcp.md` §8.3 (MIME-Allowlist) |

Zwei Probleme:

1. **Stil:** §-Zeichen in Referenzen sind im Repo unerwünscht — nur
   Markdown-Links oder LF/LN-Kennungen (siehe Referenz-Stil-Konvention).
2. **Zielbild-Kopplung:** Die Spec verweist über bloße `§`-Nummern auf einen
   done-Plan (`ImpPlan-0.9.6-B`) bzw. `ki-mcp.md`. Wo die Aussage normativ ist,
   sollte sie ohne Verweis stehen; wo ein echter Peer-Verweis gemeint ist
   (z. B. `ki-mcp.md` §8.3), gehört ein Markdown-Link hin.

**Arbeit:** pro Stelle das tatsächliche Ziel auflösen, dann entweder
Verweis streichen (normative Aussage steht ohnehin da) oder durch einen
Markdown-Link auf die Peer-Spezifikation ersetzen. Nicht mechanisch
ersetzbar — braucht Ziel-Prüfung je Referenz.

## Befund 2 — möglicherweise veraltete Verhaltensaussage (`tools/call`)

Abschnitt „Capabilities & Tools" → „`tools/list` und `tools/call`":

> „`tools/call` für `capabilities_list` läuft fachlich; alle anderen Tools
> antworten mit `UNSUPPORTED_TOOL_OPERATION`."

Das wirkt wie eine **Übergangs-Verhaltensaussage** aus der frühen
Server-Entwicklung und widerspricht dem dokumentierten produktiven
Funktionsumfang (Start-Tools wie `schema_reverse_start`, `data_import_start`
usw. werden über `tools/call` aufgerufen). Kein Phasen-/Plan-Marker, sondern
eine **Vertrags-Genauigkeitsfrage**.

**Arbeit:** gegen den Code (`adapters/driving/mcp`, `ToolRegistry`/Dispatch)
prüfen, ob heute alle registrierten Tools über `tools/call` laufen, und die
Aussage entsprechend korrigieren oder bestätigen. Nicht blind umschreiben.

## Referenzen

- [`../../../spec/mcp-server.md`](../../../spec/mcp-server.md) — betroffenes Zielbild
- [`../../../spec/ki-mcp.md`](../../../spec/ki-mcp.md), [`../done/ImpPlan-0.9.6-B.md`](../done/ImpPlan-0.9.6-B.md) — vermutete `§`-Ziele
- [`../../adr/0004-documentation-and-planning-structure.md`](../../adr/0004-documentation-and-planning-structure.md)
