package twizzy.tech.commands

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.HoverEvent
import net.minestom.server.entity.Player
import net.minestom.server.utils.mojang.MojangUtils
import revxrsal.commands.annotation.Command
import revxrsal.commands.annotation.Description
import revxrsal.commands.annotation.Subcommand
import revxrsal.commands.annotation.Suggest
import revxrsal.commands.annotation.Optional
import twizzy.tech.player.PlayerData
import twizzy.tech.util.CompactNotation
import twizzy.tech.util.DurationParser
import twizzy.tech.util.YamlFactory
import java.text.NumberFormat
import java.util.*

@Command("leaderboard", "lb")
class Leaderboard {

    @Subcommand("<type> <page>")
    @Description("Show the leaderboard for a given type and page")
    suspend fun printLeaderboard(
        actor: Player,
        @Optional @Suggest("Balance", "Souls", "Tokens", "Blocks", "Rank") type: String,
        @Optional page: Int?
    ) {
        if (type.isNullOrBlank()) {
            val message = YamlFactory.getMessage("commands.leaderboard.usage")
            actor.sendMessage(Component.text(message))
            return
        }
        val typeEnum = try {
            PlayerData.Companion.LeaderboardType.valueOf(type.trim().lowercase().replaceFirstChar { it.uppercase() })
        } catch (e: Exception) {
            actor.sendMessage("Unknown leaderboard type '$type'. Valid types: Balance, Souls, Tokens, Blocks, Rank")
            return
        }
        val pageNum = (page ?: 1).coerceAtLeast(1)
        val maxPages = twizzy.tech.player.PlayerData.Companion.getLeaderboardMaxPages()
        if (pageNum > maxPages) {
            val msg = twizzy.tech.util.YamlFactory.getMessage("commands.leaderboard.no_data", mapOf("max" to maxPages))
            actor.sendMessage(Component.text(msg + " Max pages: $maxPages"))
            return
        }
        val leaderboard = PlayerData.getLeaderboard(typeEnum, pageNum)
        val perPage = twizzy.tech.player.PlayerData.Companion.getLeaderboardPerPage()
        if (leaderboard.isEmpty()) {
            val msg = twizzy.tech.util.YamlFactory.getMessage("commands.leaderboard.no_data", mapOf("page" to pageNum))
            actor.sendMessage(Component.text(msg))
            return
        }
        val numberFormat = NumberFormat.getInstance(Locale.US)
        val compactFormat = CompactNotation
        val nextRefreshMs = PlayerData.getNextLeaderboardRefresh(typeEnum)
        val nextRefreshStr = DurationParser.format((nextRefreshMs / 1000).coerceAtLeast(0), showAllUnits = true)
        val entryTemplate = twizzy.tech.util.YamlFactory.getMessage("commands.leaderboard.entry")
        val colors = twizzy.tech.util.YamlFactory.getMessageList("commands.leaderboard.colors")
        val entryLines = mutableListOf<String>()
        for (i in 0 until leaderboard.size) {
            val (uuid, value) = leaderboard[i]
            val playerName = MojangUtils.getUsername(uuid)
            val compact = compactFormat.format(value)
            val full = numberFormat.format(value)
            val rank = (i + 1) + (pageNum - 1) * perPage
            val color = if (pageNum == 1) {
                if (i < colors.size - 1) colors[i] else colors.lastOrNull() ?: "&f"
            } else {
                colors.lastOrNull() ?: "&f"
            }
            val placeholders = mapOf(
                "rank" to rank,
                "name" to playerName,
                "compact" to compact,
                "full" to full,
                "color" to color
            )
            entryLines.add(twizzy.tech.util.YamlFactory.formatMessage(entryTemplate, placeholders))
        }
        val entryBlock = entryLines.joinToString("\n")
        val listTemplate = twizzy.tech.util.YamlFactory.getMessageList("commands.leaderboard.list",
            mapOf(
                "type" to typeEnum.name.replace("_", " ").lowercase().replaceFirstChar { it.uppercase() },
                "page" to pageNum,
                "refresh" to nextRefreshStr,
                "entry" to entryBlock
            )
        )
        val message = listTemplate.joinToString("\n")
        actor.sendMessage(Component.text(message))
    }

    @Subcommand("refresh <type>")
    suspend fun refreshLeaderboard(actor: Player, @Suggest("Balance", "Souls", "Tokens", "Blocks")type: String) {
        val typeEnum = try {
            PlayerData.Companion.LeaderboardType.valueOf(type.lowercase().replaceFirstChar { it.uppercase() })
        } catch (e: Exception) {
            actor.sendMessage("Unknown leaderboard type '$type'. Valid types: Balance, Souls, Tokens, Blocks")
            return
        }
        PlayerData.forceRefreshLeaderboard(typeEnum)
        actor.sendMessage("Leaderboard of type '$typeEnum' has been force-refreshed.")
    }
}