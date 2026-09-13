package dev.dmigrate.driver

import dev.dmigrate.driver.connection.JdbcUrlBuilder
import dev.dmigrate.driver.data.DataReader
import dev.dmigrate.driver.data.DataWriter
import dev.dmigrate.driver.data.TableLister

/**
 * Central port interface for database access. Each supported database
 * dialect provides an implementation that bundles all driver-specific
 * capabilities behind this facade.
 *
 * Faehigkeiten (`kann das Ziel das?`) beantwortet jeder Treiber ueber
 * [DialectCapabilityProvider] selbst — die Werte liegen im Treibermodul,
 * nicht in einer geteilten Tabelle. Wer nur die Auskunft braucht und keine
 * Laufzeit, fragt [DialectCapabilityLookup] statt diesen Port: der Anbieter
 * wird eigenstaendig ueber den `ServiceLoader` gefunden und braucht weder
 * Registry noch Bootstrap.
 *
 * [TypeMapper] is intentionally NOT exposed here — it is an internal
 * implementation detail of [DdlGenerator] (via AbstractDdlGenerator).
 * Consumers who obtain a [DdlGenerator] through [ddlGenerator] get
 * type-mapping implicitly; transfer compatibility is exposed structurally via
 * [transferCompatibility] without leaking the mapper.
 */
interface DatabaseDriver : DialectCapabilityProvider {
    override val dialect: DatabaseDialect

    /**
     * Die Faehigkeiten dieses Ziels.
     *
     * Der Default loest ueber [DialectCapabilityLookup] auf und landet damit
     * beim [DialectCapabilityProvider] desselben Treibermoduls — kein
     * Kreis, denn die Werte liegen in einem eigenen Objekt neben dem
     * Treiber, nicht im Treiber. Die fuenf echten Treiber ueberschreiben
     * trotzdem: der direkte Weg spart den ServiceLoader und sagt am
     * Treiber, wo seine Antwort steht.
     *
     * Der Default ist da, damit eine Test-Attrappe nicht Werte erfinden
     * muss, die sie nicht kennt. Ruft sie die Frage nie, kostet er nichts;
     * ruft sie sie doch, bekommt sie die echte Antwort oder einen
     * benannten Fehlschlag — nie eine ausgedachte.
     */
    override fun capabilities(serverVersion: ServerVersion?): DialectCapabilities =
        DialectCapabilityLookup.forTarget(dialect, serverVersion)

    fun ddlGenerator(): DdlGenerator
    fun dataReader(): DataReader

    /**
     * LN-005: Read-Pfad mit optionalem JDBC-`fetchSize`-Override (Cursor-Prefetch).
     * `null` bedeutet „Dialekt-Default" — identisch zu [dataReader]. Der Wert ist
     * ein Connection-/Cursor-Tuning und wird beim **Bau** des Readers fixiert
     * (immutable pro Instanz → parallel-sicher). Die Default-Implementierung ignoriert
     * den Wert und delegiert an [dataReader]; nur die JDBC-Treiber (PostgreSQL/MySQL/
     * SQLite) werten ihn aus.
     */
    fun dataReader(fetchSize: Int?): DataReader = dataReader()
    fun tableLister(): TableLister
    fun dataWriter(): DataWriter
    fun urlBuilder(): JdbcUrlBuilder
    fun schemaReader(): SchemaReader

    /**
     * Structural transfer-type compatibility for THIS dialect as a transfer
     * **target** — derived from the dialect's own type mapping (see
     * [StructuralTransferTypeCompatibility]), so it covers every tool-own
     * cross-dialect mapping without a hand-maintained case list. The real drivers
     * override this; the default is the conservative identity-only rule so a
     * driver without a structural mapping never silently over-accepts.
     */
    fun transferCompatibility(): TransferTypeCompatibility =
        TransferTypeCompatibility { source, target -> source == target }

    /**
     * The dialect's neutral-type canonicaliser for the migrate post-compare
     * fingerprint: projects a neutral type onto what THIS dialect's reverse
     * reader yields after generate has rendered it (see
     * [NeutralTypeCanonicalizer]). Real drivers override this with the
     * composition of their own forward and reverse type mappings; the default
     * is the conservative identity so a driver without an explicit flattening
     * declaration never folds types away.
     */
    fun typeCanonicalizer(): NeutralTypeCanonicalizer = NeutralTypeCanonicalizer.IDENTITY

    /**
     * Returns the driver's dialect-specific pre-generation validator,
     * or [PreGenerationValidator.NoOp] if the driver carries no
     * mode-specific gates. The runner calls
     * [PreGenerationValidator.validate] after the dialect-agnostic
     * [dev.dmigrate.core.validation.SchemaValidator] passes and before
     * [DdlGenerator.generate] runs.
     */
    fun preGenerationValidator(): PreGenerationValidator = PreGenerationValidator.NoOp
}
