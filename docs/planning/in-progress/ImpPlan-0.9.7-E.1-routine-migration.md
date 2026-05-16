# Implementierungsplan: 0.9.7 — E.1 Routine-Migration

> **Milestone**: 0.9.7 — Refactoring, Hardening, Diff-basierte Migrationen
> **Workstream**: E.1 Routine-Migration (PostgreSQL `CREATE OR REPLACE
> FUNCTION` / `PROCEDURE`, MySQL Routinen)
> **Status**: in-progress (Slice A ✅ 2026-05-15, Slice B ✅ 2026-05-16 — Slices C/D/E offen)
> **Vorbedingung**: Workstream G ✅ (transactionScope, strukturierte
> Statement-Serialisierung, Execution-Status)
> **Referenz**: `docs/planning/in-progress/diffresult-migration-plan-2.md`
>             §9 E.1 (Workstream-Entscheidungen)
>             `docs/planning/done/ImpPlan-0.9.7-F.4-rename-mapping-invalid-enum.md`
>             `spec/cli-spec.md` §6.1 `schema migrate`

---

## 1. Ziel

Plan-2 §9 E.1 fordert:

> "Der erste Routine-Slice nutzt keinen strukturierenden SQL-Parser.
> Vergleich und Identitaet basieren auf normalisiertem Routine-Text
> plus SHA-256-Hash."

Heute existieren `FunctionDefinition` und `ProcedureDefinition` als
Datentypen, `SchemaComparator` vergleicht sie und produziert
`FunctionDiff`/`ProcedureDiff` mit Operationen
(`CreateFunction`/`ReplaceFunction`/`DropFunction`,
`CreateProcedure`/`ReplaceProcedure`/`DropProcedure`). Aber alle drei
Renderer (PostgreSQL, MySQL, SQLite) ordnen diese Operationen in
`OpCategory.UNSUPPORTED` ein und blocken sie hart als
`DIALECT_UNSUPPORTED_OPERATION`. Es gibt aktuell keinen Renderer-Pfad
fuer Routine-Operationen.

Dieser Workstream fuehrt einen ersten, sicheren Render-Vertrag fuer
Routinen ein, ohne semantisch zu raten:

- Body-Vergleich ueber normalisierten Text + SHA-256-Hash, kein
  SQL-Parser.
- Security-, Definer-, Search-Path-/SQL-Mode-Attribute sind Teil der
  Routine-Identitaet.
- Secret-Scrubbing fuer Bodies vor jeder Display-Serialisierung;
  Reports zeigen standardmaessig Hash, Laenge und Scrubbed-Preview.
- Trennung der Output-Fluesse:
  - **Execution-Plane**: Plan-Statements fuer die eigentliche Ausfuehrung
    (Runner/`migrate`) bleiben unmaskiert, damit Migrationsausführung
    deterministisch bleibt.
  - **Log-/Diagnostic-Plane** (Logging/Diagnostics, Runner-Trace, DB-Fehler-/SQL-Logs):
    folgt einem eigenen zentralen Redaction-Hook und ist standardmaessig scrubbed-only;
    unmaskierte Bodies werden in diesen Ausgabekanälen nicht automatisch ausgegeben.
    Unmaskierter Body ist nur im expliziten Unsafe-Pfad (`--debug-body`) erlaubt.
    - Die Redaction muss an einem zentralen Logging-Event-Serialisierungs-Hook greifen
      (einschliesslich Runner-Events, Runner-Trace, CLI-Ausgabe, DB-Adapter-Fehlerpfaden,
      ausgeführte SQL-Statement-Logs, SQL-Query-/Fehlerpfaden),
      bevor ein Log geschrieben wird.
      Geltungsbereich ist zentral: Report-Serialisierung, Runner-Trace, Artefakt-
      und CLI-Output, plus Fehlerobjekte aus Adapter/Driver/DB-Adapter-Layern.
      Erfolgs-Trace- und Fehlerinformationen werden vor der Auswertung gemeinsam
      redacted; unmaskierter Body erscheint nur mit `--debug-body`.
  - **Display-Plane** (Reports, Test-Goldens, Artifakte):
    standardmaessig nur `{hash, length, scrubbedPreview, scrubbingApplied}`.
    `migrate --output` folgt diesem Plan als Default-Ausgabe. Unmaskierter Body
    ist nur im expliziten Unsafe-Pfad (`--debug-body`) erlaubt.
    - Verwendungsnotiz: `migrate --output` ist als Anzeige-/Diagnoseartefakt
      definiert; für direkt-ausfuehrbare Pipeline-Pfade MUSS `--debug-body`
      explizit gesetzt werden.
- `--debug-body` ist als explizites Unsafe-Flag für Display- und Log-/Diagnostic-Plane definiert.
- Down-Rendering ist bis F.2 nur möglich, wenn ein belastbarer Vorbody bekannt ist:
  - Datei-zu-Datei: nur wenn der alte Body im aktuellen Schema-File vorhanden ist.
  - Datei-zu-DB: nur wenn der alte Body zuverlässig aus der Live-DB gelesen werden kann.
    - Dazu gilt als zuverlässig nur, wenn:
      - die DB-Abfrage den vollständigen Routine-Body zurückliefert (kein leerer/NULL-Body, kein Truncation);
      - kein Rechte-, Timeout- oder Transaktionsfehler auftritt;
      - der Adapter den Body unverändert zurückliefert (keine Treiber-Transformationsartefakte).
- Ist im jeweiligen Down-Pfad kein sicherer Vorbody vorhanden, ist das generische
  Blocker-Signal `ROUTINE_DOWN_BODY_UNKNOWN` zu verwenden.
  (`ROUTINE_REPLACE_DOWN_BODY_UNKNOWN` bleibt im bisherigen Dokumentationskontext als
  Replace-spezifische Legacy-Kennung bestehen.)
- `bodyEmbedding` steuert Persistenzfähigkeit für Artefakte; es ist kein Ersatz für fehlenden Vorbody bei der Down-Generierung im gleichen Lauf.
  - `bodyEmbedding` ist nur ein Artefakt-/Persistenz-Flag, keine Entscheidungsgrundlage für Down-Rendering.
  - Down-Rendering ist **immer** nur möglich, wenn der Vorbody im laufenden Pfad sicher bekannt ist:
    - Datei-zu-Datei: alter Body im `current_schema`
    - Datei-zu-DB: alter Body aus zuverlässigem DB-Readback
  - Ist kein sicherer Vorbody vorhanden, blockiert `--generate-rollback` mit
    `ROUTINE_DOWN_BODY_UNKNOWN` unabhängig vom `bodyEmbedding.status`.
- MySQL-Delimiter werden nie in Artefakte geschrieben.

## 2. Scope-Carve-out fuer 0.9.7

In Scope (dieser Workstream, ggf. in mehreren Slices):

- Modellerweiterung um Security-Attribute (Security
  Invoker/Definer, Search-Path / SQL-Mode, Definer-User).
- Body-Normalisierung + SHA-256-Hash als Routine-Identitaet.
- Routine-Identitaet ist ein deterministischer Fingerprint aus
  normalisiertem Body-Hash plus Signature + Security-/Definer-/Search-Path-/SQL-Mode-Attributen.
- Signatur-Differenzen gehören zu dieser Slice explizit nicht in denselben
  `Replace`-Pfad; `CREATE OR REPLACE` gilt nur fuer gleiche Routine-Signatur.
