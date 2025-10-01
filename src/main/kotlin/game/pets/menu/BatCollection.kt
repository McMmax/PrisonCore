package twizzy.tech.game.pets.menu

import eu.koboo.minestom.stomui.api.PlayerView
import eu.koboo.minestom.stomui.api.ViewBuilder
import eu.koboo.minestom.stomui.api.ViewRegistry
import eu.koboo.minestom.stomui.api.ViewType
import eu.koboo.minestom.stomui.api.component.ViewProvider
import eu.koboo.minestom.stomui.api.item.ModifiableItem
import eu.koboo.minestom.stomui.api.item.ViewItem
import net.minestom.server.entity.Player
import net.minestom.server.item.Material
import twizzy.tech.game.pets.flying.Bat

class BatCollection(
    registry: ViewRegistry,
    private val bat: Bat
) : ViewProvider(registry, ViewBuilder.of(ViewType.SIZE_3_X_9).title("Bat Soul Collection")) {

    override fun onOpen(view: PlayerView, player: Player) {
        // Bat-specific Soul Collection
        ViewItem.byRowColumn(view, 1, 3)
            .material<ModifiableItem>(Material.SOUL_CAMPFIRE)
            .displayName<ModifiableItem>("<gold><bold>Soul Collection")
            .lore<ModifiableItem>(listOf(
                "<gray>Souls Collected: <yellow>${bat.getSoulsCollected()}",
                "<gray>Mining Streak: <aqua>${bat.getMiningStreak()} <gray>days",
                "<gray>Daily Bonus: ${if (bat.isActiveMiner()) "<green>Active" else "<red>Inactive"}",
                "",
                "<gray>Collection Rate: <white>${getSoulCollectionRate(bat).toDouble()/5} <gray>souls/s",
                "",
                "<yellow>Click to collect souls!"
            ))
            .interaction<ModifiableItem> {
                bat.withdrawSouls(player)
                view.executeRebuild()
            }

        // Interest Information
        ViewItem.byRowColumn(view, 1, 5)
            .material<ModifiableItem>(Material.EMERALD)
            .displayName<ModifiableItem>("<green><bold>Interest Information")
            .lore<ModifiableItem>(listOf(
                "<gray>Interest Rate: <green>${String.format("%.4f", bat.calculateInterestRate())}<gray>%",
                "<gray>Interval: <aqua>Every 5 minutes",
                "<gray>Active Miner: ${if (bat.isActiveMiner()) "<green>Yes" else "<red>No"}",
                "",
                "<gray>Last Played: <yellow>${bat.getLastPlayedFormatted()}"
            ))
    }

    private fun getSoulCollectionRate(bat: Bat): String {
        val baseRate = 2.0
        val rarityMultiplier = when (bat.rarity) {
            twizzy.tech.game.pets.Rarity.COMMON -> 1.0
            twizzy.tech.game.pets.Rarity.UNCOMMON -> 1.5
            twizzy.tech.game.pets.Rarity.RARE -> 2.0
            twizzy.tech.game.pets.Rarity.EPIC -> 2.5
            twizzy.tech.game.pets.Rarity.LEGENDARY -> 3.0
            twizzy.tech.game.pets.Rarity.MYTHIC -> 4.0
            twizzy.tech.game.pets.Rarity.ETERNAL -> 5.0
        }
        return String.format("%.1f", baseRate * rarityMultiplier)
    }
}
