package twizzy.tech.util

import com.github.jasync.sql.db.QueryResult
import com.github.jasync.sql.db.pool.ConnectionPool
import com.github.jasync.sql.db.postgresql.PostgreSQLConnectionBuilder
import kotlinx.coroutines.future.await
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.util.concurrent.atomic.AtomicReference
import java.time.Instant
import java.sql.Timestamp
import java.util.*

class JaSync private constructor() {
    // Configuration properties
    private var isInitialized = false
    private var configData: Map<String, Any>? = null

    // Use lazy initialization for the connection pool to ensure it's only created once
    private val connectionPoolRef = AtomicReference<ConnectionPool<*>>()

    // Getter for the connection pool - creates it only if needed
    val connectionPool: ConnectionPool<*>
        get() {
            return connectionPoolRef.get() ?: createConnectionPool().also {
                connectionPoolRef.set(it)
            }
        }

    companion object {
        private var instance: JaSync? = null

        /**
         * Gets or creates a singleton instance of JaSync.
         * @return The JaSync instance.
         */
        fun getInstance(): JaSync {
            if (instance == null) {
                instance = JaSync()
            }
            return instance!!
        }
    }

    /**
     * Initializes the JaSync system
     */
    suspend fun init() {
        try {
            println("[PrisonCore/JaSync] Initializing JaSync system...")

            // Load configuration
            loadConfig()

            // Test the connection pool
            testConnection()

            // Mark as initialized
            isInitialized = true

            println("[PrisonCore/JaSync] JaSync system initialized successfully.")
        } catch (e: Exception) {
            println("[PrisonCore/JaSync] Failed to initialize JaSync system: ${e.message}")
            throw e
        }
    }

    /**
     * Loads the configuration from database.yml
     */
    private fun loadConfig() {
        val configFile = File("database.yml")

        if (!configFile.exists()) {
            throw FileNotFoundException("Configuration file not found. Make sure database.yml is properly set up.")
        }

        val yaml = Yaml()
        configData = yaml.load(FileInputStream(configFile)) as Map<String, Any>
        println("[PrisonCore/JaSync] Configuration loaded successfully.")
    }

    /**
     * Creates the PostgreSQL connection pool using the URL string format
     */
    private fun createConnectionPool(): ConnectionPool<*> {
        val config = configData ?: throw RuntimeException("Configuration not loaded")
        @Suppress("UNCHECKED_CAST")
        val postgresConfig = config["postgres"] as? Map<String, Any>
            ?: throw RuntimeException("PostgreSQL configuration not found in database.yml")

        val host = postgresConfig["host"] as String? ?: "localhost"
        val port = postgresConfig["port"] as Int? ?: 5432
        val database = postgresConfig["database"] as String? ?: "cells"
        val user = postgresConfig["user"] as String? ?: "postgres"
        val password = postgresConfig["password"] as String? ?: ""

        println("[PrisonCore/JaSync] Creating connection pool for PostgreSQL at $host:$port/$database with user: $user")

        return PostgreSQLConnectionBuilder.createConnectionPool(
            "jdbc:postgresql://$host:$port/$database?user=$user&password=$password"
        )
    }

    /**
     * Tests the connection by executing a simple query
     */
    private suspend fun testConnection() {
        try {
            val testResult = connectionPool.sendQuery("SELECT 1").await()
            if (testResult != null) {
                println("[PrisonCore/JaSync] Successfully connected to PostgreSQL database")
            } else {
                throw RuntimeException("Failed to establish valid PostgreSQL connection")
            }
        } catch (e: Exception) {
            println("[PrisonCore/JaSync] Failed to test connection: ${e.message}")
            throw e
        }
    }

    /**
     * Executes a SQL query and returns the result
     * @param query The SQL query to execute
     * @return The query result
     */
    suspend fun executeQuery(query: String): QueryResult {
        return try {
            connectionPool.sendQuery(query).await()
        } catch (e: Exception) {
            println("[PrisonCore/JaSync] Database operation failed: ${e.message}")
            throw e
        }
    }

    /**
     * Method to execute multiple queries in a batch to reduce connection overhead
     * @param queries List of SQL queries to execute
     * @return List of query results
     */
    suspend fun executeBatch(queries: List<String>): List<QueryResult> {
        if (queries.isEmpty()) return emptyList()

        return try {
            queries.map { query ->
                connectionPool.sendQuery(query).await()
            }
        } catch (e: Exception) {
            println("[PrisonCore/JaSync] Batch DB operation failed: ${e.message}")
            throw e
        }
    }

    /**
     * Executes a prepared SQL statement with parameters
     * @param sql The SQL statement to execute
     * @param values The parameter values for the prepared statement
     * @return The query result
     */
    suspend fun executePreparedStatement(sql: String, values: List<Any?> = emptyList()): QueryResult {
        return try {
            connectionPool.sendPreparedStatement(sql, values).await()
        } catch (e: Exception) {
            println("[PrisonCore/JaSync] Failed to execute prepared statement: ${e.message}")
            throw e
        }
    }

