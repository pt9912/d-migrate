package dev.dmigrate.verify

import dev.dmigrate.core.model.NeutralType

/**
 * LN-009: kanonisiert einen rohen (JDBC-)Wert in eine **dialekt-neutrale**
 * Byte-Form, sodass semantisch gleiche Werte über Dialektgrenzen hinweg
 * identische Bytes liefern.
 *
 * Kern der Quelle↔Ziel-Reconciliation von `data transfer --verify`: pro Zeile
 * werden die kanonisierten Spaltenwerte längen-gerahmt in ein SHA-256 gehasht,
 * die Tabellen-Prüfsumme kombiniert die Zeilendigests reihenfolge-unabhängig
 * (siehe `TableChecksum`).
 *
 * **Projektions-bewusst (ADR 0030 / ADR 0026).** Der Aufrufer projiziert den
 * Spaltentyp zuvor durch den `NeutralTypeCanonicalizer` des Ziel-Dialekts (die
 * Speicher-Realität, z. B. SQLite `boolean`→INTEGER, `uuid`/`datetime`→TEXT) und
 * ruft [canonicalize] mit dem **projizierten Zieltyp** — für Quelle **und** Ziel.
 * Die kanonischen Formen sind so entworfen, dass flattening-äquivalente Werte
 * kollidieren (Boolean unter `Integer` → `1`/`0`; UUID unter `Text` →
 * Lowercase-Hyphen-String). So meldet ein verlustfreier Round-Trip keine Drift.
 *
 * **Null-Behandlung:** [canonicalize] wird **nur für Nicht-Null-Werte** gerufen;
 * `NULL` trennt das Framing strukturell vom Leerstring.
 *
 * **Determinismus-Vertrag:** Die Bytes hängen ausschließlich vom logischen Wert
 * und projizierten Typ ab — nicht von Instanz-Identität, Locale oder Default-
 * Zeitzone. Werte, deren Repräsentation nicht deterministisch interpretierbar ist
 * (fremde `Struct`/unbekannte Treiber-Objekte), werfen
 * [ValueCanonicalizationException]; der Verifier bildet das auf einen echten
 * Verify-Fehler ab (kein stiller Pass).
 */
fun interface ValueCanonicalizer {
    /**
     * @param value ein **Nicht-Null**-Rohwert (JDBC/Java-nativ)
     * @param type der (ziel-projizierte) [NeutralType] der Spalte
     * @return kanonische Byte-Form; für gleiche logische Werte deterministisch gleich
     * @throws ValueCanonicalizationException wenn der Wert nicht deterministisch
     *   kanonisierbar ist
     */
    fun canonicalize(value: Any, type: NeutralType): ByteArray
}

/**
 * Ein Wert lässt sich nicht deterministisch dialekt-neutral kanonisieren
 * (unbekannter Java-Typ, nicht-parsebarer JSON-/Geometry-Inhalt). Vom Verifier
 * zu einem echten Verify-Fehler (Exit 3) verdichtet — nie stillschweigend
 * ignoriert.
 */
class ValueCanonicalizationException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)
