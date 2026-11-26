package dev.dmigrate.cli

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.WildcardType
import java.net.JarURLConnection
import java.net.URL

/**
 * Native-Image-Wächter (Issue #84 und der `connections/list`-Befund der Native-Matrix).
 *
 * Gson (MCP-Protokoll) und Jackson mit Kotlin-Modul (Server-State-Persistenz) erzeugen ihre DTOs reflektiv.
 * Im Native-Image gelingt das nur für registrierte Klassen; für eine fehlende scheitert genau der eine Aufruf
 * zur Laufzeit (`Unable to create instance of class ...ConnectionsListParams`), während die JVM, der Build und
 * jeder Test grün bleiben. Dieser Test macht aus dem Laufzeitfehler einen Buildfehler: Jede Protokoll- und
 * Wire-Klasse muss in der Native-Konfiguration stehen.
 */
class NativeReflectionRegistrationTest : FunSpec({

    val registered: Set<String> = listOf(
        "META-INF/native-image/dev.dmigrate/cli-manual/reflect-config.json",
        "META-INF/native-image/dev.dmigrate/cli/reachability-metadata.json",
    ).flatMap { path ->
        val text = checkNotNull(Thread.currentThread().contextClassLoader.getResource(path)) { "fehlt: $path" }.readText()
        Regex(""""(?:name|type)"\s*:\s*"([^"]+)"""").findAll(text).map { it.groupValues[1] }.toList()
    }.toSet()

    fun missing(packageName: String, needsRegistration: (Class<*>) -> Boolean): List<String> =
        classesIn(packageName)
            .filter(needsRegistration)
            .map { it.name }
            .filterNot { it in registered }
            .sorted()

    // Eine Kotlin-`data class` erkennt man an `component1`; Enums direkt.
    fun isDto(type: Class<*>): Boolean =
        type.isEnum || type.declaredMethods.any { it.name == "component1" }

    test("das Wächter-Gerüst findet Klassen (sonst prüft der Test nichts)") {
        classesIn("dev.dmigrate.mcp.protocol").shouldNotBeEmpty()
        classesIn("dev.dmigrate.server.persistence.jdbc").filter { it.simpleName.endsWith("Wire") }.shouldNotBeEmpty()
    }

    test("jede Datenklasse des MCP-Protokolls steht in der Native-Konfiguration") {
        missing("dev.dmigrate.mcp.protocol", ::isDto).shouldBeEmpty()
    }

    fun isWire(type: Class<*>): Boolean {
        val name = type.simpleName
        return name.endsWith("Wire") || name == "Companion" && type.enclosingClass?.simpleName?.endsWith("Wire") == true
    }

    test("jede Wire-Klasse der Server-State-Persistenz samt Companion steht in der Native-Konfiguration") {
        missing("dev.dmigrate.server.persistence.jdbc", ::isWire).shouldBeEmpty()
    }

    // Jackson liest ueber die Wire-Klasse hinaus jeden Typ ihrer Felder (`ManagedJob`, `JobError`, …). Nur die
    // Wire-Klassen zu registrieren genuegt nicht: `job_list` scheiterte nativ erst beim Lesen eines gespeicherten
    // Jobs (`INTERNAL_AGENT_ERROR`).
    test("jeder Typ, den Jackson aus einer Wire-Klasse liest, steht in der Native-Konfiguration") {
        val wires = classesIn("dev.dmigrate.server.persistence.jdbc").filter { it.simpleName.endsWith("Wire") }

        reachableFrom(wires)
            .flatMap { type ->
                listOf(type.name) + type.declaredClasses.filter { it.simpleName == "Companion" }.map { it.name }
            }
            .filterNot { it in registered }
            .sorted()
            .joinToString("\n") shouldBe ""
    }
})

/** [roots] und alle `dev.dmigrate`-Typen ihrer Instanzfelder, auch in Generics, rekursiv. */
private fun reachableFrom(roots: List<Class<*>>): Set<Class<*>> {
    val seen = linkedSetOf<Class<*>>()
    val queue = ArrayDeque(roots)
    while (queue.isNotEmpty()) {
        val type = queue.removeFirst()
        if (!seen.add(type)) continue
        type.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) || it.isSynthetic }
            .flatMap { ownTypes(it.genericType) }
            .forEach(queue::add)
    }
    return seen
}

private fun ownTypes(type: Type): List<Class<*>> =
    when (type) {
        is Class<*> -> listOf(if (type.isArray) type.componentType else type)
            .filter { it.name.startsWith("dev.dmigrate.") }
        is ParameterizedType -> (listOf(type.rawType) + type.actualTypeArguments).flatMap(::ownTypes)
        is WildcardType -> type.upperBounds.flatMap(::ownTypes)
        else -> emptyList()
    }

/** Alle Klassen unter [packageName], auch aus Unterpaketen, aus Verzeichnis oder Jar des Classpath. */
private fun classesIn(packageName: String): List<Class<*>> {
    val loader = Thread.currentThread().contextClassLoader
    val path = packageName.replace('.', '/')
    return loader.getResources(path).toList().flatMap { url -> classNames(url, path) }
        .distinct()
        .filterNot { it.endsWith("package-info") || it.endsWith("module-info") }
        .map { Class.forName(it, false, loader) }
}

private fun classNames(url: URL, path: String): List<String> =
    when (url.protocol) {
        "file" -> File(url.toURI()).let { root ->
            val prefix = path.replace('/', '.')
            root.walkTopDown().filter { it.isFile && it.extension == "class" }
                .map { file ->
                    val relative = file.relativeTo(root).path.removeSuffix(".class")
                    "$prefix.${relative.replace(File.separatorChar, '.')}"
                }
                .toList()
        }
        "jar" -> (url.openConnection() as JarURLConnection).jarFile.entries().toList()
            .map { it.name }
            .filter { it.startsWith("$path/") && it.endsWith(".class") }
            .map { it.removeSuffix(".class").replace('/', '.') }
        else -> emptyList()
    }
