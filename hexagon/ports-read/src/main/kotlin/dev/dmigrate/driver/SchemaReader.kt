package dev.dmigrate.driver

import dev.dmigrate.driver.connection.ConnectionPool

/**
 * Port: reads the schema of a live database into the neutral model.
 *
 * Connection-Ownership follows the established pool pattern: the reader
 * borrows connections from the pool as needed and returns them after use.
 *
 * Implementations live in the driver adapters (Phase C+). This interface
 * defines only the contract.
 */
interface SchemaReader {

    /**
     * Reads the database schema from the given connection pool.
     *
     * @param pool the connection pool to read from
     * @param options controls which object types to include
     * @return a result containing the neutral schema, structured notes,
     *         and any deliberately skipped objects
     */
    fun read(
        pool: ConnectionPool,
        options: SchemaReadOptions = SchemaReadOptions(),
    ): SchemaReadResult
}
