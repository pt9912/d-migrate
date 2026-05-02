# Refactor 2

Dieses Paket setzt die sechs priorisierten Qualitätsmaßnahmen aus `docs/user/quality-report.md` um.

## Ziel

1. Fehlerausgaben zentral scrubben und vereinheitlichen.
2. Produktive `-- TODO`-DDL entfernen.
3. Große Runner weiter in klarere Schritte aufteilen.
4. `FilterDslTranslator` auf einen gemeinsamen AST-Visitor umstellen.
5. Plan-/Phasenwissen aus Hot Paths reduzieren.
6. Zuvor stille Checkpoint-/Callback-Fehler mindestens einmal warnend loggen.

## Umsetzung

- Neue Utility `UserFacingErrors` für URL-Scrubbing in User- und Log-Ausgaben.
- Export-/Import-/Transfer-/Schema-Runner auf denselben Scrubbing-Pfad umgestellt.
- Routinen-Helper erzeugen bei manueller Nacharbeit nur noch strukturierte `ACTION_REQUIRED`-Hinweise.
- Filter-Rendering nutzt jetzt einen gemeinsamen Visitor statt doppelter Traversal-Logik.
- Orchestrierung im Export-/Import-Pfad weiter in `resolve`, `validate`, `connect`, `execute`, `report` getrennt.
- Checkpoint- und Chunk-Callback-Fehler werden pro Lauf bzw. Tabelle höchstens einmal gewarnt.
