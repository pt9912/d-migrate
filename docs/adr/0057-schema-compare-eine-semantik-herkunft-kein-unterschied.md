---
status: accepted
date: 2026-09-17
decision-makers: pt9912
consulted: docs/planning/in-progress/compare-projektion-und-normalisierung.md, docs/planning/in-progress/compare-falsch-positive-cross-dialekt.md, docs/adr/0056-dialekt-schreibweise-roher-sql-texte-in-schema-compare.md, docs/adr/0026-fingerprint-kanonisierung-post-compare.md, docs/adr/0027-reverse-preferences-inhaerente-mehrdeutigkeit.md, docs/adr/0049-abdeckende-und-clustered-indizes-im-neutralen-modell.md, docs/adr/0055-enum-wertevorrat-im-zielbewussten-vergleich.md, spec/dialect-preference-mechanism.md
informed: hexagon/application (SchemaCompareSemantics, CompareSide, compareGenerationCanonicalizer, TypeCanonicalizerWiring, SchemaCompareRunner, SchemaCompareJobWorker, JobArtifactPublisher), hexagon/core (ArtifactKind, ReverseMarkerNormalizer), hexagon/ports-common (DialectCapabilities), adapters/driving/cli (SchemaCompareWiring), adapters/driving/mcp (McpRuntimeRegistries, McpCoreJobWorkerFactory, McpJobArtifacts, SchemaCompareOutcome, SchemaCompareHandler, ArtifactUploadInitHandler), spec/cli-spec.md, spec/mcp-server.md, CHANGELOG.md
---

# Eine Semantik für `schema compare` in CLI, `schema_compare` und `schema_compare_start` — die Herkunft einer Seite ist kein Unterschied

> **Status: accepted (2026-09-17).** Ergänzt und schärft
> [ADR 0056](0056-dialekt-schreibweise-roher-sql-texte-in-schema-compare.md),
> ohne ihn zu übersteuern. Der CLI-Befehl `schema compare`, das MCP-Werkzeug
> `schema_compare` und der MCP-Job `schema_compare_start` vergleichen an einer
> Stelle im Code und damit gleich. Ein Wert, der auf einer Seite nichts über das
> Schema aussagt, ist kein Unterschied. Das gilt für die Reverse-Markierung und
> für Namen, die ein Server vergibt oder nicht führt. Beide Compare-Artefakte
> tragen die eigene Art `COMPARE` und eine gemeinsame Form. Die Frage, ob eine
> Seite `SERIAL` oder IDENTITY meint, beantwortet nicht der Vergleich, sondern
> eine Reverse-Präferenz nach
> [ADR 0027](0027-reverse-preferences-inhaerente-mehrdeutigkeit.md).

## Kontext und Problemstellung

`schema compare` hat drei Oberflächen: den CLI-Befehl, das synchrone
MCP-Werkzeug `schema_compare` und den asynchronen MCP-Job
`schema_compare_start`. Bis zu dieser Entscheidung baute jede Oberfläche ihren
Comparator selbst, und die drei verglichen verschieden:

| | CLI | `schema_compare` | `schema_compare_start` |
| --- | --- | --- | --- |
| Dialekt-Schreibweise (ADR 0056) | gleichgesetzt | gleichgesetzt | wortgleich verglichen |
| Reverse-Markierung (`name`/`version`) | entfernt, gegen ein handgeschriebenes Schema erschien aber ein interner Platzhalter | als Unterschied gezählt | als Unterschied gezählt |
| Ergebnis | Bericht bzw. Dokument | Funde (`findings`); der Überlauf als nacktes Array unter der Art `DIFF` | serialisierter interner `SchemaDiff` unter der Art `DIFF` |

ADR 0056 nennt als Geltungsbereich nur die CLI und das Werkzeug. Ob der Job
dazugehört, „entscheidet dieser ADR nicht". Über MCP ergaben zwei Reverses aus
verschiedenen Dialekten immer `SCHEMA_NAME_CHANGED`: Jeder Reverse schreibt die
Markierung in `name`/`version`, und dort zählte sie als Eigenschaft des Schemas.

Außerdem meldete ein Konsument Unterschiede in zwei Feldern einer
Identity-Spalte als Fehlalarme:

- **`sequence_name`.** PostgreSQL und Oracle vergeben den Namen der Sequenz
  hinter einer IDENTITY-Spalte selbst. Ein Soll-Schema kann ihn deshalb nicht
  tragen (`DialectCapabilities.namesIdentitySequences`). Die Reverses von
  MySQL, SQLite und SQL Server setzen ihn nie.
- **`legacy_serial_syntax`.** Nur PostgreSQL unterscheidet `SERIAL` von
  IDENTITY. Die Reverses von MySQL (`BIGINT AUTO_INCREMENT`) und SQLite
  (`AUTOINCREMENT` unter der Breite `64`) setzten das Flag immer. Ob die Spalte
  als `SERIAL` oder als IDENTITY gemeint war, trägt die Datenbank nicht.

Beide Felder lassen sich im Vergleich ausblenden, und zwar über die
Fähigkeiten des Dialekts einer Seite. Diesen Dialekt liest der Vergleich aus
der Reverse-Markierung. Das ist neu: Bisher kannte `schema compare` keinen
Dialekt, weder den eines Ziels noch den einer Quelle.

Die Entscheidungslage deckte das nicht ab:

- ADR 0056 liest „`schema compare` bleibt streng" als „keine Faltung nach
  Fähigkeit oder Darstellung". Eine Projektion des Sequenznamens hängt aber an
  einer Fähigkeit.
- [ADR 0026](0026-fingerprint-kanonisierung-post-compare.md) begrenzt den
  zielbewussten Vergleichsmodus auf den Migrate-Diff. Die Namens-Projektion
  stammt aus derselben Naht.
- Nach ADR 0027 löst
  [`spec/dialect-preference-mechanism.md`](../../spec/dialect-preference-mechanism.md)
  inhärente Reverse-Mehrdeutigkeiten „nicht durch eine tolerante
  Vergleichs-Faltung". Das Serial-Flag aus MySQL und SQLite erfüllt die
  Definition einer solchen Mehrdeutigkeit.

Die Frage dieses ADR lautet: **Vergleicht `schema compare` in allen Oberflächen
gleich? Und welche Werte darf es dabei übergehen?**

## Entscheidungstreiber