- PostgreSQL-Renderer fuer `CREATE`/`CREATE OR REPLACE`/`DROP`
  `FUNCTION` und `PROCEDURE`.
- MySQL-Renderer fuer `CREATE FUNCTION`/`CREATE PROCEDURE` und
  `DROP FUNCTION`/`DROP PROCEDURE`. Body wird ohne MySQL-Delimiter
  als einzelnes strukturiertes Statement gespeichert; CLI-Anzeige bleibt
  zunächst delimiterfrei und kann optional einen Delimiter-Wrapper liefern.
- Capability-Contract:
  - `create_or_replace_routine` ist die zentrale, dialektfähige Routine-Engine-Kennzahl
    und ist mindestens typ- und versionsbewusst (`FUNCTION`/`PROCEDURE`, Server-Minimalversion).
    Konkret: `create_or_replace_routine` ist als Mapping je Routineart zu verstehen:
    - `FUNCTION`: `{ enabled: bool, minServerVersion?: string }`
    - `PROCEDURE`: `{ enabled: bool, minServerVersion?: string }`
    - Fehlende oder invalides Mapping für eine Routineart (fehlender Eintrag,
      nicht-objektförmig, nicht parsebare Versionsangabe) gilt als Konfigurationsfehler:
      `enabled=false` für diese Routineart, zusätzliches Diagnosekriterium
      `ROUTINE_CAPABILITY_CONFIG_INVALID`, und alle betroffenen Routine-Operationen
      (`Create*`, `Replace*`, `Drop*`) landen in `MANUAL_ACTION_REQUIRED`
      (inkl. Up-/Down-Pfaden, ohne `CREATE OR REPLACE` und ohne
      `DROP + CREATE`-Fallback) bis das Mapping korrigiert ist.
  - `ReplaceFunction`/`ReplaceProcedure` werden in Up- und Down-Pfad nur als
    - `enabled=false` ist auf den `CREATE OR REPLACE`-Pfad beschränkt; Signatur-Mismatch-Pfade (`DROP + CREATE`) werden nach den separaten Signatur-Differenz-Regeln entschieden.
    `CREATE OR REPLACE` gerendert, wenn der passende Routineart-Eintrag `enabled=true`
    hat und die Serverversion die `minServerVersion` erfüllt.
- Bei gültiger Capability-Konfiguration mit `enabled=false` (oder Typ-/Versions-Unterstützung fehlt) gilt in Slice A/B:
   - `Replace` wird auf keinen Fall durch `DROP + CREATE` ersetzt.
   - Bei ungültiger Capability (`ROUTINE_CAPABILITY_CONFIG_INVALID`) gilt bereits Schritt 1
     (immer `MANUAL_ACTION_REQUIRED`).
   - `DROP + CREATE` bleibt für Signatur-Differenzen möglich, aber nur wenn:
     - die Routineart-Konfiguration parsebar und serverseitig lauffähig ist
       (Mapping vorhanden und `minServerVersion` erfüllt bzw. nicht definiert),
     - in Slice A/B der `Dependency-Sicherheits-Guard` als `SAFE` gilt.
   - Anderenfalls gilt `MANUAL_ACTION_REQUIRED` (ohne DROP+CREATE-Fallback).
  - Slice C (MySQL) ist als Ausnahme erlaubt, `DROP + CREATE` als Ersatz für
    `Replace`-Fälle zu nutzen, wenn `CREATE OR REPLACE` für den betreffenden
    Routinen-Typ auf dem Zielserver nicht unterstützt wird und der
    Dependency-Sicherheits-Guard `SAFE` ist (keine bekannten/unsicheren
    Abhängigkeitskanten). Diese Entscheidung bleibt konservativ und ist auf diese
    Slice-Variante begrenzt.
    - `Dependency-Sicherheits-Guard` hat drei Zustände: `SAFE`, `UNSAFE`, `UNKNOWN`.
      `SAFE` erlaubt den `DROP + CREATE`-Fallback. `UNSAFE` und `UNKNOWN`
      erzwingen `MANUAL_ACTION_REQUIRED`.
  - In Slice D ist `DROP + CREATE` als Ersatzstrategie erlaubt, wenn der
    Dependency-Guard `SAFE` ist:
    - keine bekannten abhängigen Objekte im relevanten Scope
    - keine offenen/potentiellen Kanten zu abhängigen Routine-/View-/Trigger-/Tabellen-Paaren.
  - Ist der sichere Fallback nicht gegeben, wird `MANUAL_ACTION_REQUIRED`
    statt eines stillen Fallbacks ausgegeben.
  - Konservativer Zwischenstatus bis Slice D aktiv ist: In Slices A/B darf für
    Routine-`Replace`-Fälle grundsätzlich kein `DROP + CREATE` ausgegeben werden;
    in Slice C gilt die oben beschriebene MySQL-Ausnahme.
- Secret-Scrubbing fuer Bodies + Report-Defaults (Hash, Laenge,
  Scrubbed-Preview).
  - Alle standardmaessigen Ausgabe-Kanäle (Plan-Artifact, `migrate --output`,
    Goldens/Test-Goldens, Reports) nutzen in der Display-Plane scrubbed-only Body-Felder.
- Ohne `--debug-body` wird kein ungefilterter Body über diese Display-Kanäle ausgegeben.
- Der Default-Output ist delimiterfrei und repräsentiert das canonical Artefakt.
- Up-only-Prinzip im E.1-Kontext:
  - Datei-zu-Datei-Routine-Replace wird bei fehlendem Vorbody blockiert (`ROUTINE_DOWN_BODY_UNKNOWN`),
    unabhängig vom aktuellen `bodyEmbedding.status`.
  - In Datei-zu-DB darf Down mit verlässlich gelesenem Vorbody aus der
    Live-DB generiert werden.
- Down-Rendering fuer Replace nur, wenn der vorangegangene (Down/Legacy)-Body
  vollstaendig bekannt ist (z.B. wenn ein Down-Schema-File den alten Body
  enthält oder die Live-DB ihn liefert).
- Dependency-Sortierung zwischen Routinen, Views, Triggern und
  Tabellen und Sequenzen (Drop reverse-topologisch, Create topologisch).
  - Spec-Update in `spec/cli-spec.md` §6.1 mit dem neuen Up-only-Modus
    und dem `--generate-rollback`-Blockierungsverhalten.
  - `--debug-body` als expliziter unsafe-Ausnahmekanal fuer unmaskierte
    Body-Ausgabe.

Aus Scope:

- SQL-Parser oder semantische Body-Aequivalenz. Body-Vergleich bleibt
  konservativ textbasiert.
- SQLite-Routinen — SQLite hat keine User-Defined-Functions/-Procedures
  im klassischen Sinn; bleibt unveraendert.
- Routine-Rename — gehoert zum spaeteren F.4-Routine-Trigger-View-
  Rename-Slice (`docs/planning/open/ImpPlan-0.9.7-F.4-routine-trigger-view-renames.md`),
  der E.1 als harte Vorbedingung listet.
- Plan-Artefakt-Einbettung von Routine-Bodies — braucht F.2-Body-
  Embedding-Gate; bis dahin blockieren persistierte Mischfaelle
  fuer Replace mit klarer Begruendung.

### Body-Embedding-Gate (Status bis F.2)

