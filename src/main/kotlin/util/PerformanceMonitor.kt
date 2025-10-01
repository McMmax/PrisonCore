package twizzy.tech.util

import kotlinx.coroutines.*
import net.minestom.server.MinecraftServer
import java.lang.management.ManagementFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds

/**
 * Performance monitoring utility for tracking server performance metrics
 */
object PerformanceMonitor {
    private val memoryBean = ManagementFactory.getMemoryMXBean()
    private val runtimeBean = ManagementFactory.getRuntimeMXBean()

    // Performance metrics
    private val tickTimes = mutableListOf<Long>()
    private val eventCounts = ConcurrentHashMap<String, AtomicLong>()
    private val coroutineCounts = ConcurrentHashMap<String, AtomicLong>()
    private var lastGCTime = 0L
    private var lastGCRuns = 0L

    // Performance thresholds
    private const val HIGH_MEMORY_THRESHOLD = 0.8 // 80% memory usage
    private const val HIGH_TPS_THRESHOLD = 50.0 // Below 50ms per tick is good
    private const val MAX_COROUTINES_PER_TYPE = 100

    /**
     * Start performance monitoring
     */
    fun startMonitoring() {
        CoroutineScope(Dispatchers.Default).launch {
            while (true) {
                try {
                    collectMetrics()
                    checkThresholds()
                    delay(30.seconds)
                } catch (e: Exception) {
                    println("[PERFORMANCE] Error in monitoring: ${e.message}")
                }
            }
        }
    }

    /**
     * Track an event occurrence
     */
    fun trackEvent(eventName: String) {
        eventCounts.computeIfAbsent(eventName) { AtomicLong(0) }.incrementAndGet()
    }

    /**
     * Track coroutine creation
     */
    fun trackCoroutine(coroutineType: String) {
        coroutineCounts.computeIfAbsent(coroutineType) { AtomicLong(0) }.incrementAndGet()
    }

    /**
     * Track coroutine completion
     */
    fun untrackCoroutine(coroutineType: String) {
        coroutineCounts[coroutineType]?.decrementAndGet()
    }

    /**
     * Get current performance metrics
     */
    fun getMetrics(): PerformanceMetrics {
        val memory = memoryBean.heapMemoryUsage
        val nonHeapMemory = memoryBean.nonHeapMemoryUsage
        val uptime = runtimeBean.uptime

        return PerformanceMetrics(
            memoryUsed = memory.used,
            memoryMax = memory.max,
            memoryUsagePercent = (memory.used.toDouble() / memory.max.toDouble()) * 100,
            nonHeapMemoryUsed = nonHeapMemory.used,
            averageTickTime = if (tickTimes.isNotEmpty()) tickTimes.average() else 0.0,
            tps = calculateTPS(),
            uptime = uptime,
            activeCoroutines = coroutineCounts.values.sumOf { it.get() },
            eventCounts = eventCounts.mapValues { it.value.get() },
            coroutineCounts = coroutineCounts.mapValues { it.value.get() }
        )
    }

    /**
     * Get performance report as formatted string
     */
    fun getPerformanceReport(): String {
        val metrics = getMetrics()
        return buildString {
            appendLine("=== PERFORMANCE REPORT ===")
            appendLine("Memory: ${formatBytes(metrics.memoryUsed)} / ${formatBytes(metrics.memoryMax)} (${String.format("%.1f", metrics.memoryUsagePercent)}%)")
            appendLine("Non-Heap Memory: ${formatBytes(metrics.nonHeapMemoryUsed)}")
            appendLine("TPS: ${String.format("%.1f", metrics.tps)}")
            appendLine("Average Tick Time: ${String.format("%.2f", metrics.averageTickTime)}ms")
            appendLine("Uptime: ${formatUptime(metrics.uptime)}")
            appendLine("Active Coroutines: ${metrics.activeCoroutines}")

            if (metrics.eventCounts.isNotEmpty()) {
                appendLine("\nEvent Counts (last 30s):")
                metrics.eventCounts.forEach { (event, count) ->
                    appendLine("  $event: $count")
                }
            }

            if (metrics.coroutineCounts.isNotEmpty()) {
                appendLine("\nActive Coroutines by Type:")
                metrics.coroutineCounts.forEach { (type, count) ->
                    appendLine("  $type: $count")
                }
            }

            // Performance warnings
            val warnings = mutableListOf<String>()
            if (metrics.memoryUsagePercent > HIGH_MEMORY_THRESHOLD * 100) {
                warnings.add("HIGH MEMORY USAGE")
            }
            if (metrics.tps < 15.0) {
                warnings.add("LOW TPS")
            }
            if (metrics.activeCoroutines > 1000) {
                warnings.add("HIGH COROUTINE COUNT")
            }

            if (warnings.isNotEmpty()) {
                appendLine("\n⚠️ WARNINGS: ${warnings.joinToString(", ")}")
            }
        }
    }

