package dev.dmigrate.driver

import java.util.ServiceLoader

/**
 * Die Faehigkeiten, deren Werttypen in `ports-read` wohnen — Sequenzen,
 * Routinen, Trigger und das Raumbezugs-Profil.
 *
 * **Warum ein zweiter Port und nicht [DialectCapabilityProvider].** Der
 * liegt in `ports-common`, dem untersten Ports-Modul, und kann
 * [SequenceCapability], [EffectiveRoutineCapability], [TriggerCapability]
 * und [SpatialProfile] nicht sehen — die stehen hier. Ein Port gehoert dahin,
 * wo seine Sprache wohnt; die Alternative waere gewesen, vier Werttypen nach
 * unten zu schieben, nur damit eine Schnittstelle sie nennen kann.
 *
 * Der Port **erbt** von [DialectCapabilityProvider]: ein Anbieter beantwortet
 * damit alle Fragen seines Dialekts, und `dialect` ist nur einmal deklariert.
 */
interface DialectReadCapabilityProvider : DialectCapabilityProvider {

    /** Was der Dialekt mit benannten Sequenzen kann. */
    fun sequenceCapability(): SequenceCapability

    /**
     * Was der Dialekt mit gespeicherten Routinen kann.
     *
     * [serverVersion] entscheidet dort, wo der neutrale Dialektname nicht
     * reicht: `MYSQL` steht fuer Oracle MySQL **und** MariaDB, und nur eine
     * von beiden kennt `CREATE OR REPLACE FUNCTION`. Ohne Version gilt die
     * konservative Lesart.
     */
    fun routineCapability(serverVersion: ServerVersion?): EffectiveRoutineCapability.Valid

    /** Was der Dialekt mit Triggern kann. */
    fun triggerCapability(): TriggerCapability

    /** Das Raumbezugs-Profil ohne Angabe des Anwenders. */
    fun defaultSpatialProfile(): SpatialProfile

    /** Die Raumbezugs-Profile, die der Dialekt zulaesst. */
    fun allowedSpatialProfiles(): Set<SpatialProfile>
}

/**
 * Loest [DialectReadCapabilityProvider] je Dialekt auf — dieselbe Mechanik
 * und dieselbe Begruendung wie [DialectCapabilityLookup], nur fuer die
 * Faehigkeiten dieses Moduls.
 */
object DialectReadCapabilityLookup {

    private val registered = mutableMapOf<DatabaseDialect, DialectReadCapabilityProvider>()

    private val discovered: Map<DatabaseDialect, DialectReadCapabilityProvider> by lazy {
        ServiceLoader.load(DialectReadCapabilityProvider::class.java).associateBy { it.dialect }
    }

    /** Traegt einen Anbieter ein und ueberschreibt einen gefundenen. */
    @Synchronized
    fun register(provider: DialectReadCapabilityProvider) {
        registered[provider.dialect] = provider
    }

    /** Nimmt einen ueber [register] eingetragenen Anbieter zurueck. */
    @Synchronized
    fun unregister(dialect: DatabaseDialect) {
        registered.remove(dialect)
    }

    @Synchronized
    fun forDialect(dialect: DatabaseDialect): DialectReadCapabilityProvider =
        registered[dialect]
            ?: discovered[dialect]
            ?: throw IllegalStateException(missingProviderMessage(dialect, discovered.keys))

    /**
     * Die Meldung, wenn kein Anbieter da ist — eigene Funktion aus demselben
     * Grund wie bei [DialectCapabilityLookup]: der Zweig ist in einem Modul
     * mit Treibern auf dem Test-Klassenpfad nicht erreichbar, die Meldung
     * schon.
     */
    internal fun missingProviderMessage(
        dialect: DatabaseDialect,
        discovered: Set<DatabaseDialect>,
    ): String =
        "No DialectReadCapabilityProvider for $dialect. Put the driver module for " +
            "$dialect on the classpath, or register a provider via " +
            "DialectReadCapabilityLookup.register(...) in tests. " +
            "Discovered: ${discovered.joinToString().ifEmpty { "none" }}"
}