- Contract-Status pro Lauf:
  - `ENABLED`: Roh-Body ist in Artefakten sicher persistierbar und Down-Generation ist verlässlich.
  - `DISABLED`: Kein sicherer Persistenzstatus; Execution-Statements bleiben möglich, Display-Plane bleibt scrubbed-only.
  - `BLOCKED`: inkonsistente oder unvollstaendige Gate-Konfiguration.
  - `BLOCKED` ist blockiert für persistente Body-Nutzung in Artefakten; Down-Rendering darf nicht auf gespeicherte Embedding-Daten zurückgreifen. Als sichere Vorbody-Quelle sind weiterhin nur `current_schema` oder ein valider DB-Readback im selben Lauf zulässig.
  - `BLOCKED` ist blockiert für persistente Body-Nutzung in Artefakten; Down-Rendering darf nicht auf gespeicherte Embedding-Daten zurückgreifen. Als sichere Vorbody-Quelle sind weiterhin nur `current_schema` oder ein valider DB-Readback im selben Lauf zulässig.
- Initialzustand in dieser E.1-Iteration ist `DISABLED`.
- Für `ENABLED` ist pro Artefakt/Run mindestens zu erfassen:
  - `bodyEmbedding.status`
  - `bodyEmbedding.version` (z. B. `body-embed.v1`)
  - `bodyEmbedding.source` (`current_schema` | `db_readback` | `none`)
- `--generate-rollback` im Datei-zu-Datei-Modus muss bei
  Routine-`Replace`-Down-Pfaden `ROUTINE_DOWN_BODY_UNKNOWN`
  liefern, solange kein Vorbody in `current_schema` vorliegt.
  - Ist `bodyEmbedding.status != ENABLED`, ist Persistenz deaktiviert, aber der
    unmittelbare Down-Renderpfad bleibt möglich, wenn der Vorbody im gleichen Lauf
    validiert vorliegt.
- `DISABLED` und `BLOCKED` erzwingen standardmaessig scrubbed-only Display-Plane; ungefilterte Bodies nur über `--debug-body`.

## 3. Tranchen

### Deterministische Entscheidungslogik für `Create/Replace/Drop` von Routinen

Präzisierung der Auto-Plan-Regelung (vor F.2):

1. Konfigurationsfehler der Routineart-Capability
   - Wenn `ROUTINE_CAPABILITY_CONFIG_INVALID` aktiv ist, werden alle betroffenen
     Routineoperationen in `MANUAL_ACTION_REQUIRED` gewandelt (einschließlich
     `Create*`, `Replace*`, `Drop*`).
   - `bodyEmbedding` bleibt ein Persistenz- und Artefakt-Flag und darf die
     Vorbody-Validierung im Down-Pfad nicht ersetzen.
2. Signatur-Mismatch
   - Standardziel ist `DROP + CREATE`.
   - Slice A/B gilt: Signatur-Mismatch darf als `DROP + CREATE` gerendert werden, wenn die
     Routineart laut Capability-Contract lauffähig ist (parsebare Routineart-Konfiguration,
     serverseitig unterstützt, `minServerVersion` erfüllt falls gesetzt) und der
     `Dependency-Sicherheits-Guard=SAFE` ist.
      `enabled=false` ist für diesen Signatur-Differenzfall kein Ausschlusskriterium; die Regel folgt ausschließlich den Signatur-Differenz- und Guard-Kriterien.
     Sonst: `MANUAL_ACTION_REQUIRED` (außer Schritt 1).
   - Slice C/D gilt: `DROP + CREATE` nur bei `Dependency-Sicherheits-Guard=SAFE`.
   - Bei `Dependency-Sicherheits-Guard=UNSAFE` oder `UNKNOWN` wird `MANUAL_ACTION_REQUIRED`.
3. Selbe Signatur, geänderte Identity-Attribute (`hash`, `security`, `definer`,
   `search_path`, `sql_mode`)
- Wenn Capability aktiv + Serverversion passt: `CREATE OR REPLACE`.
- Wenn die Capability-Konfiguration gültig ist, die Routineart aber aktuell nicht nutzbar ist (`enabled=false` oder Zielserver unterstützt Routineart nicht):
  - PG (Slice A/B): `MANUAL_ACTION_REQUIRED`.
  - MySQL (Slice C): `DROP + CREATE`, nur bei `Dependency-Sicherheits-Guard=SAFE`.
  - Sonst `MANUAL_ACTION_REQUIRED`.
- Ist die Capability-Konfiguration ungültig (`ROUTINE_CAPABILITY_CONFIG_INVALID`), gilt Schritt 1:
  `MANUAL_ACTION_REQUIRED` für alle betroffenen Routine-Operationen.
4. Vorbody-Prüfung im Down-Pfad
   - Keinerlei Down-Statement ohne sicheren Vorbody:
   - bei `Replace*` ist der Vorbody für die betroffene Routine zwingend.
   - bei `DROP + CREATE` (Signatur-Differenzen) muss der alte Body für den Gegenpfeil
     (Rollback-Ziel) im entsprechenden Source-Mode verlässlich vorliegen.
   - `ROUTINE_DOWN_BODY_UNKNOWN`, falls Vorbody im jeweiligen Laufpfad fehlt
     (Datei-zu-Datei: kein aktueller Schema-Body, Datei-zu-DB: kein zuverlässiger DB-Readback).
5. Fallback-Fälle
   - `Dependency-Sicherheits-Guard=UNSAFE` oder `UNKNOWN` blockiert in allen Slices
     die automatischen `DROP + CREATE`-Fallbacks.

### Slice A — PostgreSQL Functions (MVP, dieser Slice)

Vertikaler erster Schritt: ein Dialekt, eine Routinen-Klasse mit Up- und bekanntem Vorbody-Down-Pfad.

- Modell: `FunctionDefinition` um optionale
  Security-/Definer-/Search-Path-Felder erweitern.
- `RoutineBodyNormalizer` (neu, `hexagon:core`): LF-normalisiert,
  trimmt Whitespace, kanonisiert trailing Semicolons. SHA-256-Hash
  ueber den normalisierten Text.
- `RoutineSignature` ist klar getrennt von `RoutineIdentity`:
  - `RoutineSignature`: strukturelle Signatur (`name`, `params`, `kind`,
    `language`), plus `return type` nur für `FUNCTION`.
    Dialekt-spezifische Optionen, die CREATE-Semantik beeinflussen,
    werden entweder in der Signature/Identity explizit modelliert (z. B.
    `volatility`, `strictness`, `parallel`, `cost`, `security`-Eigenschaften)
    oder als Out-of-Scope mit `MANUAL_ACTION_REQUIRED` gekennzeichnet.
  - `RoutineIdentity`: `RoutineSignature + body_hash + security + definer +`
    `search_path + sql_mode`.
  - PostgreSQL-Slice A übernimmt nur PG-Routine-Spezifika; `create_or_replace_routine`
    wird als generische, dialektfähige Capability im Modell-/Renderer-Vertrag
    verankert und als Routineart-Capability (`FUNCTION`/`PROCEDURE`) interpretiert.
