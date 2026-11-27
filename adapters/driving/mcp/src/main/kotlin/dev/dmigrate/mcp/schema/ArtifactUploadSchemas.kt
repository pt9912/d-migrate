package dev.dmigrate.mcp.schema

import dev.dmigrate.server.core.upload.bundle.BundleFormat

/** `schemaRef` in der Antwort von `artifact_upload` mit `uploadSessionState: COMPLETED`. */
internal const val FINAL_REF_DESCRIPTION: String =
    "Reference to the finished upload: the schema URI for schema_staging_readonly, the artifact URI " +
        "(usable as sourceArtifactRef of data_import_start) for job_input."

/**
 * Eingabe von `artifact_upload_init` fuer beide `uploadIntent`s (spec/mcp-server.md). Welche Felder je Intent
 * verlangt sind, prueft der Handler; `required` nennt nur, was beide verlangen.
 */
internal fun artifactUploadInitInput(): Map<String, Any> = obj(
    "uploadIntent" to enumField("schema_staging_readonly", "job_input"),
    "expectedSizeBytes" to described(
        integerField(),
        "Payload size. Required for schema_staging_readonly (at least 1); job_input also accepts sizeBytes.",
    ),
    "sizeBytes" to described(
        integerField(),
        "job_input only. 0 is allowed for artifacts other than schema.",
    ),
    "checksumSha256" to stringField(),
    "filename" to stringField(),
    "approvalKey" to described(stringField(), "Required for job_input: idempotency and approval key."),
    "approvalToken" to described(stringField(), "job_input only: token of a granted approval."),
    "mimeType" to described(stringField(), "job_input only. Default application/octet-stream; CSV: text/csv."),
    "artifactKind" to described(
        enumField("schema", "ddl", "transform-script", "seed-data", "rules", "generic"),
        "job_input only. Default seed-data.",
    ),
    "targetTable" to described(stringField(), "job_input only: single-file import into this table."),
    "bundleFormat" to described(
        enumField(*BundleFormat.ALL.toTypedArray()),
        "job_input only: required together with tables.",
    ),
    "tables" to described(arrayField("string"), "job_input only: tables of a bundle upload."),
).required("uploadIntent", "checksumSha256")
