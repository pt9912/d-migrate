# shellcheck shell=bash
# Versionsunabhaengige Waechter fuer die Vergleichs-Harnesses in scripts/.
#
# Benutzt von smoke-cross-dialect-roundtrip.sh (CLI, `schema compare
# --output-format json`) und smoke-compare-matrix.sh (MCP, `schema_compare`
# und `schema_compare_start`). Beide Quellen werden zuerst in **eine** Liste
# gebracht, damit dieselbe Regel auf beide Oberflaechen trifft:
#
#   [{kind: "metadata"|"constraint"|"index"|"generation", where, before, after}]
#
# Die Waechter zielen auf **Klassen** von Fehlalarmen, nicht auf eine
# Zeilenform:
#
#   metadata    Ein Name- oder Versionsfund, obwohl eine Seite ein Reverse ist
#               (die Reverse-Markierung ist keine Eigenschaft des Schemas).
#   notation    Ein CHECK-, Index- oder Fremdschluessel-Fund, dessen beide
#               Seiten nach dem Entfernen von Leerraum, Anfuehrungszeichen,
#               eckigen und runden Klammern und einer ausdruecklichen
#               `no_action` gleich sind — also nur Schreibweise.
#   sequence    Ein Erzeugungs-Fund zweier Identity-Spalten, die sich nur im
#               Sequenznamen unterscheiden (den vergibt der Server). Geprueft
#               wird die **Struktur** `identity(schluessel=wert,…)`, nicht ein
#               einzelnes Token: ein Schluessel, den der Waechter nicht kennt,
#               ist selbst ein Verstoss (`form`).
#   form        Die Ausgabe hat eine Form, die die Waechter nicht lesen
#               koennen — ein umbenannter Schluessel wuerde sie sonst still
#               leer laufen lassen.
#
# **Selbstprobe.** Die Vereinheitlichung prueft, dass sie die Ausgabe
# vollstaendig versteht, und scheitert sonst **laut** (jq-Fehler, Exit != 0):
#
#   CLI  jede Zahl in `summary` ist die Laenge der gleichnamigen Liste in
#        `diff`; eine geaenderte Tabelle traegt nur bekannte Schluessel und
#        mindestens eine Aenderung, eine geaenderte Spalte nur bekannte Felder.
#   MCP  `status` ist `identical` oder `different`; `different` hat Funde,
#        `identical` keine; jeder Fund traegt `code` und `path`, ein
#        Aenderungsfund der Waechter-Arten `details` mit mindestens einer
#        Seite (eine fehlende Seite war dort nicht gesetzt).
#
# **Grenze der notation-Heuristik:** sie ist die Heuristik des Harness, nicht
# die Faltung des Produkts. Gross-/Kleinschreibung und Casts bleiben stehen
# (beides kann Bedeutung tragen, und die Schreibweise von Schluesselwoertern
# ist eine offene Eigner-Frage). Klammern, die eine Rangfolge aendern, oder
# Leerraum in einem Literal wuerde sie faelschlich als Schreibweise lesen —
# die Fixtures der Harnesses enthalten solche Ausdruecke nicht; wer sie
# ergaenzt, muss die Heuristik mitziehen.
#
# Erwartet: jq. Die Funktionen schreiben nichts nach stdout ausser ihrem
# Ergebnis; ihr Exit-Code ist zu pruefen — ein leeres Ergebnis nach einem
# jq-Fehler ist **kein** „keine Verstoesse".

# Die Schluessel, die ein Identity-Wert tragen darf
# (CompareValueText.generation): alles andere ist eine neue Form.
COMPARE_GUARDS_IDENTITY_KEYS='["mode","sequence","legacy_serial_syntax"]'