- `sql_mode` ist im Identity-Modell dialekt-spezifisch definiert:
  - PostgreSQL: `sql_mode` wird auf stabilen Platzhalter `NULL` normalisiert,
    da PG kein `sql_mode` kennt; dies ist absichtlich konstant für alle PG-Routinen,
    um Vergleichsstabilität zu garantieren.
  - PostgreSQL `search_path` ist im Identity-Modell normiert als:
    - `,`-getrennte Segmentliste.
    - jedes Segment wird getrimmt, leere Segmente werden verworfen.
    - unquoted Schema-Namen werden auf `lower-case` normalisiert.
    - quoted Identifier werden auf PostgreSQL-konforme Escaping-Regeln zerlegt
      und als canonical quoted Form gespeichert (escaped `"` als `""`).
    - Schlüsselwoerter wie `$user`, `"$user"` werden als identische Platzhalter
      behandelt.
    - Duplikate nach Normalisierung werden dedupliziert (erste Position gewinnt),
      Reihenfolge der übrigen Segmente bleibt erhalten; Ausgabe erfolgt als
      kommagetrennte Zeichenkette.
    - Dadurch werden semantisch äquivalente Schreibweisen deterministisch gleich
      gerechnet.
  - MySQL: normalisiert auf sortierte, upper-case, deduplizierte Token-Liste
    (`IGNORECASE`/`PIPES_AS_CONCAT` etc.), Trennzeichen auf komma-normalisiert.
    Reihenfolgenunterschiede ohne Inhaltsunterschied sind identisch. Die Sortierung ist
    bewusst semantikneutral für Vergleichszwecke; Reihenfolgeeffekte von `sql_mode`
    werden in E.1 nicht interpretiert.
- Bei gleicher Signatur:
  - Gleiches `RoutineIdentity` erzeugt keine Operation.
  - Body-/Security-/Definer-/Search-Path-/SQL-Mode-Differenzen erzeugen
    `ReplaceFunction`.
  - `CreateFunction` und `DropFunction` entstehen nur aus echter Existenz-
    Asymmetrie (Create-/Drop-Delta).
  - Bei Signatur-Differenzen werden `DropFunction` + `CreateFunction`
    emittiert (kein `ReplaceFunction`), sofern kein `ROUTINE_CAPABILITY_CONFIG_INVALID`
    für die betroffene Routineart vorliegt.
- PostgreSQL-Renderer fuer `CreateFunction`, `ReplaceFunction`
  (`CREATE OR REPLACE FUNCTION`) und `DropFunction`. Renderer
  schreibt Function-Signatur (Name, Parameter, Return-Type, Language)
  + Security-Attribute + Body.
- Secret-Scrubbing: ein `RoutineBodyScrubber` (neu) maskiert
  passwort-/secret-aehnliche Literale im Body, bevor er in die
  Display-/Log-/Diagnostic-Plane gelangt. Default-Report-Shape:
  `{hash, length, scrubbedPreview, scrubbingApplied}` statt vollem
  Body.
- Ohne `--debug-body` werden rohe Body-Fragmente in Display- und
  Log-/Diagnostic-Kanälen unterdrueckt.
- Up-Only-Prinzip im aktuellen Diff-Lauf (vor F.2):
  - Datei-zu-Datei: Wenn das Schema-File keinen alten Body in der
    Current-Seite trägt (mit `--generate-rollback`) ist der Down-Replace-Weg
    mit `ROUTINE_DOWN_BODY_UNKNOWN` blockiert.
  - Datei-zu-DB: Down-Replace ist nur möglich, wenn die Live-DB den alten Body
    zuverlässig liefern kann.
- Down-Replace für Routine-Operations (Function/Procedure) ist erlaubt, wenn
  der Vorbody sicher bekannt ist; andernfalls bleibt der Blocker aktiv.
- Spec-Update §6.1: `--generate-rollback` dokumentiert den neuen
  Blocker fuer Routinen ohne bekannten Vorbody.
- Tests:
- `RoutineBodyNormalizerTest`: LF-Norm, Trim, Semicolon, Hash-Stabilitaet.
  - `RoutineBodyScrubberTest`: Passwort-/Token-/Connection-String-Patterns
    werden maskiert; nicht-secret Bodies bleiben unveraendert.
  - `RoutineSearchPathNormalizerTest`: semantisch äquivalente `search_path`-
    Eingaben (quoted/unquoted, Leerzeichen, gleiche Segment-Reihenfolge,
    leere Segmente, Duplikate) ergeben denselben Normalized-String.
  - `Body-Embedding-Gate-ContractTest`: Defaultzustand ist `DISABLED` bis F.2; bei
    `ENABLED` werden Metadata (`status/version/source`) persistiert.
  - `RoutineCapabilityConfigTest`: Fehlende oder invalid formatierte
    `create_or_replace_routine`-Einträge führen deterministisch zu
    `ROUTINE_CAPABILITY_CONFIG_INVALID` + `MANUAL_ACTION_REQUIRED`.
  - `RoutineLoggingRedactionTest`: In allen Logging-/Diagnostic-Hotpaths (CLI/Runner/DB-Adapter)
    wird bei `--debug-body=false` garantiert kein unmaskierter Body ausgegeben.
  - `DBAdapterErrorRedactionTest`: Simulierter DB-Fehler mit in Body-Daten
    enthaltenen Secret-Patterns wird vor Log-Emission zentral maskiert
    (inkl. SQL-Fehlerstrings aus Treiber- oder Query-Fehlerpfad).
  - CLI-Output-Test: Ohne `--debug-body` sind nur scrubbed-default
  (`hash`, `length`, `scrubbedPreview`) sichtbar; unmaskierter Body ist
  in Standard-Output ausgeschlossen.
- PG-Renderer-Tests fuer CreateFunction/ReplaceFunction/DropFunction
  inklusive Down-Replace+Blocker-Pfade bei bekanntem/fehlendem Vorbody.
  - Comparator-Test:
    - gleicher `RoutineSignature` + gleiche `RoutineIdentity` → keine Operation;
    - differierende Identity-Attribute bei gleicher Signatur → ReplaceFunction;
    - Signaturwechsel → DropFunction + CreateFunction.
    - Signaturwechsel bei `ROUTINE_CAPABILITY_CONFIG_INVALID` für die betroffene
      Routineart → `MANUAL_ACTION_REQUIRED` (kein `DROP+CREATE`-Fallback).
    - Signaturwechsel-Down-Pfad wird nur dann für Rollback gerendert, wenn der alte
      Body sicher vorliegt (`current_schema` oder DB-Readback); sonst Blocker.
- End-to-End-Test ueber `SchemaMigrateRunner`: `schema migrate` mit
  geaendertem Function-Body rendert `CREATE OR REPLACE FUNCTION`
  ohne Routine-Drop+Create.

### Slice B — PostgreSQL Procedures ✅ (2026-05-16)

Wie Slice A, aber fuer `ProcedureDefinition` und `Create/Replace/Drop
  Procedure` (mit prozedur-spezifischer Signatur ohne `return type`).
  Reuse von `RoutineBodyNormalizer` und
  `RoutineBodyScrubber`.

Geliefert:

- `ProcedureDiff` um `security`/`definer`/`searchPath`/`sqlMode`-
  ValueChange-Felder erweitert; `SchemaComparator.compareProcedure`
  vergleicht Body via `RoutineBodyNormalizer.hash`.
- `PostgresDiffProcedureOps` rendert `CREATE [OR REPLACE] PROCEDURE`
  und `DROP PROCEDURE` mit denselben Body-Quoting-,
  Dollar-Tag-Kollisions- und Down-Blocker-Pfaden wie der
  Function-Renderer; `ROUTINE_REPLACE_DOWN_BODY_UNKNOWN` /
  `ROUTINE_REPLACE_UP_BODY_UNKNOWN` /
  `ROUTINE_BODY_DOLLAR_TAG_COLLISION` werden geteilt.
- `PostgresDiffDdlGenerator` kategorisiert Procedure-Ops in eine
  eigene `OpCategory.PROCEDURE` und delegiert an die neuen Ops; die
  Boundary-Test-Pinnung wandert auf Trigger.
