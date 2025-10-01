package twizzy.tech.game.items.pickaxe.enchants

import net.kyori.adventure.key.Key
import net.kyori.adventure.sound.Sound
import net.kyori.adventure.text.Component
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.Player
import net.minestom.server.instance.batch.AbsoluteBlockBatch
import net.minestom.server.instance.block.Block
import twizzy.tech.game.items.pickaxe.Pickaxe
import twizzy.tech.game.items.pickaxe.Enchant.ConfigurableBlockEnchant
import twizzy.tech.util.YamlFactory

/**
 * Drill Token Enchant implementation
 * Removes a vertical layer from the player's mine when activated
 */
class Drill(level: Int) : ConfigurableBlockEnchant(NAME, level, config) {
    companion object {
        const val NAME = "Drill"
        private var config = EnchantConfig(
            maxLevel = 100,
            basePrice = java.math.BigDecimal(10000),
            priceIncrease = 10.0,
            basePercentage = 0.2,
            percentageIncrease = 0.1,
            pickaxeLevel = 1,
            item = "HOPPER"
        )

        fun loadConfig(configMap: Map<String, Any>, basePath: String) {
            config = EnchantConfig.loadFromConfig(
                configMap,
                NAME,
                basePath,
                EnchantConfig(
                    maxLevel = 100,
                    basePrice = java.math.BigDecimal(10000),
                    priceIncrease = 10.0,
                    basePercentage = 0.2,
                    percentageIncrease = 0.1,
                    pickaxeLevel = 1,
                    item = "HOPPER"
                )
            )
        }
    }

    override fun isTokenEnchant(): Boolean = true

    /**
     * Called when Drill enchant is activated.
     * Removes a vertical layer from the player's mine.
     */
    suspend fun onActivate(
        player: Player,
        @Suppress("UNUSED_PARAMETER") pickaxe: Pickaxe,
        regionManager: twizzy.tech.game.RegionManager,
        worlds: twizzy.tech.util.Worlds,
        blockPos: net.minestom.server.coordinate.Point
    ): Long {
        val worldName = worlds.getWorldNameFromInstance(player.instance)
        val regions = regionManager.getRegionsAt(worldName, Pos(blockPos))
        val region = regions.firstOrNull() ?: return 0L

        val minY = Math.min(region.pos1.y(), region.pos2.y()).toInt()
        val maxY = Math.max(region.pos1.y(), region.pos2.y()).toInt()
        val x = blockPos.x().toInt()
        val z = blockPos.z().toInt()

        val instance = player.instance
        val batch = AbsoluteBlockBatch()
        var removed = 0L
        val blockTypeCounts = mutableMapOf<String, Int>()

        // Remove blocks vertically from top to bottom at the broken block's X,Z coordinates
        for (y in maxY downTo minY) {
            val pos = Pos(x.toDouble(), y.toDouble(), z.toDouble())
            val block = instance.getBlock(pos)
            if (!block.isAir) {
                batch.setBlock(x, y, z, Block.AIR)
                removed++
                val blockName = block.name()
                blockTypeCounts[blockName] = blockTypeCounts.getOrDefault(blockName, 0) + 1
            }
        }

        batch.apply(instance, null)

        // Add all blocks broken to the player's backpack
        // Check for backpack booster and apply multiplier if present
        val backpackBooster = pickaxe.activeBoostersOf<twizzy.tech.game.items.pickaxe.boosters.Backpack>().maxByOrNull { it.multiplier }
        for ((blockType, count) in blockTypeCounts) {
            val finalCount = backpackBooster?.applyBooster(count) ?: count
            twizzy.tech.player.PlayerData.addToBlockpack(player.uuid, blockType, finalCount)
        }

        // Send activation message to player
        val activationMessage = YamlFactory.getMessage("commands.pickaxe.enchants.activate", mapOf(
            "enchant" to NAME,
            "blocks" to removed.toString()
        ))
        player.sendMessage(Component.text(activationMessage))

        // Play success sound
        player.playSound(
            Sound.sound(
                Key.key("minecraft:block.anvil.land"),
                Sound.Source.PLAYER,
                1.0f,
                1.0f
            )
        )

        return removed
    }
}
