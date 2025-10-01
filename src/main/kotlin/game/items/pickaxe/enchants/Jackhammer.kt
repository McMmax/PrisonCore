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
 * Jackhammer Token Enchant implementation
 * Removes a horizontal layer from the player's mine when activated
 */
class Jackhammer(level: Int) : ConfigurableBlockEnchant(NAME, level, config) {
    companion object {
        const val NAME = "Jackhammer"
        private var config = EnchantConfig(
            maxLevel = 100,
            basePrice = java.math.BigDecimal(10000),
            priceIncrease = 10.0,
            basePercentage = 0.2,
            percentageIncrease = 0.1,
            pickaxeLevel = 1,
            item = "GUNPOWDER"
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
                    item = "GUNPOWDER"
                )
            )
        }
    }

    override fun isTokenEnchant(): Boolean = true // Jackhammer is a token enchant

    /**
     * Called when Jackhammer enchant is activated.
     * Removes a horizontal layer from the player's mine.
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

        val minX = Math.min(region.pos1.x(), region.pos2.x()).toInt()
        val maxX = Math.max(region.pos1.x(), region.pos2.x()).toInt()
        val minZ = Math.min(region.pos1.z(), region.pos2.z()).toInt()
        val maxZ = Math.max(region.pos1.z(), region.pos2.z()).toInt()
        val y = blockPos.y().toInt()

        val instance = player.instance
        val batch = AbsoluteBlockBatch()
        var removed = 0L
        val blockTypeCounts = mutableMapOf<String, Int>()

        for (x in minX..maxX) {
            for (z in minZ..maxZ) {
                val pos = Pos(x.toDouble(), y.toDouble(), z.toDouble())
                val block = instance.getBlock(pos)
                if (!block.isAir) {
                    batch.setBlock(x, y, z, Block.AIR)
                    removed++
                    val blockName = block.name()
                    blockTypeCounts[blockName] = blockTypeCounts.getOrDefault(blockName, 0) + 1
                }
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
                Key.key("minecraft:entity.iron_golem.death"),
                Sound.Source.PLAYER,
                1.0f,
                1.0f
            )
        )

        return removed
    }
}
