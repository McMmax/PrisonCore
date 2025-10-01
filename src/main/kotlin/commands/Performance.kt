package twizzy.tech.commands

import net.kyori.adventure.text.Component
import net.minestom.server.entity.Player
import revxrsal.commands.annotation.Command
import revxrsal.commands.annotation.Subcommand
import revxrsal.commands.minestom.annotation.CommandPermission
import twizzy.tech.util.PerformanceMonitor

@Command("performance", "perf")
class Performance {

    @Command("performance", "perf")
    @CommandPermission("prison.admin.performance")
    fun performanceCommand(player: Player) {
        val report = PerformanceMonitor.getPerformanceReport()

        // Send each line separately to avoid chat formatting issues
        report.lines().forEach { line ->
            player.sendMessage(Component.text("§7$line"))
        }
    }

    @Subcommand("gc")
    @CommandPermission("prison.admin.performance")
    fun forceGarbageCollection(player: Player) {
        val beforeMemory = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }

        System.gc()

        // Wait a moment for GC to complete
        Thread.sleep(100)

        val afterMemory = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
        val freedMemory = beforeMemory - afterMemory

        player.sendMessage(Component.text("§a✓ Garbage collection completed"))
        player.sendMessage(Component.text("§7Freed: ${formatBytes(freedMemory)}"))
    }

    @Subcommand("monitor")
    @CommandPermission("prison.admin.performance")
    fun toggleMonitoring(player: Player) {
        // This would toggle detailed monitoring if needed
        player.sendMessage(Component.text("§aPerformance monitoring is always active"))
        player.sendMessage(Component.text("§7Use §e/performance §7to view current metrics"))
        player.sendMessage(Component.text("§7Use §e/spark profiler §7for detailed profiling"))
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
}
