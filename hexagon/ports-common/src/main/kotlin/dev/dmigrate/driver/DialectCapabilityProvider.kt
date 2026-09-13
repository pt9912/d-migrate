package dev.dmigrate.driver

import java.util.ServiceLoader

/**
 * Was ein Dialekt kann, beantwortet der Dialekt selbst.
 *
 * Vorher stand die Antwort in [DialectCapabilities] als ein
 * `when (dialect)`-Zweig je Dialekt — geteilter Besitz: der sechste
 * Dialekt haette dieselbe Tabelle erweitert, statt sein Wissen
 * mitzubringen. Jetzt liegt sie im Treibermodul, und dieser Port ist der
 * Weg dorthin.
 *
 * **Warum nicht ueber [DatabaseDriver]**, der die Frage ebenfalls
 * beantwortet: dieser Port liegt in `ports-common`, dem untersten
 * Ports-Modul. `DatabaseDriver` und seine Registry liegen in `ports`, das
 * von `ports-read` abhaengt — `ports-read` koennte sie also nicht
 * erreichen, ohne den Modulgraphen umzudrehen. Und die Frage „kann das
 * Ziel das?" braucht keinen Verbindungspool, keinen Leser und keinen
 * Generator; sie an den vollen Treiber zu binden hiesse, fuer eine reine
 * Auskunft eine Laufzeit zu verlangen.
 */
interface DialectCapabilityProvider {

    /** Der Dialekt, fuer den dieser Anbieter spricht. */
    val dialect: DatabaseDialect

    /**
     * Die Faehigkeiten des Ziels, Version eingerechnet.
     *
     * [serverVersion] ist die Auspraegung des Lesepfads; jede
     * versionsabhaengige Faehigkeit faengt die von ihr erwartete mit `as?`
     * ab. `null` heisst „unbekannt" — ein Dateiziel hat keine Version.
     *
     * **Was „unbekannt" bedeutet, entscheidet die Faehigkeit, nicht der
     * Aufrufer.** Die beiden Klassen und ihre Begruendung stehen bei
     * [MeasuredServerVersions].
     */
    fun capabilities(serverVersion: ServerVersion?): DialectCapabilities
}

/**
 * Loest [DialectCapabilityProvider] je Dialekt auf.
 *
 * **Eigenstaendig, nicht ueber `DatabaseDriverRegistry`.** Die Registry
 * wird beim Start gefuellt (`RuntimeBootstrap.loadAll()`) und ist damit an
 * eine Laufzeit gebunden; die Frage „kann das Ziel das?" wird aber auch
 * dort gestellt, wo nichts gebootet wurde — im Vorlauf einer Pruefung, in
 * einem Modultest. Dieser Einstieg laedt deshalb selbst und traeg: wer die
 * Treiber-JARs auf dem Klassenpfad hat, bekommt eine Antwort, ohne etwas
 * initialisiert zu haben.
 *
 * Wo kein Anbieter gefunden wird, **scheitert** die Auskunft benannt,
 * statt einen Vorgabewert zu erfinden. Ein geratener Vorgabewert waere
 * fuer jeden echten Dialekt falsch und faele nirgends auf; ein Fehlschlag
 * nennt den Dialekt und was zu tun ist. Fuer Modultests, die bewusst
 * keinen Adapter auf dem Klassenpfad fuehren, gibt es [register].
 */
object DialectCapabilityLookup {

    private val registered = mutableMapOf<DatabaseDialect, DialectCapabilityProvider>()

    private val discovered: Map<DatabaseDialect, DialectCapabilityProvider> by lazy {
        ServiceLoader.load(DialectCapabilityProvider::class.java)
            .associateBy { it.dialect }
    }

    /**
     * Traegt einen Anbieter ein und ueberschreibt einen gefundenen.
     *
     * Fuer Tests in Modulen, die keinen konkreten Treiber sehen duerfen —
     * `hexagon/application` etwa fuehrt bewusst nur `driver-common` auf dem
     * Test-Klassenpfad, weil alles andere die Schichtregel umdrehte.
     */
    @Synchronized
    fun register(provider: DialectCapabilityProvider) {
        registered[provider.dialect] = provider
    }

    /** Nimmt einen ueber [register] eingetragenen Anbieter zurueck. */
    @Synchronized
    fun unregister(dialect: DatabaseDialect) {
        registered.remove(dialect)
    }

    /** Die Faehigkeiten des Ziels, Version eingerechnet. */
    @Synchronized
    fun forTarget(dialect: DatabaseDialect, serverVersion: ServerVersion?): DialectCapabilities =
        providerFor(dialect).capabilities(serverVersion)

    /**
     * Die Faehigkeiten des Dialekts **ohne** bekannte Zielversion —
     * gleichbedeutend mit `forTarget(dialect, null)`.
     */
    fun forDialect(dialect: DatabaseDialect): DialectCapabilities = forTarget(dialect, null)

    private fun providerFor(dialect: DatabaseDialect): DialectCapabilityProvider =
        registered[dialect]
            ?: discovered[dialect]
            ?: throw IllegalStateException(missingProviderMessage(dialect, discovered.keys))

    /**
     * Die Meldung, wenn kein Anbieter da ist.
     *
     * Eigene Funktion, weil sie das eigentliche Produkt dieses Zweigs ist: der
     * Zweig selbst ist in einem Modul, das alle fuenf Treiber auf dem
     * Test-Klassenpfad fuehrt, nicht erreichbar — die Meldung schon.
     */
    internal fun missingProviderMessage(
        dialect: DatabaseDialect,
        discovered: Set<DatabaseDialect>,
    ): String =
        "No DialectCapabilityProvider for $dialect. Put the driver module for " +
            "$dialect on the classpath, or register a provider via " +
            "DialectCapabilityLookup.register(...) in tests. " +
            "Discovered: ${discovered.joinToString().ifEmpty { "none" }}"
}