# Gemeinsamer jq-Kern: die Waechter ueber der vereinheitlichten Liste.
# Ausgabe: je Verstoss eine Zeile "<waechter>: <ort>: <vorher> -> <nachher>".
# shellcheck disable=SC2016 # `$a`, `$b`, `$known` sind jq-Variablen
COMPARE_GUARDS_JQ='
  def notation: gsub(",?\\s*on_(delete|update)=no_action"; "") | gsub("[\\s\"`\\[\\]()]"; "");
  # `identity(mode=by_default,sequence=s)` -> {mode: "by_default", sequence: "s"};
  # null, wenn der Wert keine Identity ist.
  def identity_fields:
    if type == "string" and test("^identity\\(.*\\)$") then
      (capture("^identity\\((?<args>.*)\\)$").args
       | if . == "" then {} else
           [split(",")[] | capture("^(?<k>[a-z_]+)=(?<v>.*)$")? // {k: ("?" + .), v: ""}]
           | map({(.k): .v}) | add
         end)
    else null end;
  if type != "array" then error("Waechter: keine vereinheitlichte Fundliste") else . end
  | .[]
  | if .kind == "metadata" then
      "metadata: \(.where): \(.before) -> \(.after)"
    elif (.kind == "constraint" or .kind == "index")
         and (.before != null) and (.after != null)
         and ((.before | notation) == (.after | notation)) then
      "notation: \(.where): \(.before) -> \(.after)"
    elif .kind == "generation" then
      ((.before | identity_fields) as $b | (.after | identity_fields) as $a
       | (([$b, $a] | map(select(. != null) | keys[]) | unique) - $known) as $unknown
       | if ($unknown | length) > 0 then
           "form: \(.where): unbekannte Identity-Schluessel \($unknown | join(",")): \(.before) -> \(.after)"
         elif $b != null and $a != null and $b != $a
              and (($b | del(.sequence)) == ($a | del(.sequence))) then
           "sequence: \(.where): \(.before) -> \(.after)"
         else empty end)
    else empty end
'

# CLI-Dokument (`schema compare --output-format json`) -> vereinheitlichte Liste.
compare_guard_items_from_cli() {  # $1=JSON-Datei
    jq '
      def known_table_keys: ["name", "columns_added", "columns_removed", "columns_changed", "primary_key",
        "indices_added", "indices_removed", "indices_changed",
        "constraints_added", "constraints_removed", "constraints_changed"];
      def known_column_keys: ["name", "type", "required", "default", "unique", "references", "generation"];
      def probe:
        if (.summary | type) != "object" or ([.summary[] | type] | unique) != ["number"] then
          error("Selbstprobe: `summary` ist kein Objekt aus Zahlen")
        else . end
        | (.diff // {}) as $d
        | ([.summary | to_entries[] | select(.value != (($d[.key] // []) | length)) | .key]) as $off
        | if ($off | length) > 0 then
            error("Selbstprobe: `summary` und `diff` zaehlen verschieden: \($off | join(", "))")
          else . end
        | ([($d.tables_changed // [])[]
            | select(((keys - known_table_keys) | length) > 0 or (keys == ["name"]))
            | .name]) as $tables
        | if ($tables | length) > 0 then
            error("Selbstprobe: geaenderte Tabelle ohne bekannte Aenderung: \($tables | join(", "))")
          else . end
        | ([($d.tables_changed // [])[] | (.columns_changed // [])[]
            | select(((keys - known_column_keys) | length) > 0) | .name]) as $cols
        | if ($cols | length) > 0 then
            error("Selbstprobe: geaenderte Spalte mit unbekanntem Feld: \($cols | join(", "))")
          else . end;
      probe
      | (.diff // {}) as $d
      | [ ( ($d.schema_metadata // null) as $m
            | select($m != null)
            | ($m | to_entries[] | select(.value != null)
                | {kind: "metadata", where: .key, before: .value.before, after: .value.after}) ),
          ( ($d.tables_changed // [])[] as $t
            | ( ($t.constraints_changed // [])[]
                  | {kind: "constraint", where: "tables.\($t.name)", before, after} ),
              ( ($t.indices_changed // [])[]
                  | {kind: "index", where: "tables.\($t.name)", before, after} ),
              ( ($t.columns_changed // [])[]
                  | select(.generation != null)
                  | {kind: "generation", where: "tables.\($t.name).columns.\(.name)",
                     before: .generation.before, after: .generation.after} ) )
        ]
    ' "$1"
}

# MCP-Ergebnis (Antwort von `schema_compare` oder das Compare-Artefakt, beide
# mit `status` und `findings`) -> vereinheitlichte Liste.
compare_guard_items_from_mcp() {  # $1=JSON-Datei mit `status` und `findings`
    jq '
      def kind_of: if (.code == "SCHEMA_NAME_CHANGED" or .code == "SCHEMA_VERSION_CHANGED") then "metadata"
        elif .code == "TABLE_CONSTRAINT_CHANGED" then "constraint"
        elif .code == "TABLE_INDEX_CHANGED" then "index"
        elif .code == "TABLE_COLUMN_GENERATION_CHANGED" then "generation"
        else null end;
      def probe:
        if (.findings | type) != "array" then error("Selbstprobe: keine Fundliste `findings`")
        elif (.status != "identical" and .status != "different") then
          error("Selbstprobe: unbekannter `status` \(.status)")
        elif .status == "different" and (.findings | length) == 0 then
          error("Selbstprobe: `status` different ohne Funde")
        elif .status == "identical" and (.findings | length) > 0 then
          error("Selbstprobe: `status` identical mit Funden")
        elif ([.findings[] | select((.code | type) != "string" or (.path | type) != "string")] | length) > 0 then
          error("Selbstprobe: ein Fund ohne `code` oder `path`")
        elif ([.findings[] | select(kind_of != null and kind_of != "metadata")
               | select((.details | type) != "object" or (.details.before == null and .details.after == null))]
              | length) > 0 then
          error("Selbstprobe: ein Aenderungsfund ohne `details` (weder `before` noch `after`)")
        else . end;
      probe
      | [ .findings[]
          | kind_of as $kind
          | select($kind != null)
          | {kind: $kind, where: .path, before: (.details.before // null), after: (.details.after // null)} ]
    ' "$1"
}

# Wendet die Waechter an. Ausgabe: die Verstoesse, je Zeile einer; leer = gut,
# **aber nur mit Exit 0** — der Aufrufer prueft den Exit-Code.
compare_guard_violations() {  # $1=Datei mit der vereinheitlichten Liste
    jq -r --argjson known "$COMPARE_GUARDS_IDENTITY_KEYS" "$COMPARE_GUARDS_JQ" "$1"
}

# Die Zeichensaetze, die MySQL als Introducer kennt (`SHOW CHARACTER SET`,
# MySQL 8/9). Nur sie gelten als bekannter Reader-Befund — eine andere
# unbekannte Spalte mit fuehrendem Unterstrich (`_tmp`) ist ein anderer Fehler.
COMPARE_GUARDS_MYSQL_CHARSETS='armscii8|ascii|big5|binary|cp1250|cp1251|cp1256|cp1257|cp850|cp852|cp866|cp932|dec8|eucjpms|euckr|gb18030|gb2312|gbk|geostd8|greek|hebrew|hp8|keybcs2|koi8r|koi8u|latin1|latin2|latin5|latin7|macce|macroman|sjis|swe7|tis620|ucs2|ujis|utf16|utf16le|utf32|utf8|utf8mb3|utf8mb4'

# Der bekannte Reader-Befund „MySQL-Zeichensatz-Introducer" (Posten 4 des
# Compare-Slices, im Reader-Slice als C1/P6): MySQL legt String-Literale in
# CHECK-Ausdruecken mit Introducer ab (`_latin1'…'`, `_utf8mb4'…'`), der
# Reverse uebernimmt ihn, und die Validierung liest ihn als Spalte (`E012`).
# Die CLI weist ein solches Schema ab (`schema compare`/`schema generate`:
# Exit 3). Rueckgabe 0 genau dann, wenn das Protokoll mindestens einen
# solchen Fehler traegt und **keinen anderen** — jeder andere Grund bleibt
# ein Fehlschlag des Harness.
known_introducer_invalid() {  # $1=Protokoll des CLI-Laufs
    local errors others
    errors="$(grep -E 'Error .*\[E[0-9]+\]' "$1" || true)"
    [ -n "$errors" ] || return 1
    # Ohne `-q`: `grep -vq` verhaelt sich nicht in jeder grep-Implementierung
    # gleich (ugrep meldet dort keinen Treffer).
    others="$(grep -vE "\[E012\]: Check expression '[^']+' references unknown column '_($COMPARE_GUARDS_MYSQL_CHARSETS)'\$" \
        <<< "$errors" || true)"
    [ -z "$others" ]
}
