package dev.dmigrate.driver

import dev.dmigrate.core.model.ViewDefinition

/**
 * Classifies views into PRE_DATA and POST_DATA based on function
 * dependencies. Extracted from AbstractDdlGenerator for clarity.
 */
internal object ViewPhaseClassifier {

    /**
     * Returns (preDataViews, postDataViews, diagnosticNotes).
     */
    fun classify(
        views: Map<String, ViewDefinition>,
        functionNames: Set<String>,
        declaredViewDeps: (String, ViewDefinition, Set<String>) -> Set<String>,
        inferredViewDeps: (String, String?, Set<String>) -> Set<String>,
    ): Triple<Map<String, ViewDefinition>, Map<String, ViewDefinition>, List<TransformationNote>> {
        if (views.isEmpty()) return Triple(views, emptyMap(), emptyList())

        val diagnostics = mutableListOf<TransformationNote>()
        val postDataDirect = mutableSetOf<String>()
        val normalizedFuncNames = functionNames.map { it.lowercase() }.toSet()

        for ((name, view) in views) {
            val declaredFuncs = view.dependencies?.functions
            if (!declaredFuncs.isNullOrEmpty()) {
                postDataDirect += name
                continue
            }
            if (view.query != null && normalizedFuncNames.isNotEmpty()) {
                val inferred = inferFunctionDependencies(view.query!!, normalizedFuncNames)
                if (inferred.isNotEmpty()) {
                    postDataDirect += name
                    continue
                }
            }
            if (view.query == null && view.dependencies == null && normalizedFuncNames.isNotEmpty()) {
                postDataDirect += name
                diagnostics += TransformationNote(
                    type = NoteType.ACTION_REQUIRED,
                    code = "E060",
                    objectName = name,
                    message = "View '$name' has no query text and no declared function dependencies. " +
                        "Phase assignment cannot be reliably determined for split mode.",
                    hint = "Add explicit dependencies.functions to the view definition.",
                )
            }
        }

        // Transitive propagation
        val postDataAll = postDataDirect.toMutableSet()
        val viewDeps = views.mapValues { (name, view) ->
            declaredViewDeps(name, view, views.keys) +
                inferredViewDeps(name, view.query, views.keys)
        }
        var changed = true
        while (changed) {
            changed = false
            for ((name, deps) in viewDeps) {
                if (name !in postDataAll && deps.any { it in postDataAll }) {
                    postDataAll += name
                    changed = true
                }
            }
        }

        val preData = linkedMapOf<String, ViewDefinition>()
        val postData = linkedMapOf<String, ViewDefinition>()
        for ((name, view) in views) {
            if (name in postDataAll) postData[name] = view else preData[name] = view
        }
        return Triple(preData, postData, diagnostics)
    }

    fun inferFunctionDependencies(
        query: String,
        normalizedFuncNames: Set<String>,
    ): Set<String> {
        val cleaned = stripSqlCommentsAndLiterals(query)
        val callPattern = Regex("""(?i)\b([A-Za-z_][A-Za-z0-9_]*)\s*\(""")
        return callPattern.findAll(cleaned)
            .map { it.groupValues[1].lowercase() }
            .filter { it in normalizedFuncNames }
            .toSet()
    }

    fun stripSqlCommentsAndLiterals(sql: String): String {
        val sb = StringBuilder(sql.length)
        var i = 0
        while (i < sql.length) {
            when {
                i + 1 < sql.length && sql[i] == '-' && sql[i + 1] == '-' -> {
                    val end = sql.indexOf('\n', i)
                    i = if (end < 0) sql.length else end + 1
                    sb.append(' ')
                }
                i + 1 < sql.length && sql[i] == '/' && sql[i + 1] == '*' -> {
                    val end = sql.indexOf("*/", i + 2)
                    i = if (end < 0) sql.length else end + 2
                    sb.append(' ')
                }
                sql[i] == '\'' -> {
                    i++
                    while (i < sql.length) {
                        if (sql[i] == '\'' && i + 1 < sql.length && sql[i + 1] == '\'') {
                            i += 2
                        } else if (sql[i] == '\'') {
                            i++
                            break
                        } else {
                            i++
                        }
                    }
                    sb.append(' ')
                }
                else -> {
                    sb.append(sql[i])
                    i++
                }
            }
        }
        return sb.toString()
    }
}