- Codec, Fingerprint und Reverse-Reader-Carve-out wurden bereits in
  Slice A geliefert, gelten weiterhin auch für Procedures.
- Tests: `SchemaComparatorRoutineTest` (Procedure-Identity-Pins),
  `PostgresDiffProcedureOpsTest` (Create/Replace/Drop Up+Down
  inkl. Blocker), `SchemaMigrateCommandProcedureTest` (E2E).

### Slice C — MySQL Routines

- MySQL-Renderer fuer `CreateFunction`/`CreateProcedure`,
  `ReplaceFunction`/`ReplaceProcedure` und
  `DropFunction`/`DropProcedure`.
- Renderer schreibt Body ohne `DELIMITER`-Wrapper; das Statement ist
  ein einzelner, strukturierter Plan-Statement-Eintrag.
- Canonical Plan-Artifact bleibt delimiterfrei.
  - Up-only-Regel analog zu PG:
  - Up-Replace nutzt `CREATE OR REPLACE` nur, wenn `create_or_replace_routine`
    für den konkreten Routinen-Typ im Mapping aktiviert ist und die MySQL-Zielversion die Routineklasse
    (`FUNCTION`/`PROCEDURE`) sauber unterstützt.
    Bei `ROUTINE_CAPABILITY_CONFIG_INVALID` werden alle betroffenen
    `Create*`/`Replace*`/`Drop*` als `MANUAL_ACTION_REQUIRED` mit Diagnose
    `ROUTINE_CAPABILITY_CONFIG_INVALID` ausgegeben; kein Fallback.
    Bei gültiger Capability-Konfiguration, aber nicht nutzbar (`enabled=false` oder Typ-/Versions-Unterstützung fehlt), ist ein sicherer `DROP` + `CREATE`-Fallback nur bei
    Dependency-Sicherheits-Guard `SAFE` erlaubt; bei `UNSAFE` oder `UNKNOWN` wird
    `MANUAL_ACTION_REQUIRED` ausgegeben.
  - Down-Replace ist möglich, wenn der Vorbody sicher bekannt ist:
    - Datei-zu-Datei: nur wenn Current-Schema den alten Body enthält.
    - Datei-zu-DB: wenn der alte Body sicher aus der Live-DB gelesen wird.
  - Down-Replace nutzt ebenfalls `CREATE OR REPLACE` nur bei
    aktiver Routineart-Capability (`FUNCTION`/`PROCEDURE`) und passender Typ-/Versionsunterstuetzung;
    bei `ROUTINE_CAPABILITY_CONFIG_INVALID` ebenfalls `MANUAL_ACTION_REQUIRED`.
    Bei gültiger Capability-Konfiguration, aber aktuell nicht nutzbar (`enabled=false` oder Typ-/Versions-Unterstützung fehlt), ist nur sicherer
    `DROP` + `CREATE` bei Dependency-Sicherheits-Guard `SAFE` erlaubt,
    bei `UNSAFE` oder `UNKNOWN` `MANUAL_ACTION_REQUIRED`.
  - Ist der alte Body im Dateimodus unbekannt → `ROUTINE_DOWN_BODY_UNKNOWN`.
  - Ist der alte Body im DB-Modus nicht lesbar/zuverlässig → `ROUTINE_DOWN_BODY_UNKNOWN`
    (Blocker statt Fallback auf alternative Down-Strategien).
  - Wenn kein sicherer Vorbody vorliegt, blockiert `--generate-rollback`.
- Standardmäßig bleibt `migrate --output` canonical und delimiterfrei.
  - Optional kann die Anzeige-Schicht Delimiter als separate,
  ausführbare Render-Variante ergänzen (nicht Teil des
  Kern-Renderepfads).
  - Tests: Up-/Down-/Blocker-Renderer-Tests und ein
  E2E gegen ein MySQL-Fixture.
- MySQL-Ausgabekanäle (`--output`, Artefakt-Dumps, Log-/Diagnostic-Plane)
  nutzen ebenfalls standardmaessig Scrubbing (`hash`, `length`, `scrubbedPreview`).
  - E2E-Testfall: `create_or_replace_routine` variiert zwischen `FUNCTION`/`PROCEDURE`
  je nach Server-Unterstützung (z. B. Versionsgates je Routineart), und die erwarteten SQL-Pfade
  werden entsprechend validiert. Bei fehlender Routineart-Capability darf `DROP + CREATE`
  nur unter aktivem Dependency-Safe-Guard (`SAFE`) ausgegeben werden; bei `UNSAFE`/`UNKNOWN`
  wird `MANUAL_ACTION_REQUIRED` erwartet.
- Zusätzlicher negativer Guard-Test: bei unklarem/fehlerhaftem Dependency-Guard (`UNKNOWN`)
  wird statt Fallback niemals `DROP + CREATE` ausgegeben; erwartetes Ergebnis ist
  `MANUAL_ACTION_REQUIRED` mit betroffenen Objektpaaren.
- Zusätzlicher Capability-Mapping-Negativtest: bei fehlendem, inkonsistentem oder
  unparsablem `create_or_replace_routine`-Eintrag für eine betroffene Routineart
  darf weder ein `CREATE OR REPLACE`- noch ein `DROP + CREATE`-Pfad geben; erwartete
  Ausgabe ist `MANUAL_ACTION_REQUIRED` mit `ROUTINE_CAPABILITY_CONFIG_INVALID`.
- MySQL-Regressionstest: Unmaskierter Body darf in Standardsicht nur bei
  `--debug-body` erscheinen.

#### Slice C — Implementation Cut (Sub-Slices + Wiring)

Der Slice-C-Contract oben ist gross; die folgenden Implementierungs-
Entscheidungen halten den Cut deterministisch und klein genug für
einzelne Reviews.

##### Cut in drei Sub-Slices

- **C.1 — Capability- und Debug-Body-Infrastruktur**:
  - Neues Domänenobjekt `RoutineCapability` in `hexagon:core`
    (`dev.dmigrate.core.diff.routine`):
    - `data class RoutineCapability(val function: RoutineKindCapability?, val procedure: RoutineKindCapability?)`
    - `data class RoutineKindCapability(val enabled: Boolean, val minServerVersion: SemVer? = null)`
    - Statisches Default für PostgreSQL (immer `enabled=true`, keine
      `minServerVersion` — Slice A/B Verhalten als no-op-Konfig
      formalisiert) und einen `Mysql`-Default, der gegen MySQL-8+
      validiert.
  - Statusobjekt `RoutineCapabilityResolution` mit Varianten
    `Active`/`Disabled`/`InvalidConfig`. Renderer fragt nur dieses
    Objekt, kennt die Konfig-Quelle nicht.
  - Neue Blocker-/Diagnosekennung `ROUTINE_CAPABILITY_CONFIG_INVALID`
    und `ROUTINE_DOWN_BODY_UNKNOWN` (letztere ersetzt im neuen Code
    `ROUTINE_REPLACE_DOWN_BODY_UNKNOWN`; Legacy-Code bleibt
    rückwärtskompatibel benannt, nur neuer MySQL-Pfad nutzt den
    generischen Namen).
  - CLI-Flag `--debug-body` auf `schema migrate`/`schema rollback`:
    - Verdrahtung: Clikt-Option → `SchemaMigrateRequest.debugBody`
      → Report-Writer-Default
      (`RoutineBodyScrubber.preview(...)` vs roher Body)
      → `DdlGenerationOptions.routineBodyDisplay` (`SCRUBBED_ONLY` |
      `RAW_DEBUG`). Renderer-Execution-Plane bleibt unverändert
      (Execution-Statements rendern immer den Roh-Body — Display vs
      Execution sind zwei Welten).
    - Default in jedem Pfad: `SCRUBBED_ONLY`.
  - Capability-Konfiguration-Quelle:
    - Heute hardcoded je Dialekt in `RoutineCapabilityDefaults`
      (PostgreSQL / MySQL); kein YAML/CLI-Override in Slice C.
    - Erweiterung auf konfigurierbare Quelle (CLI-Flag,
      YAML-Eintrag) erst in einem späteren Slice — der Vertrag
      `RoutineCapability` ist so geschnitten, dass dieser Schritt
      keine API-Erweiterung am Renderer braucht.
  - **DoD C.1**: `RoutineCapability` + `RoutineCapabilityResolution`
    + `--debug-body`-CLI-Pfad existieren, sind getestet, aber **noch
    nicht** vom MySQL-Renderer konsumiert. Function-/Procedure-
    Renderer in PostgreSQL bleiben byte-identisch (Capability immer
    `Active`).

