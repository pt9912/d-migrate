#!/usr/bin/env bash
# Parquet Cut-A (0.9.8) — Sealed-when-Sweep
#
# Faehrt die `rg`-Patterns aus AP12 §8 / AP13 §4.1
# (`docs/planning/done-archive/parquet-decision-template.md:227-237`)
# pro Sealed-Hierarchie und druckt fuer jede einen Treffer-
# Block. Jeder Treffer ist ein potentieller `when`/`is`-
# Zweig, der bei neuen Sealed-Varianten geprueft werden
# muss.
#
# `gradle assemble --warning-mode=fail` deckt nur den
# exhaustive-`when`-Subset ab; dieser Sweep faengt auch
# `else`-Zweige, non-exhaustive `when`-Statements, Reflection-
# und Service-Loader-Pfade sowie Test-Code (AP13 §4.1
# Luecken-Liste).
#
# Exit-Code immer 0 — der Sweep ist eine Inventar-Anzeige,
# kein Gate. PR-Reviewer entscheidet pro Treffer.

set -euo pipefail

if ! command -v rg >/dev/null 2>&1; then
    echo "error: 'rg' (ripgrep) wird benoetigt, ist aber nicht im PATH." >&2
    exit 2
fi

sweep() {
    local label="$1"
    shift
    echo "=== ${label} ==="
    "$@" || true
    echo
}

# AP12 §8.1 — ImportInput (neu: ResolvedBundle in S5a, ResolvedSingleFile in S5b)
sweep "ImportInput — direct is-checks" \
    rg --type kotlin -n 'is ImportInput\.' .
sweep "ImportInput — when-statements" \
    bash -c "rg --type kotlin -n 'when \\(' . | grep -F 'ImportInput' || true"

# AP12 §8.2 — SchemaOrigin (neu: MANIFEST_FALLBACK aus AP9)
sweep "SchemaOrigin — direct is-checks" \
    rg --type kotlin -n 'is SchemaOrigin\.' .
sweep "SchemaOrigin — when-statements" \
    bash -c "rg --type kotlin -n 'when \\(' . | grep -F 'SchemaOrigin' || true"

# AP12 §8.3 — SeekableChunkSource (neu in S2, AP10 §3.2)
sweep "SeekableChunkSource — direct is-checks" \
    rg --type kotlin -n 'is SeekableChunkSource\.' .

# AP12 §8.4 — CheckpointOperationSpecifics (Bundle + SingleFile aus S8)
sweep "CheckpointOperationSpecifics — direct is-checks" \
    rg --type kotlin -n 'is CheckpointOperationSpecifics' .

# AP12 §8.5 — DataExportFormat (PARQUET-Erweiterung in S3)
sweep "DataExportFormat — direct is-checks" \
    rg --type kotlin -n 'is DataExportFormat\.' .
sweep "DataExportFormat — when-statements" \
    bash -c "rg --type kotlin -n 'when \\(' . | grep -F 'DataExportFormat' || true"

echo "Sweep komplett. Pro Treffer pruefen, ob exhaustive (gut)"
echo "oder mit else-Zweig (manuell sealed-sicher machen oder"
echo "begruendet else-belassen)."
