package dev.dmigrate.driver.data

/**
 * Write-/import-oriented options for the streaming importer and
 * [DataWriter.openTable]. Carries trigger mode, conflict handling,
 * error policy, truncate, FK checks, and sequence reseeding.
 *
 * Read-oriented format options (encoding, CSV header/null-string) live
 * in [dev.dmigrate.format.data.FormatReadOptions] and are consumed by
 * format readers independently.
 */
data class ImportOptions(
    /** Trigger behavior during import: FIRE (default), DISABLE, or STRICT. */
    val triggerMode: TriggerMode = TriggerMode.FIRE,

    /**
     * Ob Identity-/Sequence-Spalten nach dem Import dialektspezifisch
     * nachgeführt werden (§6.6). Default `true`. `false` überspringt
     * Schritt 1–3 des Reseeding-Pfads komplett — gedacht für
     * Spezialfälle, in denen der User die Sequence manuell verwaltet.
     */
    val reseedSequences: Boolean = true,

    /**
     * Ob FK-Checks während des Imports deaktiviert werden sollen (§6.8).
     * Default `false`. Auf PG wirft der Writer bei `true` Exit 2 mit
     * Hinweis auf `--schema` oder `DEFERRABLE`-Constraints (F42 /
     * §6.8.1). MySQL/SQLite haben session-lokale Schalter, die im
     * R6-Cleanup auf derselben Connection wieder aktiviert werden
     * müssen (H-R3 / §3.1.1 Schritt 4 / §6.8.2).
     */
    val disableFkChecks: Boolean = false,

    /**
     * Ob die Zieltabelle vor dem Import per `TRUNCATE` (PG) bzw.
     * `DELETE FROM` (MySQL/SQLite) geleert wird. Nicht-atomar (F41 /
     * §6.14): wenn der nachfolgende Import scheitert, bleibt die
     * Tabelle leer.
     */
    val truncate: Boolean = false,

    /**
     * Verhalten bei Konflikten auf Unique-/PK-Constraints (§6.12.2).
     * Default `ABORT` — erster Konflikt bricht den Chunk ab. `SKIP`
     * nutzt `ON CONFLICT DO NOTHING` / `INSERT IGNORE`; `UPDATE` ist
     * der idempotente UPSERT-Pfad für inkrementelle Imports.
     */
    val onConflict: OnConflict = OnConflict.ABORT,

    /**
     * Verhalten bei Chunk-Fehlern (§6.5). Default `ABORT` — erster
     * Fehler beendet den Import mit Exit 5. `SKIP` und `LOG` arbeiten
     * auf Chunk-Granularität; `LOG` vermerkt zusätzlich im Report.
     * **Wichtig**: Pre-Flight-Fehler (Header-Mapping, Target-Metadaten)
     * sind immer ABORT, unabhängig von diesem Wert (M6 / §3.6.1).
     */
    val onError: OnError = OnError.ABORT,
)

/** Trigger-Modus für den Import (§6.7). */
enum class TriggerMode {
    /** Trigger laufen normal. Default. */
    FIRE,

    /**
     * Trigger werden pro Tabelle deaktiviert und am Ende wieder aktiviert.
     * Nur auf PostgreSQL sicher unterstützt; MySQL/SQLite werfen
     * `UnsupportedTriggerModeException`.
     */
    DISABLE,

    /**
     * Pre-Flight-Prüfung: Tabelle darf keine User-Trigger haben, sonst
     * bricht der Import mit Exit 3 ab. Sicherheits-Check für Cases, in
     * denen der User explizit keinen Trigger-Effekt will.
     */
    STRICT,
}

/** Konflikt-Verhalten bei Unique-/PK-Verletzungen (§6.12.2). */
enum class OnConflict {
    /** Erster Konflikt kippt den Chunk (Default). */
    ABORT,

    /** `ON CONFLICT DO NOTHING` / `INSERT IGNORE`. */
    SKIP,

    /** `ON CONFLICT DO UPDATE` / `ON DUPLICATE KEY UPDATE` (UPSERT). */
    UPDATE,
}

/** Chunk-Fehler-Verhalten während Streaming (§6.5). */
enum class OnError {
    /** Erster Chunk-Fehler beendet den Lauf mit Exit 5 (Default). */
    ABORT,

    /** Chunk verwerfen, mit nächstem weitermachen. */
    SKIP,

    /** Wie SKIP, plus strukturierter Report-Eintrag. */
    LOG,
}
