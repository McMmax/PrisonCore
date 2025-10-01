package twizzy.tech.listeners

import net.kyori.adventure.text.Component
import net.minestom.server.MinecraftServer
import net.minestom.server.event.player.PlayerChatEvent
import twizzy.tech.player.Profile
import twizzy.tech.player.Ranks
import twizzy.tech.player.PlayerData
import twizzy.tech.util.LettuceCache
import twizzy.tech.util.YamlFactory
import twizzy.tech.game.cells.CellManager
import java.io.File

class ChatHandler(minecraftServer: MinecraftServer) {
    // Use singleton instance of Ranks
    private val ranks = Ranks.getInstance()

    // Cache for config data
    private var gameConfig: Map<String, Any>? = null

    init {
        val lettuce = LettuceCache.getInstance()

        // Load game configuration
        loadGameConfiguration()

        // Initialize chat-related listeners here
        MinecraftServer.getGlobalEventHandler().addListener(PlayerChatEvent::class.java) { event ->
            val player = event.player
            val message = event.rawMessage

            // Cancel the original message so we can send customized ones
            event.isCancelled = true

            // Get player profile
            val profile = Profile.getProfile(player.uuid)

            // Send customized messages to all online players
            sendCustomizedChatMessage(profile, player.uuid, player.username, message)
        }
    }

    /**
     * Loads the game configuration from config.yml
     */
    private fun loadGameConfiguration() {
        try {
            val configFile = File("game/config.yml")
            if (configFile.exists()) {
                gameConfig = YamlFactory.loadConfig(configFile)
            }
        } catch (e: Exception) {
            println("Failed to load game configuration: ${e.message}")
        }
    }

    /**
     * Gets the formatted chat message using the configured format
     */
    private fun getFormattedChatMessage(profile: Profile?, playerUuid: java.util.UUID, playerName: String, message: String): String {
        // Check if player is in a cell
        val cellManager = CellManager.getInstance()
        val playerCell = cellManager.getPlayerCell(playerUuid)

        // Get chat format from config based on whether player is in a cell
        val chatFormat = gameConfig?.let { config ->
            if (playerCell != null) {
                YamlFactory.getValue(config, "chat.cell-global", "%cell% &7[%rank-color%R%rank-number%&7] %player%&7: &f%message%")
            } else {
                YamlFactory.getValue(config, "chat.global", "&7[%rank-color%R%rank-number%&7] %player%&7: &f%message%")
            }
        } ?: if (playerCell != null) {
            "%cell% &7[%rank-color%R%rank-number%&7] %player%&7: &f%message%"
        } else {
            "&7[%rank-color%R%rank-number%&7] %player%&7: &f%message%"
        }

        // Get player data for rank information
        val playerData = PlayerData.getFromCache(playerUuid)
        val rankNumber = playerData?.rank ?: 0
        val rankColor = if (rankNumber > 0) getRankColor(rankNumber) else ""

        // Get donor rank prefix
        val donorRankPrefix = getDonorRankPrefix(profile)

        // Combine donor rank prefix with player name
        val playerWithRank = if (donorRankPrefix.isNotEmpty()) {
            "$donorRankPrefix$playerName"
        } else {
            playerName
        }

        // Replace placeholders
        var formattedMessage = chatFormat
            .replace("%rank-color%", rankColor)
            .replace("%rank-number%", rankNumber.toString())
            .replace("%player%", playerWithRank)
            .replace("%message%", message)

        // Replace cell placeholder if player is in a cell
        if (playerCell != null) {
            formattedMessage = formattedMessage.replace("%cell%", "${playerCell.name}")
        }

        formattedMessage = formattedMessage.trim()

        // Convert & color codes to § format
        return formattedMessage.replace("&", "§")
    }

    /**
     * Gets the prisoner rank prefix with appropriate color (kept for backward compatibility)
     */
    private fun getPrisonRankPrefix(playerUuid: java.util.UUID): String {
        val playerData = PlayerData.getFromCache(playerUuid)
        if (playerData == null || playerData.rank <= 0) {
            return ""
        }

        // Get color for this rank
        val rankColor = getRankColor(playerData.rank)
        return "§7[${rankColor}R${playerData.rank}§7]"
    }

    /**
     * Gets the donor rank prefix
     */
    private fun getDonorRankPrefix(profile: Profile?): String {
        if (profile == null || profile.ranks.isEmpty()) {
            return ""
        }

        var highestRank: Ranks.Rank? = null
        var highestWeight = -1

        // Find the highest weighted rank
        for (rankIdFull in profile.ranks) {
            // Parse the rank ID from the format stored in Redis (e.g., "Owner|snowbunnykilla|1751595201355|null|")
            val rankId = parseRankId(rankIdFull)

            // Try to find the rank in the cache with case-insensitive matching
            val rank = ranks.findRankCaseInsensitive(rankId)

            if (rank != null) {
                if (rank.weight > highestWeight) {
                    highestWeight = rank.weight
                    highestRank = rank
                }
            }
        }

        // Return the prefix or empty string if no rank found
        return highestRank?.prefix?.replace("&", "§") ?: ""
    }