- Dieselbe Frage („Sind diese Schemata dasselbe?") darf nicht davon abhängen,
  ob ein Abnehmer synchron fragt oder einen Job startet. Wer bei großen
  Schemata auf den Job ausweicht, erwartet dieselben Funde.
- Eine Linie braucht **einen** Besitzer (ADR 0056), also eine Semantik, eine
  Stelle im Code und einen ADR.
- Ein Fund, den niemand beheben kann, ist ein Fehlalarm. Das gilt etwa für
  einen Namen, den der Server vergibt. Ein Fund über einen echten
  Fähigkeitsunterschied ist dagegen kein Fehlalarm.
- Wo eine Seite mehrere vertretbare Lesarten zulässt, entscheidet der Anwender,
  nicht der Vergleich (ADR 0027).
- Der Fingerabdruck und `schema migrate` bleiben unberührt (ADR 0056,
  Entscheidung 1).
- Das Hexagon verzweigt nicht nach Dialekt. Was ein Dialekt kann, sagt eine
  benannte Fähigkeit.
- Ein Abnehmer eines geänderten Artefakts soll den Wechsel bemerken, statt die
  neue Form still falsch zu lesen.

## Betrachtete Optionen

### Geltungsbereich

- **A — Der Job vergleicht weiter wortgleich** (der Stand, den ADR 0056
  festhält). Verworfen: Zwei MCP-Wege beantworten dieselbe Frage verschieden,
  und kein Vertrag sagt das dem Abnehmer.
- **B — Jede Oberfläche baut ihren Comparator selbst, mit denselben
  Parametern.** Verworfen: Genau so ist die Abweichung entstanden. Eine
  Sabotage an einer der Verdrahtungen blieb zunächst unbemerkt.
- **C — Eine Stelle für alle drei Oberflächen.** Gewählt.

### Die Markierung und die vom Server vergebenen Namen

- **A — Streng bleiben.** Verworfen: Jedes Paar zweier Reverses meldet die
  Markierung als Namensänderung. Jedes Paar mit einem PostgreSQL- oder
  Oracle-Reverse meldet außerdem Sequenznamen, die niemand setzen kann.
- **B — Beide Felder ganz aus dem Vergleich nehmen.** Verworfen: Zwei
  handgeschriebene Schemata verlören einen echten Unterschied. Ein von Hand
  geschriebener Name, eine Version und ein Sequenzname sind Aussagen.
- **C — Nur vergleichen, wo beide Seiten einen Wert tragen** (wie beim
  MySQL-`engine`). Nicht anwendbar: Eine Reverse-Seite trägt in `name` und
  `version` immer einen Wert, nämlich die Markierung. Bei PostgreSQL gegen
  Oracle tragen beide Seiten einen Sequenznamen, und beide hat ein Server
  vergeben.
- **D — Name und Version nur übergehen, wenn beide Seiten Reverses sind.**
  Verworfen: Ein Reverse gegen ein handgeschriebenes Schema meldete dann einen
  Platzhalter gegen den echten Namen, also einen Wert, der in keinem der beiden
  Schemata steht.
- **E — Projektion an der Fähigkeits-Naht, der Dialekt kommt aus der
  Reverse-Markierung; Name und Version entfallen, sobald eine Seite die
  Markierung trägt.** Gewählt.

### Das Serial-Flag

- **A — Faltung im Vergleich an einer neuen Fähigkeit.** Diese Faltung war
  zuerst gebaut: Das Flag zählte nicht, sobald eine Seite aus einem Dialekt
  stammte, der `SERIAL` und IDENTITY nicht unterscheidet. Verworfen
  (Eigner-Entscheidung vom 2026-09-17): Das Flag ist kein Wert ohne Aussage.
  Es hat zwei vertretbare Lesarten, und solche Mehrdeutigkeiten löst die
  Spezifikation am Reverse, „nie im nachgelagerten Vergleich". Die beiden
  anderen Lesarten, die der Entwurf dieses ADR zur Wahl stellte, sind damit
  ebenfalls verworfen: die Faltung als mit der Spezifikation verträglich zu
  lesen oder sie dort als Ausnahme zu nennen.
- **B — Den Reader ändern**, sodass MySQL und SQLite das Flag nie setzen.
  Verworfen: MySQL → PostgreSQL und SQLite → PostgreSQL erzeugten dann still
  IDENTITY statt `BIGSERIAL`. Das ist eine Regression des Defaults und rät
  gegen einen Teil der Anwender.
- **C — Eine Reverse-Präferenz nach ADR 0027.** Gewählt. Sie ist kein
  Gegenstand dieses ADR, denn ADR 0027 hat den Mechanismus und die wachsende
  Registry in der Spezifikation bereits entschieden.

### Das Compare-Artefakt

- **A — Der serialisierte `SchemaDiff`** (bisheriger Stand des Jobs).
  Verworfen: Er ist eine interne Darstellung mit Kotlin-Feldnamen und
  Typwerten ohne Diskriminator, und `spec/` beschreibt ihn nicht.
- **B — Dieselben Funde wie `schema_compare`, unter der bisherigen Art
  `DIFF`.** Verworfen: Ein Abnehmer des alten Formats scheitert erst beim
  Parsen oder liest die neue Form falsch, und die unveränderte Art kündigt den
  Wechsel nicht an.
- **C — Eine neue Art `COMPARE` für beide Compare-Artefakte, in einer Form;
  der Server erzeugt `DIFF` nicht mehr.** Gewählt (Eigner-Entscheidung vom
  2026-09-17). Ein Abnehmer, der nach `DIFF` sucht, findet nichts und bemerkt
  den Wechsel.
- **D — Wie B, mit einer Formatkennung im Artefakt.** Verworfen: Ein alter
  Abnehmer kennt die Kennung nicht und liest weiter falsch.

## Entscheidung

### 1. Eine Semantik an einer Stelle

Der CLI-Befehl `schema compare`, das Werkzeug `schema_compare` und der Job
`schema_compare_start` vergleichen gleich. Alle drei nutzen dieselbe Faltung
der Dialekt-Schreibweise (ADR 0056, Entscheidung 4), dieselbe Behandlung der
Herkunft (Abschnitt 2) und dieselbe Diagnose `W137`. Die Semantik steht an
einer Stelle im Code (`SchemaCompareSemantics`): Sie baut die Seiten, den
Comparator und die Diagnose. Die drei Verdrahtungen verweisen darauf und bauen
keinen eigenen Comparator.

Damit ist die Frage aus ADR 0056 („Konsequenzen") beantwortet: Der Job gehört
zum Geltungsbereich von dessen Entscheidung 4.

### 2. Was auf einer Seite nichts über das Schema aussagt, ist kein Unterschied

**Die Regel.** Ein Wert, der auf mindestens einer Seite nichts über das Schema
aussagt, ist kein Vergleichsgegenstand. Das trifft zu, wenn der Wert
beschreibt, wie die Seite entstanden ist, oder wenn ein Server ihn vergibt bzw.
nicht führt. Die Regel gilt für die ganze Familie der Namen, die ein Server
vergibt oder nicht führt (`DialectCapabilities.namesIdentitySequences`,
`namesFullTextIndexes`, `namesPartitions`,
`namesSingleColumnConstraints`). Im Einzelnen:

1. **Die Reverse-Markierung zählt nicht.** Jede Oberfläche entfernt sie vor dem
   Vergleich. Trägt **eine** Seite sie, sind `name` und `version` kein
   Vergleichsgegenstand, auch gegen ein handgeschriebenes Schema. Kein Fund und
   kein Bericht nennt die Markierung oder einen Platzhalter für sie. Zwei
   handgeschriebene Schemata vergleichen beide Felder.

   Eine unvollständige Markierung, also das reservierte Präfix ohne den Rest,
   ist ein Fehler und wird nicht verglichen. Die Folgen je Oberfläche:
   - CLI: Exit 7.
   - Werkzeug: `VALIDATION_ERROR` am `schemaRef` der betroffenen Seite.
   - Job: Status `FAILED` mit `RUNNER_ERROR`, ohne Artefakt.
2. **Der Dialekt einer Seite kommt aus ihrer Markierung.** Ein
   handgeschriebenes Schema hat keinen Dialekt, dort bleibt der Vergleich
   streng. Eine Markierung mit einem Dialekt, den die laufende Version nicht
   kennt, ist trotzdem eine Markierung (Punkt 1). Sie liefert aber keinen
   Dialekt für Punkt 3.
3. **Vom Server vergebene Namen zählen nicht.** Der `sequence_name` einer
   IDENTITY-Spalte zählt nicht, sobald eine Seite aus einem Dialekt stammt,
   dessen Server den Namen vergibt (`namesIdentitySequences = false`:
   PostgreSQL, Oracle). Die Projektion betrifft nur den Vergleichswert.
   Gemeldete Definitionen tragen die Namen unverändert.

   **Reichweite (Eigner-Entscheidung vom 2026-09-17).** Angewendet ist die
   Regel heute nur auf den Identity-Sequenznamen. Die drei übrigen Namen der
   Familie bleiben in `schema compare` Vergleichsgegenstand, bis ein
   gemessenes Paar zweier Reverses dort einen solchen Fehlalarm zeigt. Die
   Ausweitung auf einen dieser Namen deckt diese Entscheidung. Sie setzt die
   Messung voraus, und `spec/cli-spec.md` ändert sich mit.
4. **Mehrdeutige Werte sind keine Werte ohne Aussage.** Ein Wert, für den es
   auf einer Seite mehr als eine vertretbare Lesart gibt, fällt nicht unter die
   Regel. Er bleibt ein Unterschied. Welche Lesart gilt, erklärt der Anwender
   am Reverse (ADR 0027). Das betrifft `legacy_serial_syntax`: `schema compare`
   vergleicht das Flag in jeder Paarung. Die Mehrdeutigkeit von MySQL und
   SQLite löst die Reverse-Präferenz `serial`/`identity`, die in der Registry
   von `spec/dialect-preference-mechanism.md` steht.
5. **Fähigkeitsunterschiede bleiben Funde.** Das sind Werte, die auf **beiden**
   Seiten etwas über das Schema aussagen, von denen ein Dialekt aber nur einen
   ausdrücken kann. Beispiele:
   - der Modus einer IDENTITY-Spalte gegen SQL Server (`W140`) und gegen die
     Autowert-Formen von MySQL und SQLite;
   - `stored` einer berechneten Spalte;
   - INCLUDE-Spalten und `clustered`
     ([ADR 0049](0049-abdeckende-und-clustered-indizes-im-neutralen-modell.md));
   - die Darstellung eines Enums
     ([ADR 0055](0055-enum-wertevorrat-im-zielbewussten-vergleich.md));
   - ein gewolltes `smallint → integer` (ADR 0026).
6. **Die Regel gilt nicht für den Fingerabdruck, `schema migrate` und
   `CanonicalPayload`.** Dort hängt die Namens-Projektion wie bisher am Ziel,
   und `legacy_serial_syntax` erzeugt auf PostgreSQL eine andere Spalte.

Punkte 1, 3, 4 und 5 ergeben zusammen eine Prüffrage für jeden Wert, bei dem
zwei Seiten verschieden sind:

| Der Wert … | Folge | Zuständig |
| --- | --- | --- |
| sagt auf einer Seite nichts aus (Herkunft, vom Server vergeben) | kein Unterschied | dieser ADR |
| hat auf einer Seite mehrere vertretbare Lesarten | Unterschied; der Anwender erklärt die Lesart am Reverse | ADR 0027 |
| sagt auf beiden Seiten etwas aus, ein Dialekt kann ihn nicht ausdrücken | Unterschied | ADR 0049, ADR 0055, ADR 0056, `W140` |

### 3. Das Compare-Artefakt

Zwei Artefakte tragen die Art `COMPARE` (`application/json`): das Ergebnis des
Jobs `schema_compare_start` und das Überlauf-Artefakt, das `schema_compare`
anlegt und in `diffArtifactRef` nennt, wenn seine Antwort das Ergebnis nicht
mehr ganz trägt. Beide haben **eine** Form: ein Objekt
`{status, summary, findings}` mit denselben Einträgen wie die Antwort von
`schema_compare`, nie gekürzt.

`truncated`, `diffArtifactRef` und `executionMeta` beschreiben einen Aufruf,
nicht das Ergebnis, und gehören nicht in das Artefakt. Der Job hat keinen
Aufruf in diesem Sinn, und dasselbe Ergebnis soll auf beiden Wegen denselben
Inhalt ergeben.

Die Art `DIFF` erzeugt der Server nicht mehr. Sie bleibt als Filterwert und für
gespeicherte oder hochgeladene Artefakte bestehen. `COMPARE` erzeugt nur der
Server, ein Upload kann diese Art nicht tragen. Der Vertrag steht in
[`spec/mcp-server.md`](../../spec/mcp-server.md).

## Bestätigung

- **`SchemaCompareRuntimeSemanticsTest`** schickt dieselben Paare durch die
  echte Registry (`schema_compare`) und die echte Job-Fabrik
  (`schema_compare_start`) und prüft:
  - Reine Schreibweise ist gleich, eine echte Änderung ist ein Fund.
  - Zwei Reverses verschiedener Dialekte ergeben keinen Namensfund. Das
    Serial-Flag eines MySQL-Reverse ist ein Fund, und keiner mehr, sobald der
    MySQL-Reverse `identity` erklärt hat.
  - Zwei handgeschriebene Schemata bleiben streng und melden Name und Version
    mit den Werten beider Seiten.
  - Ein Reverse gegen ein handgeschriebenes Schema ergibt keinen Name- oder
    Versionsfund, und kein Platzhalter dringt nach außen.
  - Eine halbe Markierung wird abgewiesen.
  - Der Job veröffentlicht die Funde des Werkzeugs vollständig, samt
    `details`. Das Überlauf-Artefakt des Werkzeugs gleicht dem des Jobs in
    Art, Form und Inhalt.
  - `W137` erscheint auf beiden Oberflächen.
- **`SchemaCompareCommandSemanticsTest`** prüft dasselbe über den CLI-Befehl,
  einschließlich Name und Version ohne Platzhalter.
- **`SchemaCompareSemanticsTest`** prüft die zentrale Stelle: Eine Seite weiß,
  ob sie die Markierung trug (`CompareSide.reverseGenerated`), auch bei einem
  unbekannten Dialekt. Name und Version entfallen bei einer und bei zwei
  Reverse-Seiten, und eine halbe Markierung wird abgewiesen.
- **`CompareGenerationProjectionTest`** prüft die Dialektwahl je Paarung
  (`compareGenerationCanonicalizer`):
  - PostgreSQL gegen Oracle blendet beide Namen aus.
  - Die gemeldete Änderung trägt die unprojizierten Namen.
  - Kein Dialekt faltet das Serial-Flag.
  - Der Modus und `stored` bleiben sichtbar.
- **`SchemaMigrateComparatorsTest`** prüft, dass `schema migrate` den
  Sequenznamen und `legacy_serial_syntax` weiter vergleicht.
- **`SchemaCompareOverflowArtifactTest`** prüft das Überlauf-Artefakt: Art
  `COMPARE`, eine Form, alle Funde. Es entsteht auch dann, wenn nur die Anzahl
  der Funde die Grenze überschreitet.
- **`McpCoreJobWorkerFactoryTest`** prüft, dass der Job sein Artefakt unter
  `COMPARE` in dieser Form veröffentlicht.
- **`ArtifactUploadInitHandlerPolicyPathTest`** prüft, dass ein Upload mit der
  Art `COMPARE` abgewiesen wird.
- **`McpOperationalScenarioTest`** fährt den Job über
  `make integration INTEGRATION_TASKS=":test:e2e-cli:test"` durch den
  MCP-Client. Das Artefakt hat über `resources/read` die Art `COMPARE`, und
  `artifact_chunk_get` liefert genau `status`, `summary` und `findings`.
- **Der Compiler.** Der Port `JobArtifactPublisher<in P : Any>` ist im Typ der
  Nutzlast generisch, und die MCP-Seite hat je Job einen typisierten Publisher
  (`McpJobArtifacts`). Ein Compare-Worker mit einem Schema-Publisher
  kompiliert nicht. `SchemaCompareOutcome.artifact` ist die einzige Quelle der
  Artefakt-Form.
- **Sabotage.** Jeder der folgenden Eingriffe macht seine Tests rot:
  - ein strikter Comparator an einer der drei Verdrahtungen;
  - die Faltung an der zentralen Stelle abgeschaltet;
  - die Markierung nicht entfernt;
  - Name und Version trotz Markierung verglichen;
  - die Markierung nur bei bekanntem Dialekt erkannt;
  - der Vergleich faltet das Serial-Flag wieder;
  - das Job-Artefakt oder das Überlauf-Artefakt unter `DIFF`;
  - das Überlauf-Artefakt als nacktes Array;
  - gekürzte Funde im Artefakt;
  - ein Upload, der `COMPARE` annimmt.

## Konsequenzen

**Dafür:**

- Ein Abnehmer bekommt auf jedem Weg dieselben Funde. Zwei Reverses
  verschiedener Dialekte ergeben keinen Namens- und keinen
  Sequenznamen-Fund mehr.
- Die Abweichung, die ADR 0056 festhalten musste, entfällt. Die Linie hat eine
  Stelle im Code.
- Die Prüffrage aus Abschnitt 2 ordnet jede Klasse von Unterschieden genau
  einem Besitzer zu. Keine Klasse wird im Vergleich weggefaltet, die ein
  anderer ADR dem Anwender oder dem Bericht zuweist.
- Der Treiber-Port bekommt keine neue Fähigkeit. Die Regel liest die
  vorhandene Familie der `names*`-Fähigkeiten.
- Ein Abnehmer des alten Artefakts bemerkt den Wechsel, statt falsch zu lesen.

**Dagegen / zu tragen:**

- **Vertragswechsel für MCP-Abnehmer.** Wer das Ergebnis von
  `schema_compare_start` oder das Überlauf-Artefakt unter `DIFF` sucht, findet
  nichts mehr und muss auf `COMPARE` und die neue Form umstellen. Bereits
  gespeicherte `DIFF`-Artefakte bleiben, wie sie sind.
- **Vertragswechsel in der CLI.** Ein Reverse gegen eine Schema-Datei meldet
  keinen Name- oder Versionsfund mehr. Bisher erschien dort ein interner
  Platzhalter gegen den Namen der Datei. Wer diesen Fund als Hinweis „die
  Datei ist kein Reverse" gelesen hat, verliert ihn.
- `schema compare` berücksichtigt den Dialekt, sobald eine Seite ein Reverse
  ist, und das Ergebnis hängt dann an der Markierung. Wer sie von Hand entfernt,
  bekommt den strengen Vergleich. Wer sie beschädigt, bekommt einen Fehler.
- PostgreSQL gegen Oracle meldet **beide** vom Server vergebenen Namen nicht.
- Ohne erklärte Präferenz ist eine PostgreSQL-IDENTITY-Spalte gegen ein
  `AUTO_INCREMENT` aus MySQL (bzw. SQLite unter der Breite `64`) ein Fund.
  Umgekehrt ist eine PostgreSQL-`SERIAL`-Spalte gegen einen Reverse mit
  `identity` ein Fund. Der Anwender muss seine Absicht erklären. Das ist der
  Preis, den ADR 0027 bewusst verlangt. Der Modus bleibt in jedem Fall ein
  Unterschied.
- Die drei übrigen Namen der Familie bleiben in `schema compare`
  Vergleichsgegenstand, bis eine Messung vorliegt. Fehlalarme dort bleiben bis
  dahin stehen.
- `schema compare` und `schema migrate` weichen an zwei weiteren Stellen
  voneinander ab: beim Sequenznamen und bei Name und Version (vgl. ADR 0056,
  „Dagegen").

## Verhältnis zu benachbarten ADRs

- **ADR 0056** wird ergänzt und geschärft, nicht übersteuert, und bleibt
  `accepted` (Eigner-Entscheidung vom 2026-09-17). Der Geltungsbereich seiner
  Entscheidung 4 umfasst jetzt auch den Job. Seine offene Frage unter
  „Konsequenzen" ist damit beantwortet. „Keine Faltung nach Fähigkeit" meint
  die Fähigkeitsunterschiede, die ein Ziel nicht ausdrücken kann. Diese
  bleiben Funde (Abschnitt 2, Punkt 5). Ein Wert, der auf einer Seite nichts
  über das Schema aussagt, ist kein solcher Unterschied und muss deshalb auch
  nicht wegdefiniert werden. Faltungsmenge, Grenze und Rückzug aus ADR 0056
  bleiben unverändert.
- **ADR 0027** wird weder geändert noch ergänzt. Dieser ADR grenzt sich nur
  ab: Die Mehrdeutigkeit zwischen `SERIAL` und IDENTITY wird nach ADR 0027 am
  Reverse gelöst, nicht im Vergleich. ADR 0027 hat eine wachsende Registry in
  der Spezifikation entschieden. Der neue Eintrag steht dort und braucht
  keinen eigenen ADR. Die Konsistenz-Konsequenz von ADR 0027 gilt weiter: Im
  Default schreiben der SQLite-Reverse unter Breite `64` und der MySQL-Reverse
  dasselbe Flag. Mit `identity` lassen es beide weg.
- **ADR 0026** bleibt in seinem Gegenstand unberührt, also bei Typen und
  Äquivalenzen im Fingerabdruck. Der zielbewusste Modus bleibt beim
  Migrate-Diff. Die Namens-Projektion teilt mit ihm nur die Naht
  (`namesIdentitySequences`), nicht den Modus: Sie kennt kein Ziel und keine
  Version und faltet `stored` nicht.
- **ADR 0049 und ADR 0055** behalten ihre Strenge in `schema compare`.
  Abschnitt 2, Punkt 5, nennt sie als Fähigkeits- bzw.
  Darstellungsunterschiede, die Funde bleiben.

## Weitere Informationen

- Der normative Vertrag steht in den Spezifikationen:
  - [`spec/cli-spec.md`](../../spec/cli-spec.md), Abschnitt zu
    `schema compare`: Reverse-Markierung, Sequenzname einer Identity-Spalte,
    `legacy_serial_syntax` und die Grenze des Identity-Modus;
  - [`spec/mcp-server.md`](../../spec/mcp-server.md): „`schema_compare` —
    Funde" mit dem Compare-Artefakt und die Reverse-Präferenzen der Job-Tools;
  - [`spec/dialect-preference-mechanism.md`](../../spec/dialect-preference-mechanism.md):
    der Registry-Eintrag `serial`/`identity` für MySQL und SQLite.