    /**
     * Optimized method for upsert operations (INSERT ... ON CONFLICT)
     * @param sql The upsert SQL statement
     * @param values The parameter values
     * @return The query result
     */
    suspend fun executeUpsert(sql: String, values: List<Any?> = emptyList()): QueryResult {
        return try {
            connectionPool.sendPreparedStatement(sql, values).await()
        } catch (e: Exception) {
            println("[PrisonCore/JaSync] Failed to execute upsert: ${e.message}")
            throw e
        }
    }

    /**
     * Executes a query and returns only the first row as a map
     * @param sql The SQL query
     * @param values The parameter values
     * @return Map of column names to values, or null if no results
     */
    suspend fun queryForSingleRow(sql: String, values: List<Any?> = emptyList()): Map<String, Any?>? {
        return try {
            val result = connectionPool.sendPreparedStatement(sql, values).await()
            result.rows.firstOrNull()?.let { row ->
                // Since we can't easily get column names from jasync-sql QueryResult,
                // we'll create a simplified map using numeric indices
                // This method is primarily used for existence checks anyway
                mapOf("result" to (if (row.size > 0) row[0] else null))
            }
        } catch (e: Exception) {
            println("[PrisonCore/JaSync] Failed to query for single row: ${e.message}")
            null // Return null instead of throwing to make it more robust
        }
    }

    /**
     * Executes a count query and returns the count as Long
     * @param sql The count SQL query
     * @param values The parameter values
     * @return The count value
     */
    suspend fun queryForCount(sql: String, values: List<Any?> = emptyList()): Long {
        return try {
            val result = connectionPool.sendPreparedStatement(sql, values).await()
            result.rows.firstOrNull()?.get(0) as? Long ?: 0L
        } catch (e: Exception) {
            println("[PrisonCore/JaSync] Failed to query for count: ${e.message}")
            0L
        }
    }

    /**
     * Helper method to convert database timestamps to Instant
     */
    fun getInstant(row: com.github.jasync.sql.db.RowData, columnName: String): Instant? {
        return when (val value = row[columnName]) {
            is Timestamp -> value.toInstant()
            is java.time.LocalDateTime -> value.atZone(java.time.ZoneOffset.UTC).toInstant()
            is java.time.OffsetDateTime -> value.toInstant()
            is java.time.ZonedDateTime -> value.toInstant()
            else -> null
        }
    }

    /**
     * Helper method to safely get UUID from database result
     */
    fun getUUID(row: com.github.jasync.sql.db.RowData, columnName: String): UUID? {
        return when (val value = row[columnName]) {
            is UUID -> value
            is String -> try { UUID.fromString(value) } catch (_: Exception) { null }
            else -> null
        }
    }

    /**
     * Helper method to safely get String from database result
     */
    fun getString(row: com.github.jasync.sql.db.RowData, columnName: String): String? {
        return row[columnName]?.toString()
    }

    /**
     * Helper method to safely get Boolean from database result
     */
    fun getBoolean(row: com.github.jasync.sql.db.RowData, columnName: String): Boolean? {
        return when (val value = row[columnName]) {
            is Boolean -> value
            is String -> value.lowercase() in listOf("true", "t", "1", "yes", "y")
            is Number -> value.toInt() != 0
            else -> null
        }
    }

    /**
     * Helper method to safely get Int from database result
     */
    fun getInt(row: com.github.jasync.sql.db.RowData, columnName: String): Int? {
        return when (val value = row[columnName]) {
            is Int -> value
            is Number -> value.toInt()
            is String -> value.toIntOrNull()
            else -> null
        }
    }

    /**
     * Helper method to safely get BigDecimal from database result
     */
    fun getBigDecimal(row: com.github.jasync.sql.db.RowData, columnName: String): java.math.BigDecimal? {
        return when (val value = row[columnName]) {
            is java.math.BigDecimal -> value
            is Number -> java.math.BigDecimal.valueOf(value.toDouble())
            is String -> try { java.math.BigDecimal(value) } catch (_: Exception) { null }
            else -> null
        }
    }

    /**
     * Cleanup method to properly disconnect when plugin is disabled
     */
    fun shutdown() {
        try {
            println("[PrisonCore/JaSync] Shutting down JaSync system...")

            // Close PostgreSQL connection pool
            connectionPoolRef.get()?.disconnect()
            connectionPoolRef.set(null)

            isInitialized = false
            configData = null
            println("[PrisonCore/JaSync] JaSync system shut down successfully.")
        } catch (e: Exception) {
            println("[PrisonCore/JaSync] Error during JaSync shutdown: ${e.message}")
        }
    }
}