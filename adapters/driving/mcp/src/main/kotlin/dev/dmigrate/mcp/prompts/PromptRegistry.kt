package dev.dmigrate.mcp.prompts

import dev.dmigrate.mcp.protocol.PromptListEntry

/**
 * Phase G § 5.7 + § 6 G.7 — Server-seitige Prompt-Registry.
 *
 * Plan §5.7 Z. 858-864 verbindlich: drei Pflichtprompts.
 * [DefaultPromptRegistry] verdrahtet sie; Tests können einen
 * leeren oder minimalen Registry-Stub nutzen.
 *
 * Pflichten:
 *
 * - [list] liefert alle registrierten Prompts in deterministischer
 *   Reihenfolge (alphabetisch nach `name`), damit Goldens stabil
 *   bleiben.
 * - [find] liefert die volle [PromptDescriptor] oder `null`. Kein
 *   Throw — der `prompts/get`-Handler mappt `null` auf
 *   `RESOURCE_NOT_FOUND`.
 */
fun interface PromptRegistry {

    fun forName(name: String): PromptDescriptor?

    fun list(): List<PromptListEntry> = emptyList()

    /** Standard-Wrapper, damit Caller einfache `forName`-Implementierungen tragen können. */
    fun find(name: String): PromptDescriptor? = forName(name)
}

/**
 * Default-Implementation, die ein festes Set von [PromptDescriptor]-
 * Instanzen führt. Zugriff über stable name-Lookup.
 */
class DefaultPromptRegistry(prompts: List<PromptDescriptor>) : PromptRegistry {

    private val byName: Map<String, PromptDescriptor>

    init {
        val seen = mutableMapOf<String, PromptDescriptor>()
        for (p in prompts) {
            require(seen.put(p.name, p) == null) { "duplicate prompt name: ${p.name}" }
        }
        byName = seen.toSortedMap()
    }

    override fun forName(name: String): PromptDescriptor? = byName[name]

    override fun list(): List<PromptListEntry> = byName.values.map { it.toListEntry() }

    companion object {
        /**
         * Plan §5.7: drei Pflichtprompts. Test-Pfade erlaubterweise
         * leer; Default-Server-Wiring nutzt diese Instanz.
         */
        fun mandatory(): DefaultPromptRegistry = DefaultPromptRegistry(
            prompts = listOf(
                ProcedureAnalysisPrompt.descriptor(),
                ProcedureTransformationPrompt.descriptor(),
                TestdataPlanningPrompt.descriptor(),
            ),
        )
    }
}
