# Unicode Callsite Audit (0.8.0 Phase D)

## Audited: gezielt umgestellt

| Callsite | Entscheidung | Begründung |
|---|---|---|
| `OutputFormatter.printPlain()` Schema-Name | **Umgestellt** auf `UnicodeNormalizer.normalize(name, context.normalization)` | Nutzernahe Anzeige, Unicode-Normalisierung für konsistente Darstellung |

## Audited: bewusst unverändert

| Callsite | Entscheidung | Begründung |
|---|---|---|
| `ObjectKeyCodec.decode()` `.substring(PREFIX.length)` | Technisch | Positionsbasiertes Parsing auf ASCII-Prefixen |
| `ReverseScopeCodec.decode()` `.substring(PREFIX.length)` | Technisch | Prefixbasiertes Parsing |
| `SchemaValidator` `.maxLength` / `.length` | Numerische Metadaten | Prüft Schema-Metadatenwerte, nicht Stringlängen |
| `MigrationSlugNormalizer` | **Bewusst unverändert** per §4.6 | Artefaktidentitäts-Pfad mit Vertragslast |
| `MigrationIdentityResolver` | **Bewusst unverändert** per §4.6 | Slug-/Version-Ableitungen |
| CSV-/JSON-/YAML-Tokenizer | Technisch | Positionsbasierte Byte-/Codeunit-Semantik |
| Escape-Helfer (`escapeJson`, `escapeYaml`, `escapePython`, `escapeJavaScript`) | Technisch | Character-level Escaping |
| `StringBuilder`-Builder in Report-Writern | Technisch | String-Assembling, keine semantische Zeichenlänge |

## Nicht auditiert (out of scope)

- JDBC-nahe Hilfsfunktionen in Driver-Adaptern
- Streaming-Pipeline Byte-/Chunk-Logik
- Clikt-interne String-Verarbeitung
