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
#   sequence    Ein Erzeugungs-Fund, dessen Seiten sich nur im Sequenznamen
#               unterscheiden (den vergibt der Server).
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
# Ergebnis.

# Gemeinsamer jq-Kern: die Waechter ueber der vereinheitlichten Liste.
# Ausgabe: je Verstoss eine Zeile "<waechter>: <ort>: <vorher> -> <nachher>".
COMPARE_GUARDS_JQ='
  def notation: gsub(",?\\s*on_(delete|update)=no_action"; "") | gsub("[\\s\"`\\[\\]()]"; "");
  def sequence: gsub(",?sequence=[^,)]*"; "");
  .[]
  | if .kind == "metadata" then
      "metadata: \(.where): \(.before) -> \(.after)"
    elif (.kind == "constraint" or .kind == "index")
         and (.before != null) and (.after != null)
         and ((.before | notation) == (.after | notation)) then
      "notation: \(.where): \(.before) -> \(.after)"
    elif .kind == "generation"
         and (.before != null) and (.after != null)
         and (.before != .after)
         and ((.before | sequence) == (.after | sequence)) then
      "sequence: \(.where): \(.before) -> \(.after)"
    else empty end
'

# CLI-Dokument (`schema compare --output-format json`) -> vereinheitlichte Liste.
compare_guard_items_from_cli() {  # $1=JSON-Datei
    jq '
      (.diff // {}) as $d
      | [ ( ($d.schema_metadata // $d.schemaMetadata // null) as $m
            | select($m != null)
            | ($m | to_entries[] | select(.value != null)
                | {kind: "metadata", where: .key, before: .value.before, after: .value.after}) ),
          ( ($d.tables_changed // $d.tablesChanged // [])[] as $t
            | ( ($t.constraints_changed // $t.constraintsChanged // [])[]
                  | {kind: "constraint", where: "tables.\($t.name)", before, after} ),
              ( ($t.indices_changed // $t.indicesChanged // [])[]
                  | {kind: "index", where: "tables.\($t.name)", before, after} ),
              ( ($t.columns_changed // $t.columnsChanged // [])[]
                  | select(.generation != null)
                  | {kind: "generation", where: "tables.\($t.name).columns.\(.name)",
                     before: .generation.before, after: .generation.after} ) )
        ]
    ' "$1"
}

# MCP-Funde (Array `findings`) -> vereinheitlichte Liste.
compare_guard_items_from_mcp() {  # $1=JSON-Datei mit einem Array von Funden
    jq '
      [ .[]
        | (if (.code == "SCHEMA_NAME_CHANGED" or .code == "SCHEMA_VERSION_CHANGED") then "metadata"
           elif .code == "TABLE_CONSTRAINT_CHANGED" then "constraint"
           elif .code == "TABLE_INDEX_CHANGED" then "index"
           elif .code == "TABLE_COLUMN_GENERATION_CHANGED" then "generation"
           else null end) as $kind
        | select($kind != null)
        | {kind: $kind, where: .path, before: (.details.before // null), after: (.details.after // null)} ]
    ' "$1"
}

# Wendet die Waechter an. Ausgabe: die Verstoesse, je Zeile einer; leer = gut.
compare_guard_violations() {  # $1=Datei mit der vereinheitlichten Liste
    jq -r "$COMPARE_GUARDS_JQ" "$1"
}

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
    others="$(grep -vE "\[E012\]: Check expression '[^']+' references unknown column '_[A-Za-z0-9]+'" <<< "$errors" || true)"
    [ -z "$others" ]
}