- **C.2 — MySQL Function/Procedure Renderer Up + Down**:
  - Neuer `MysqlDiffRoutineOps` rendert
    `Create*`/`Replace*`/`Drop*Function|Procedure`. Body ohne
    `DELIMITER`-Wrapper als ein strukturiertes Statement. Beziehung
    zu Slice A/B-Helfern: eigener Body-Render-Pfad (kein Dollar-Tag,
    kein PostgreSQL-Quoting), aber Reuse von `RoutineBodyNormalizer`
    (Comparator) und `RoutineBodyScrubber` (Reports).
  - `MysqlDiffDdlGenerator` lernt `OpCategory.ROUTINE` (Function +
    Procedure in einer Kategorie, da MySQL beide gleich behandelt).
  - Capability-Gate: Renderer fragt
    `ctx.options.routineCapability.resolveFor(...)` ab und entscheidet:
    - `Active` + Body bekannt → `CREATE OR REPLACE`.
    - `Disabled` → bei `Dependency-Guard=SAFE` `DROP+CREATE`, sonst
      `MANUAL_ACTION_REQUIRED`.
    - `InvalidConfig` → `MANUAL_ACTION_REQUIRED` +
      `ROUTINE_CAPABILITY_CONFIG_INVALID`.
  - Dependency-Guard in Slice C ist fest auf `UNKNOWN` verdrahtet
    (Plan §3 erlaubt das; Slice D liefert die echte Berechnung).
    Konsequenz: alle Disabled/Signatur-Mismatch-Fälle blocken in
    Slice C mit `MANUAL_ACTION_REQUIRED`. Das ist absichtlich
    konservativ und im Test gepinnt.
  - Server-Version: kommt aus dem MySQL-Adapter-Layer:
    - File-zu-File (kein Live-DB): `RoutineCapability.minServerVersion`
      wird nicht aufgelöst → Resolution = `Active` falls
      `enabled=true` UND `minServerVersion=null`, sonst `Disabled`.
    - File-zu-DB: Reader liefert
      `MysqlServerInfo(version: SemVer)` als optionales Feld in
      `DdlGenerationOptions.mysqlServerVersion`; `resolveFor` nutzt
      es zur Version-Prüfung. Fehlt das Feld bei DB-Operanden, gilt
      bewusst `Disabled` (sicher).
  - **DoD C.2**: MySQL-Function- und -Procedure-Routinen rendern
    Up + Down (mit known prior body), Scrubbing in Reports aktiv,
    delimiterfrei. PG-Renderer unverändert. Trigger und alle
    Disabled/Signatur-Mismatch-Pfade landen weiterhin in
    `MANUAL_ACTION_REQUIRED` weil Guard=UNKNOWN.

- **C.3 — Dependency-Guard-Stub + DROP+CREATE-Fallback**:
  - Konservativer Dependency-Guard für Slice C: `DependencyGuard`
    in `hexagon:core` als `enum { SAFE, UNSAFE, UNKNOWN }` plus
    Berechner `DependencyGuardEvaluator` mit einer **explizit
    konservativen** Heuristik: ohne Slice-D-Topologie gilt jede
    Routine-Operation als `UNSAFE`, sobald irgendein anderer
    Routine-/View-/Trigger-/Tabellen-Op im selben Plan steht; sonst
    `SAFE`. Ein Diagnose-Hinweis `DEPENDENCY_GUARD_HEURISTIC` markiert
    diese Stub-Bewertung im Report.
  - Renderer nutzt den Guard für den `Disabled`-Pfad:
    `SAFE` → `DROP+CREATE`, sonst `MANUAL_ACTION_REQUIRED`.
  - **DoD C.3**: `Disabled`-Capability-Fälle landen mit `SAFE`-Guard
    auf `DROP+CREATE`, mit `UNSAFE`/`UNKNOWN` weiterhin auf
    `MANUAL_ACTION_REQUIRED`. Slice D wird den Heuristik-Stub
    ablösen, aber der Renderer-Contract bleibt stabil.

##### Akzeptanzkriterien pro Sub-Slice

- C.1: `RoutineCapability`-Tests pinnen die drei Resolution-Zweige;
  `--debug-body`-CLI-Test pinnt, dass ohne Flag der Report weiterhin
  scrubbed-only ist und mit Flag der Roh-Body landet; Default-Tests
  für PostgreSQL- und MySQL-Defaults; CHANGELOG-Eintrag C.1.
- C.2: `MysqlDiffRoutineOpsTest` für Up/Down Create/Replace/Drop
  Function+Procedure inklusive `CREATE OR REPLACE`-Pfad, plus
  Capability-Negativtests (`Disabled` → MANUAL,
  `InvalidConfig` → ROUTINE_CAPABILITY_CONFIG_INVALID).
  E2E `SchemaMigrateCommandMysqlRoutineTest` mit Fixture-Paar.
  Scrubbing-Regression: ohne `--debug-body` taucht in der
  Report-JSON kein Roh-Body-Token auf.
- C.3: Guard-Stub-Test (SAFE bei isolierter Routine, UNSAFE sobald
  weitere Routine-/Tabellen-Ops im Plan); Renderer-Test pinnt
  `DROP+CREATE` nur unter `SAFE`. Diagnose-Test pinnt
  `DEPENDENCY_GUARD_HEURISTIC`.

##### Modulgrenzen / Wiring-Pfad

```
hexagon:core
  └── dev.dmigrate.core.diff.routine.RoutineCapability
  └── dev.dmigrate.core.diff.routine.RoutineCapabilityResolution
  └── dev.dmigrate.core.diff.routine.RoutineCapabilityDefaults
  └── dev.dmigrate.core.diff.routine.DependencyGuard  (C.3)
  └── dev.dmigrate.core.diff.routine.DependencyGuardEvaluator  (C.3)

hexagon:ports-read (DdlGenerationOptions)
  └── + routineCapability: RoutineCapability  (default = NOT_DECLARED)
  └── + mysqlServerVersion: SemVer?
  └── + routineBodyDisplay: RoutineBodyDisplay  (SCRUBBED_ONLY|RAW_DEBUG)

adapters/driving/cli  (SchemaMigrateCommand + Runner)
  └── --debug-body Clikt-Option → SchemaMigrateRequest.debugBody
  └── SchemaMigrateRenderPipeline.buildRenderOptions baut
      RoutineBodyDisplay + RoutineCapabilityDefaults.forDialect(dialect)

adapters/driven/driver-mysql
  └── MysqlDiffRoutineOps (C.2)
  └── MysqlRoutineReader liest MysqlServerInfo (file-zu-DB)
  └── MysqlDdlGenerator routet Routine-Ops in MysqlDiffRoutineOps
```

