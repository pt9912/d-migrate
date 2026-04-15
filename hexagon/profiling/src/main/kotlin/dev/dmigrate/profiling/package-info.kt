/**
 * Data profiling domain model, ports, and warning rules for 0.7.5.
 *
 * This module defines profiling-specific types and ports. It does NOT extend
 * [dev.dmigrate.driver.DatabaseDriver] — profiling adapters implement ports
 * defined here and are wired centrally in the CLI bootstrap.
 *
 * Scope for 0.7.5 (deterministic core):
 * - Database/table/column profiling (counts, types, nullability, top-N)
 * - Warning rules (quality findings)
 * - Target-type compatibility checks
 * - JSON/YAML report output
 *
 * NOT in scope for 0.7.5:
 * - Query profiling
 * - Normalization analysis (FD discovery)
 * - LLM/semantic analysis
 */
package dev.dmigrate.profiling