    private fun collectMetrics() {
        // Clear old event counts
        eventCounts.clear()

        // Track tick time
        val startTime = System.nanoTime()
        // Simulate getting current tick time - in real implementation, hook into server tick
        val tickTime = (System.nanoTime() - startTime) / 1_000_000.0

        synchronized(tickTimes) {
            tickTimes.add(tickTime.toLong())
            if (tickTimes.size > 20) {
                tickTimes.removeAt(0)
            }
        }
    }

    private fun checkThresholds() {
        val metrics = getMetrics()

        if (metrics.memoryUsagePercent > HIGH_MEMORY_THRESHOLD * 100) {
            println("[PERFORMANCE WARNING] High memory usage: ${String.format("%.1f", metrics.memoryUsagePercent)}%")
            suggestGarbageCollection()
        }

        if (metrics.tps < 15.0) {
            println("[PERFORMANCE WARNING] Low TPS: ${String.format("%.1f", metrics.tps)}")
        }

        if (metrics.activeCoroutines > 1000) {
            println("[PERFORMANCE WARNING] High coroutine count: ${metrics.activeCoroutines}")
        }

        // Check for coroutine leaks
        coroutineCounts.forEach { (type, count) ->
            if (count.get() > MAX_COROUTINES_PER_TYPE) {
                println("[PERFORMANCE WARNING] Possible coroutine leak in $type: ${count.get()} active")
            }
        }
    }

    private fun calculateTPS(): Double {
        return if (tickTimes.isNotEmpty()) {
            val avgTickTime = tickTimes.average()
            1000.0 / avgTickTime.coerceAtLeast(1.0)
        } else 20.0
    }

    private fun suggestGarbageCollection() {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastGCTime > 30000) { // Only suggest GC every 30 seconds
            System.gc()
            lastGCTime = currentTime
            println("[PERFORMANCE] Suggested garbage collection")
        }
    }

    private fun formatBytes(bytes: Long): String {
        val units = arrayOf("B", "KB", "MB", "GB")
        var size = bytes.toDouble()
        var unitIndex = 0

        while (size >= 1024 && unitIndex < units.size - 1) {
            size /= 1024
            unitIndex++
        }

        return "${String.format("%.1f", size)} ${units[unitIndex]}"
    }

    private fun formatUptime(millis: Long): String {
        val seconds = millis / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        val days = hours / 24

        return when {
            days > 0 -> "${days}d ${hours % 24}h ${minutes % 60}m"
            hours > 0 -> "${hours}h ${minutes % 60}m"
            minutes > 0 -> "${minutes}m ${seconds % 60}s"
            else -> "${seconds}s"
        }
    }
}

data class PerformanceMetrics(
    val memoryUsed: Long,
    val memoryMax: Long,
    val memoryUsagePercent: Double,
    val nonHeapMemoryUsed: Long,
    val averageTickTime: Double,
    val tps: Double,
    val uptime: Long,
    val activeCoroutines: Long,
    val eventCounts: Map<String, Long>,
    val coroutineCounts: Map<String, Long>
)