##### Auswirkung auf bestehende Slice-A/B-Tests

- Slice A/B-Tests bleiben byte-identisch grün, weil
  PostgreSQL-Default-Capability ein `Active`-Resolution für alle
  Routinearten erzeugt und der PG-Renderer den Capability-Gate
  durchläuft, ohne den bisherigen Pfad zu ändern.
- Reverse-Reader-Carve-out (Slice A) bleibt aktiv; Slice E öffnet
  den Body-Readback.

### Slice D — Dependency-Sortierung Routine ↔ Tabelle/View/Trigger/Sequence

- `OperationMapper`/`OperationOrderer` bekommt einen
  Dependency-Sort-Schritt, der Routinen, Views, Trigger, Tabellen und Sequenzen
  in der korrekten Reihenfolge anordnet:
  - Drop- und Create-Reihenfolge werden per objektuebergreifender
  Topologie berechnet, nicht per fixer Hardcoded-Liste.
  - Drop bleibt semantisch reverse-topologisch (`abhaengige Objekte` vor
  ihren Abhaengigkeiten), Create bleibt topologisch.
  - Bei fehlenden Kanten wird deterministisch nach Typ-Prioritaet und
  Lexikografie sortiert.
  - Kantenquelle ist in dieser Slice-Iteration parser-unabhaengig:
  - Datei-zu-Datei: primär im Schema-Manifest gepflegte
    Objekt-Dependency-Metadaten (primäre Quelle für Routine-/Sequence-Kanten).
    Kanten gelten als sicher, wenn:
    - Quelle explizit `dependsOn` kennt und in Richtung abhängigeres
      Objekt zeigt.
    - DB-Metadaten sie explizit bestätigen.
    - Quelle/Paar ist per Klassenregel als deterministisch unabhänggig markiert.
    Unsichere potenzielle Abhängigkeitspaare sind:
    (Routine ↔ Routine), (Routine ↔ Tabelle/View/Sequence), Trigger ↔ Tabelle,
    View ↔ View/Table.
    - Bei fehlenden oder widersprüchlichen Manifest-Kanten:
      - sichere Kanten werden verwendet,
      - für sichere unabhängige Bereiche wird deterministisch nach Typ-Priorität
        + Lexikografie sortiert,
      - für potenziell abhängige Paare ohne sichere Kante wird `MANUAL_ACTION_REQUIRED`
        mit betroffenen Objektpaaren gefordert.
    - Datei-zu-DB:
      - PostgreSQL: Engine-Metadaten wie `pg_depend`/`pg_rewrite`/`pg_trigger`
        je Objektklasse als Primärquelle.
  - MySQL: Engine-Metadaten für Objektbeziehungen, wobei Routine-Body-Abhängigkeiten
        zu Tabellen/Views/Sequenzen nicht allgemeingültig aus Abhängigkeitstabellen abgeleitet
        werden können; diese Kanten bleiben manifest-/konfigurationsbasiert.
  - Test: ein Diff mit allen fünf Objektklassen erzeugt die richtige
  Reihenfolge; eine Tabelle oder Sequenz, die eine Routine nutzt, blockiert nicht mehr
  beim Drop, wenn die tatsächliche Kanten-Graphenaufloesung korrekt ist.
  - Bei Zyklus oder unsortierbaren Dependency-Edges ist ein expliziter
  Fehlerpfad erforderlich (`ROUTINE_DEPENDENCY_CYCLE` oder
  `MANUAL_ACTION_REQUIRED` mit Objektliste).

### Slice E — Down-Rendering wenn Vorbody bekannt

- Wenn die Current-Schema-Side den alten Routine-Body traegt
  (Datei-zu-Datei) oder die Live-DB ihn liefert (Datei-zu-DB), darf
  der Reverse-Pfad `ReplaceFunction` oder `ReplaceProcedure` mit dem alten
  Body emittieren.
- `--generate-rollback` blockiert nur noch, wenn der alte Body
  unbekannt ist, oder bei bewusstem Unsafe-Debug/Debug-Body-Pfad.

## 4. Akzeptanzkriterien (gesamtes E.1)

- [ ] Routine-Body-Vergleich nutzt normalisierten Text + SHA-256-Hash,
      keinen SQL-Parser.
- [ ] Security-, Definer- und Search-Path-Attribute sind Teil der
      Routine-Identitaet und werden im Comparator beruecksichtigt.
- [ ] Reports zeigen standardmaessig Hash, Laenge und Scrubbed-Preview;
  voller Body landet in der Display-Plane nur mit `--debug-body`;
  `Body-Embedding-Gate` beschreibt die Persistenzfähigkeit für Down-Generation,
  nicht die Freigabe unmaskierter Anzeige.
- [ ] `migrate --output` ist bewusst als scrubbed Display-Artifact dokumentiert;
  eine vollständig unmaskierte Routinen-Ausgabe ist nur im expliziten
  `--debug-body`-Pfad erlaubt.
- [ ] `spec/cli-spec.md` dokumentiert klar, dass `migrate --output` in E.1 als
  Anzeige-/Diagnoseartefakt definiert ist und vollständige Routine-Bodies
  als unsichere Ausgabe nur via `--debug-body`/explicit unsafe-Pfad erlaubt sind
  (bestehende Direkt-Execution-Pipelines werden dadurch bewusst als unsicheres
  Verhalten markiert).
- [ ] PostgreSQL-Renderer fuer Function + Procedure rendert
      `CREATE OR REPLACE` (Up) und blockiert `--generate-rollback` im
      Datei-zu-Datei-Modus ohne bekannten Vorbody. Im Datei-zu-DB-Modus ist
      Down-Blocking nur bei fehlendem Vorbody.
  - [ ] MySQL-Renderer schreibt Routine-Bodies ohne `DELIMITER` im
      kanonischen Artefakt. Standardmäßig bleibt `--output` canonical und
      delimiterfrei; eine optionale Anzeige-Variante darf Delimiter als
      ausführbare Zusatz-Schicht ergänzen. MySQL-Standard-Display-Ausgaben bleiben
      scrubbed-only. Down-Replace ist nur erlaubt, wenn Vorbody bekannt ist,
      sonst `ROUTINE_DOWN_BODY_UNKNOWN`.
  - [ ] MySQL nutzt `create_or_replace_routine` Capability und schreibt
   nur bei aktivem Routineart-Capability-Flag `CREATE OR REPLACE` (Up- und Down-Pfad);
    bei fehlender oder invalidierter Routineart-Capability werden alle betroffenen
    `Create*`/`Replace*`/`Drop*` als `MANUAL_ACTION_REQUIRED` ausgegeben;
    ein sicherer Drop+Create-Fallback ist nur mit gültiger Capability-Konfiguration und
    aktivem `Dependency-Sicherheits-Guard` denkbar.
    - Explizit: `Dependency-Sicherheits-Guard=SAFE` => `DROP`+`CREATE` erlaubt,
      `UNSAFE` oder `UNKNOWN` => `MANUAL_ACTION_REQUIRED`.
    - Fehlende/invalides Capability-Mapping erzwingt `ROUTINE_CAPABILITY_CONFIG_INVALID`
      und führt in allen betroffenen Richtungen auf `MANUAL_ACTION_REQUIRED`.
  - Capability ist Routine-Typ-spezifisch (`FUNCTION`/`PROCEDURE`) und beruht auf
    Zielserver-Unterstützung inkl. Versions- und Objektklassen-Prüfung.
