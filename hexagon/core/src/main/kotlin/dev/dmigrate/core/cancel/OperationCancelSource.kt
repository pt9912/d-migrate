package dev.dmigrate.core.cancel

/**
 * LF-012 / LN-011 / LN-017 / LN-027 *
 * Worker-Adapter MUESSEN die Quelle einer
 * [OperationCancelledException] korrekt setzen, damit der
 * [dev.dmigrate.server.application.job.JobDispatcher] sie auf den
 * richtigen Job-Status mappen kann:
 *
 * - [JOB_CANCEL] -> Job-Status `CANCELLED`. Source ist eine echte
 *   Cancel-Operation, ausgeloest entweder durch ein `job_cancel`-
 *   Tool-Aufruf oder durch das Loopback eines Worker-internen
 *   Cancel-Pfads. CLI-/Runner-`execute(...): Int`-Grenzen mappen
 *   diesen Branch auf Exit-Code `130` (SIGINT-Standard;
 *   LF-012 / LN-011 / LN-017 / LN-027 Z. 1180-1181).
 *
 * - [RUNNER_TIMEOUT] -> Job-Status `FAILED` mit
 *   `error.code = "OPERATION_TIMEOUT"`. Source ist KEINE Cancel-
 *   Operation im fachlichen Sinne, sondern eine erschoepfte Runner-
 *   Budget-Grenze. CLI-Exit-Code-Mapping nicht 130 — LF-012 / LN-011 / LN-017 / LN-027
 *   listet 130 explizit nur fuer "echte Cancel-Operationen".
 */
enum class OperationCancelSource {
    JOB_CANCEL,
    RUNNER_TIMEOUT,
}
