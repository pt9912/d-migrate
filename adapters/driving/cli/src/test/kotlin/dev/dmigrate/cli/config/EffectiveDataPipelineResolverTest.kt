package dev.dmigrate.cli.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path

/**
 * Deckt den gebündelten Ein-Ladevorgang-Resolver [resolveEffectiveDataPipeline] ab:
 * chunk_size/fetch_size (Tuning) UND parallelism werden aus **einer** `.d-migrate.yaml`
 * gemergt (Präzedenz CLI > Config > Default), inkl. der `readFetchSize=false`-Variante
 * für den Import.
 */
class EffectiveDataPipelineResolverTest : FunSpec({

    fun tempConfig(content: String): Path {
        val file = Files.createTempFile("dmigrate-datapipeline-", ".yaml")
        Files.writeString(file, content)
        return file
    }

    test("export/transfer: merges chunk_size, fetch_size AND parallelism from one config") {
        val file = tempConfig("pipeline:\n  chunk_size: 5000\n  fetch_size: 250\n  parallelism: auto\n")
        val eff = resolveEffectiveDataPipeline(
            configPath = file, cliChunkSize = null, cliFetchSize = null, cliParallel = null,
            availableProcessors = 4,
        )
        eff.tuning.chunkSize shouldBe 5000
        eff.tuning.fetchSize shouldBe 250
        eff.parallelism.degree shouldBe 4 // auto = min(4 cores, 10 default pool)
        eff.parallelism.fromCli shouldBe false
    }

    test("parallelism: auto clamps against the configured database.pool.max_size, not the default") {
        // pool.max_size 4 < 16 cores → auto must resolve to 4 (the wired pool cap),
        // proving the pool section now feeds the auto-resolution instead of the hard-coded 10.
        val file = tempConfig(
            """
            database:
              pool:
                max_size: 4
            pipeline:
              parallelism: auto
            """.trimIndent()
        )
        val eff = resolveEffectiveDataPipeline(
            configPath = file, cliChunkSize = null, cliFetchSize = null, cliParallel = null,
            availableProcessors = 16,
        )
        eff.pool.maximumPoolSize shouldBe 4
        eff.parallelism.degree shouldBe 4
    }

    test("resolved pool settings are surfaced on the bundle") {
        val file = tempConfig(
            """
            database:
              pool:
                max_size: 7
                connection_timeout_ms: 20000
            """.trimIndent()
        )
        val eff = resolveEffectiveDataPipeline(
            configPath = file, cliChunkSize = null, cliFetchSize = null, cliParallel = null,
        )
        eff.pool.maximumPoolSize shouldBe 7
        eff.pool.connectionTimeoutMs shouldBe 20_000
    }

    test("bad database.pool value surfaces as ConfigResolveException") {
        val file = tempConfig("database:\n  pool:\n    max_size: 0\n")
        shouldThrow<ConfigResolveException> {
            resolveEffectiveDataPipeline(
                configPath = file, cliChunkSize = null, cliFetchSize = null, cliParallel = null,
            )
        }
    }

    test("CLI values win over config for both tuning and parallelism") {
        val file = tempConfig("pipeline:\n  chunk_size: 5000\n  fetch_size: 250\n  parallelism: 2\n")
        val eff = resolveEffectiveDataPipeline(
            configPath = file, cliChunkSize = 999, cliFetchSize = 42, cliParallel = 8,
        )
        eff.tuning.chunkSize shouldBe 999
        eff.tuning.fetchSize shouldBe 42
        eff.parallelism.degree shouldBe 8
        eff.parallelism.fromCli shouldBe true
    }

    test("import (readFetchSize=false): reads chunk_size + parallelism, IGNORES fetch_size") {
        // A malformed fetch_size must NOT fail the import path (import has no JDBC reader).
        val file = tempConfig("pipeline:\n  chunk_size: 5000\n  fetch_size: not-a-number\n  parallelism: 3\n")
        val eff = resolveEffectiveDataPipeline(
            configPath = file, cliChunkSize = null, cliFetchSize = null, cliParallel = null,
            readFetchSize = false,
        )
        eff.tuning.chunkSize shouldBe 5000
        eff.tuning.fetchSize shouldBe null
        eff.parallelism.degree shouldBe 3
    }

    test("no config file: tuning defaults + parallelism 1 (not from CLI)") {
        val eff = resolveEffectiveDataPipeline(
            configPath = null, cliChunkSize = null, cliFetchSize = null, cliParallel = null,
        )
        eff.tuning.chunkSize shouldBe 10_000
        eff.tuning.fetchSize shouldBe null
        eff.parallelism.degree shouldBe 1
        eff.parallelism.fromCli shouldBe false
    }

    test("a bad parallelism value still surfaces as ConfigResolveException (export path)") {
        val file = tempConfig("pipeline:\n  chunk_size: 5000\n  parallelism: banana\n")
        shouldThrow<ConfigResolveException> {
            resolveEffectiveDataPipeline(
                configPath = file, cliChunkSize = null, cliFetchSize = null, cliParallel = null,
            )
        }
    }
})