- [ ] `--generate-rollback` blockiert mit
      `ROUTINE_DOWN_BODY_UNKNOWN`, wenn der alte Body im jeweiligen
      Laufmodus nicht sicher vorliegt (Datei-zu-Datei: kein `current_schema`-Body,
      Datei-zu-DB: kein zuverlässiger DB-Readback).
      `MANUAL_ACTION_REQUIRED` bleibt nur für andere Ursachen (z. B. `ROUTINE_CAPABILITY_CONFIG_INVALID`,
      Dependency-Guard `UNSAFE`/`UNKNOWN`) vorgesehen.
- [ ] Dependency-Sortierung deckt Tabellen, Views, Routinen, Trigger und
  Sequenzen ab; Drop-Reihenfolge ist reverse-topologisch, Create-Reihenfolge
  topologisch.
  - [ ] Secret-Scrubbing maskiert Passwort-/Token-/Connection-String-
    Literale im Body, bevor er in Log-/Diagnostic-Plane (Logs, Runner-Trace,
    DB-Fehler-/SQL-Logs), Reports oder Test-Goldens gelangt.
  - Default ist `scrubbed-only`; ungefilterter Body wird nur in einem
  expliziten Unsafe-Output-Pfad (`--debug-body`) zugelassen.
  - Es muss ein zentraler Log-Redaction-Hook geben, der alle Body-bezogenen Felder in
    Logging-Events normalisiert; ein Test muss sicherstellen, dass kein unmaskiertes
    Body-Snippet in Runner-/DB-/Diagnostic-Logs ohne `--debug-body` enthalten ist.
- [ ] `--debug-body` ist in `spec/cli-spec.md` §6.1 als unsafe-Override
  dokumentiert.
- [ ] `spec/cli-spec.md` §6.1 dokumentiert den neuen Up-only-Modus
      und das `--generate-rollback`-Blockierungsverhalten.
- [ ] `roadmap.md` und `diffresult-migration-plan-2.md §9 E.1`
      bekommen einen Status-Update.
- [ ] Jede freigeschaltete Objektklasse hat Positiv-, Blocker- und
      Rollback-Tests.

## 5. Definition of Done

- [ ] Alle Akzeptanzkriterien aus §4 erfuellt (oder bewusst auf
      Folgeslices vertagt).
- [ ] Slice-spezifische Abnahme:
  - Slice A: PG-Function-Identitaet, Comparator/Renderer, Blocker- und
    Down-Replace-Pfade für Functions inklusive Up-only verifiziert.
  - Slice B: PG-Procedure-Identitaet, Comparator/Renderer und sichere
    Down-Replace-Guards.
  - Slice C: MySQL-Routine-Renderer, Capability-gesteuertes Replace-Verhalten,
    delimiterfreies Artefakt und Scrubbing vollständig abgedeckt.
  - Slice D: Dependency-Kantensortierung inkl. Fehlerpfade
    (`ROUTINE_DEPENDENCY_CYCLE`, `MANUAL_ACTION_REQUIRED`).
  - Slice E: Down-Rendering nur bei bekanntem Vorbody; `ROUTINE_DOWN_BODY_UNKNOWN`
    korrekt im Blockerpfad dokumentiert.
- [ ] `make docker-test` gruen, Output in `/tmp/build.log`.
- [ ] Coverage `hexagon:core`, `hexagon:application`,
      `adapters:driven:driver-postgresql`,
      `adapters:driven:driver-mysql` jeweils ≥ 90%.
- [ ] CHANGELOG.md hat einen Eintrag pro Slice.
- [ ] Plan-Datei nach `docs/planning/done/` verschoben, sobald alle
      Slices durch sind.

## 6. Risiken

### 6.1 Secret-Leaks in Bodies

Routine-Bodies koennen Passwoerter, Tokens und Connection-Strings
enthalten (z.B. `CREATE FUNCTION ... LANGUAGE plperlu`). Default-Report-
Output muss diese maskieren; ein nicht-scrubbed Body darf nicht in
Diagnostics, Logs, Runner-Trace oder Report-Artefakten landen.
Die Execution-Plane (die tatsächlich ausgefuehrten Statements) bleibt unmaskiert,
damit Migrationen deterministisch bleiben; die Logging-/Diagnostic-Plane bleibt jedoch
scrubbed-only (nur `--debug-body` darf Body-Inhalte freigeben).
Konkret ist eine zentrale Redaction-Funktion für Log/Event-Serialization verpflichtend,
die auf allen Logging-Pfaden (inkl. Treiber-/DB-Logs im Runner) angewendet wird.
Die Redaction muss als gemeinsamer Hook im Logging-Layer implementiert sein
(`LogEventSerializer`/`LogSink`), nicht erst in einzelnen Adapter- oder Renderer-Stellen.

**Mitigation**: `RoutineBodyScrubber` nutzt einen versionierten Pattern-Katalog
`secret-patterns.v1` inklusive Dialekt-spezifischer Muster für
Passwort-/Token-/Connection-String-Literale (quoted/unquoted, escaped,
obfuscation-nahe Formen, Base64/Hex-ähnliche Token). Scrubbed-Output
wird default-only in Reports/Diagnostics verwendet; unmasked ist nur
`--debug-body` möglich. Tests pinnen den Katalog (inklusive Gegenbeispiele)
und ein Audit-Test stellt sicher, dass kein unmaskierter Body-Snippet in
Goldens auftaucht.

### 6.2 MySQL-Delimiter-Annahmen

Manche MySQL-Workflows verlassen sich darauf, dass `CREATE
PROCEDURE`-Statements von `DELIMITER //` umschlossen sind. d-migrate
speichert das Statement ohne Delimiter, weil das Artefakt
deterministisch und Tool-unabhaengig sein soll.

**Mitigation**: Doku im Spec + CHANGELOG nennt das Verhalten explizit.
Canonical Artefakt ist delimiterfrei; die `--output`-Schicht kann optional
ein ausführbares Delimiter-Skript erzeugen. Das ist eine dedizierte
Ausgabevariante, nicht der Kern-Renderpfad.

### 6.3 False-Positive-Replace bei kosmetischen Body-Aenderungen

Normalisierter Textvergleich erkennt z.B. eingefuegte Kommentare als
Aenderung. Operator sieht ein `ReplaceFunction` fuer ein semantisch
identisches Body.

**Mitigation**: bewusste Carve-out fuer diesen Slice — keine
semantische Aequivalenz. Die Doku nennt das. Operator kann ueber
einen spaeteren Slice einen normalisierungs-Overlay (analog
`migration-overlay.v1`) einbringen, der bestimmte Aenderungen als
no-op markiert. Bis dahin: konservativ.

## 7. Out-of-Scope-Verweis

Routine-Rename (`ALTER FUNCTION ... RENAME TO`), Trigger-Rename und
View-Rename gehoeren zu
`ImpPlan-0.9.7-F.4-routine-trigger-view-renames.md`. Der E.1-Slice
liefert deren harte Vorbedingung (Routine-/Trigger-Renderbarkeit),
implementiert aber selbst keinen Rename.