    /**
     * Gets the color code for a specific rank based on configuration
     */
    private fun getRankColor(rank: Int): String {
        val config = gameConfig ?: return "§a" // Default to green if no config
        val colors = YamlFactory.getValue(config, "ranks.colors", emptyMap<Int, String>())

        // Find the highest rank threshold that this rank meets
        var selectedColor = "§7" // Default color
        var highestThreshold = 0

        for ((threshold, color) in colors) {
            val thresholdInt = threshold as? Int ?: (threshold as? String)?.toIntOrNull() ?: 0

            if (rank >= thresholdInt && thresholdInt > highestThreshold) {
                highestThreshold = thresholdInt
                selectedColor = color as String
            }
        }

        return selectedColor
    }

    /**
     * Parses the rank ID from the format stored in Redis
     * Format appears to be: "RankName|username|timestamp|null|"
     * @param fullRankId The full rank ID string from Redis
     * @return The actual rank ID
     */
    private fun parseRankId(fullRankId: String): String {
        // The rank ID is the first part before the first pipe character
        val parts = fullRankId.split("|")
        return parts[0]
    }

    /**
     * Sends customized chat messages to all online players
     * Cell names appear green for players in the same cell, and different color for others
     */
    private fun sendCustomizedChatMessage(profile: Profile?, senderUuid: java.util.UUID, senderName: String, message: String) {
        val cellManager = CellManager.getInstance()
        val senderCell = cellManager.getPlayerCell(senderUuid)

        // Get all online players
        val onlinePlayers = MinecraftServer.getConnectionManager().onlinePlayers

        onlinePlayers.forEach { player ->
            val formattedMessage = getCustomizedFormattedMessage(
                profile,
                senderUuid,
                senderName,
                message,
                senderCell,
                player.uuid
            )
            player.sendMessage(Component.text(formattedMessage))
        }
    }

    /**
     * Gets the formatted chat message customized for the viewing player
     */
    private fun getCustomizedFormattedMessage(
        profile: Profile?,
        senderUuid: java.util.UUID,
        senderName: String,
        message: String,
        senderCell: twizzy.tech.game.cells.Cell?,
        viewerUuid: java.util.UUID
    ): String {
        val cellManager = CellManager.getInstance()
        val viewerCell = cellManager.getPlayerCell(viewerUuid)

        // Determine if viewer is in the same cell as sender
        val inSameCell = senderCell != null && viewerCell != null && senderCell.id == viewerCell.id

        // Get chat format from config based on whether sender is in a cell
        val chatFormat = gameConfig?.let { config ->
            if (senderCell != null) {
                YamlFactory.getValue(config, "chat.cell-global", "&8<&e%cell%&8> &7[%rank-color%R%rank-number%&7] %player%&7: &f%message%")
            } else {
                YamlFactory.getValue(config, "chat.global", "&7[%rank-color%R%rank-number%&7] %player%&7: &f%message%")
            }
        } ?: if (senderCell != null) {
            "&8<&e%cell%&8> &7[%rank-color%R%rank-number%&7] %player%&7: &f%message%"
        } else {
            "&7[%rank-color%R%rank-number%&7] %player%&7: &f%message%"
        }

        // Get player data for rank information
        val playerData = PlayerData.getFromCache(senderUuid)
        val rankNumber = playerData?.rank ?: 0
        val rankColor = if (rankNumber > 0) getRankColor(rankNumber) else ""

        // Get donor rank prefix
        val donorRankPrefix = getDonorRankPrefix(profile)

        // Combine donor rank prefix with player name
        val playerWithRank = if (donorRankPrefix.isNotEmpty()) {
            "$donorRankPrefix$senderName"
        } else {
            senderName
        }

        // Replace placeholders
        var formattedMessage = chatFormat
            .replace("%rank-color%", rankColor)
            .replace("%rank-number%", rankNumber.toString())
            .replace("%player%", playerWithRank)
            .replace("%message%", message)

        // Replace cell placeholder if sender is in a cell
        if (senderCell != null) {
            // Use green color if viewer is in same cell, yellow if different cell
            val cellColor = if (inSameCell) "&a" else "&e"
            formattedMessage = formattedMessage.replace("%cell%", "$cellColor${senderCell.name}")
        }

        formattedMessage = formattedMessage.trim()

        // Convert & color codes to § format
        return formattedMessage.replace("&", "§")
    }
}