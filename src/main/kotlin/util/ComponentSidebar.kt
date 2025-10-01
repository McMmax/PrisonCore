package twizzy.tech.util

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.Viewable
import net.minestom.server.entity.Player
import net.minestom.server.scoreboard.Sidebar
import net.minestom.server.scoreboard.Sidebar.ScoreboardLine
import java.util.*
import java.util.List
import java.util.function.Consumer


class ComponentSidebar(title: Component) : Viewable {
    private val sidebar: Sidebar
    private val numberFormat: Sidebar.NumberFormat

    companion object {
        private val playerSidebars = mutableMapOf<UUID, ComponentSidebar>()

        fun getSidebar(player: Player): ComponentSidebar? {
            return playerSidebars[player.uuid]
        }

        fun setSidebar(player: Player, sidebar: ComponentSidebar) {
            playerSidebars[player.uuid] = sidebar
        }

        fun removeSidebar(player: Player) {
            playerSidebars.remove(player.uuid)
        }

        /**
         * Updates the scoreboard for a player with their current data
         */
        fun updateScoreboardForPlayer(player: Player) {
            val playerData = twizzy.tech.player.PlayerData.getFromCache(player.uuid)
            if (playerData != null) {
                val sidebar = getSidebar(player)
                sidebar?.updateFromConfig(playerData)
            }
        }

        /**
         * Updates the scoreboard for a player by UUID
         */
        fun updateScoreboardForPlayer(uuid: UUID) {
            val player = net.minestom.server.MinecraftServer.getConnectionManager().getOnlinePlayerByUuid(uuid)
            if (player != null) {
                updateScoreboardForPlayer(player)
            }
        }
    }

    init {
        this.sidebar = Sidebar(title)
        this.numberFormat = Sidebar.NumberFormat.blank()
    }

    override fun addViewer(player: Player): Boolean {
        return this.sidebar.addViewer(player)
    }

    override fun removeViewer(player: Player): Boolean {
        return this.sidebar.removeViewer(player)
    }

    override fun getViewers(): MutableSet<Player> {
        return this.sidebar.getViewers()
    }

    fun update(components: MutableList<Component>) {
        this.sidebar.getLines().forEach(Consumer { line: ScoreboardLine? -> this.sidebar.removeLine(line!!.getId()) })

        for (index in components.indices) {
            val id = UUID.randomUUID().toString().substring(0, 6)
            val line = ScoreboardLine(id, components[index], components.size - index, this.numberFormat)
            this.sidebar.createLine(line)
        }
    }

    fun update(vararg components: Component) {
        this.update(List.of(*components))
    }

    fun updateForPlayer(player: Player, components: List<Component>) {
        val sidebar = getSidebar(player)
        sidebar?.update(components.toMutableList())
    }

    /**
     * Updates the scoreboard using the scoreboard.yml configuration
     */
    fun updateFromConfig(playerData: twizzy.tech.player.PlayerData) {
        try {
            val scoreboardConfig = twizzy.tech.util.YamlFactory.loadConfig(java.io.File("game/scoreboard.yml"))
            val scoreboard = scoreboardConfig["scoreboard"] as? Map<*, *> ?: emptyMap<String, Any>()

            // Get header, body, and footer sections
            val header = (scoreboard["header"] as? List<*>) ?: emptyList<String>()
            val body = (scoreboard["body"] as? List<*>) ?: emptyList<String>()
            val footer = (scoreboard["footer"] as? List<*>) ?: emptyList<String>()

            // Calculate values for placeholders
            val backpackCount = playerData.blockpack.values.sum()

            // Process header lines
            val headerComponents = header.mapNotNull { line ->
                if (line is String) {
                    val processedLine = processPlaceholders(line, playerData, backpackCount)
                    Component.text(processedLine.replace("&", "§"), determineColorFromLine(line))
                } else null
            }

            // Process body lines
            val bodyComponents = body.mapNotNull { line ->
                if (line is String) {
                    val processedLine = processPlaceholders(line, playerData, backpackCount)
                    Component.text(processedLine.replace("&", "§"), determineColorFromLine(line))
                } else null
            }

            // Process footer lines
            val footerComponents = footer.mapNotNull { line ->
                if (line is String) {
                    val processedLine = processPlaceholders(line, playerData, backpackCount)
                    Component.text(processedLine.replace("&", "§"), determineColorFromLine(line))
                } else null
            }

            // Combine all components: header + body + footer
            val allComponents = mutableListOf<Component>()
            allComponents.addAll(headerComponents)
            allComponents.addAll(bodyComponents)
            allComponents.addAll(footerComponents)

            update(allComponents)
        } catch (e: Exception) {
            println("Error loading scoreboard config: ${e.message}")
            // Fallback to hardcoded values
            val backpackCount = playerData.blockpack.values.sum()
            update(
                Component.text("Balance: $${twizzy.tech.util.CompactNotation.format(playerData.balance)}", NamedTextColor.GREEN),
                Component.text("Tokens: ${twizzy.tech.util.CompactNotation.format(playerData.tokens)}", NamedTextColor.GOLD),
                Component.text("Souls: ${twizzy.tech.util.CompactNotation.format(playerData.souls)}", NamedTextColor.LIGHT_PURPLE),
                Component.text("Backpack: ${twizzy.tech.util.CompactNotation.format(backpackCount)}", NamedTextColor.AQUA)
            )
        }
    }

    /**
     * Processes placeholders in a line
     */
    private fun processPlaceholders(line: String, playerData: twizzy.tech.player.PlayerData, backpackCount: Int): String {
        return line
            .replace("%balance%", twizzy.tech.util.CompactNotation.format(playerData.balance))
            .replace("%tokens%", twizzy.tech.util.CompactNotation.format(playerData.tokens))
            .replace("%souls%", twizzy.tech.util.CompactNotation.format(playerData.souls))
            .replace("%backpack%", twizzy.tech.util.CompactNotation.format(backpackCount))
    }

    /**
     * Determines color from the original line based on color codes
     */
    private fun determineColorFromLine(line: String): NamedTextColor {
        return when {
            line.contains("&a") -> NamedTextColor.GREEN
            line.contains("&b") -> NamedTextColor.AQUA
            line.contains("&c") -> NamedTextColor.RED
            line.contains("&d") -> NamedTextColor.LIGHT_PURPLE
            line.contains("&e") -> NamedTextColor.YELLOW
            line.contains("&f") -> NamedTextColor.WHITE
            line.contains("&6") -> NamedTextColor.GOLD
            line.contains("&9") -> NamedTextColor.BLUE
            line.contains("&2") -> NamedTextColor.DARK_GREEN
            line.contains("&4") -> NamedTextColor.DARK_RED
            line.contains("&5") -> NamedTextColor.DARK_PURPLE
            line.contains("&1") -> NamedTextColor.DARK_BLUE
            line.contains("&3") -> NamedTextColor.DARK_AQUA
            line.contains("&0") -> NamedTextColor.BLACK
            line.contains("&7") -> NamedTextColor.GRAY
            line.contains("&8") -> NamedTextColor.DARK_GRAY
            else -> NamedTextColor.WHITE
        }
    }
}