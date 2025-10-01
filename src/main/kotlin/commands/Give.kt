package twizzy.tech.commands

import net.kyori.adventure.text.Component
import net.minestom.server.component.DataComponents
import net.minestom.server.entity.Player
import net.minestom.server.item.ItemStack
import revxrsal.commands.annotation.Command
import revxrsal.commands.annotation.Description
import revxrsal.commands.annotation.Optional
import revxrsal.commands.minestom.annotation.CommandPermission
import twizzy.tech.game.items.Item
import twizzy.tech.game.items.pickaxe.boosters.Experience
import twizzy.tech.game.pets.flying.Bat
import java.util.*

@CommandPermission("command.give")
class Give {

    companion object {
        // Registry of custom items that can be given
        private val customItems = mapOf<String, () -> ItemStack>(
            "exp_booster_2x_1h" to { Experience.doubleXPItem(3600, "Command") },
            "exp_booster_3x_30m" to { Experience.tripleXPItem(1800, "Command") },
            "exp_booster_5x_15m" to { Experience.fiveTimesXPItem(900, "Command") },
            "exp_booster_10x_5m" to { Experience.createItem(10.0, 300, "Command") },
            "exp_booster_2x_permanent" to { Experience.permanentDoubleXPItem("Command") },
        )
    }

    @Command("give")
    @Description("Give an item to a player")
    fun giveCommand(actor: Player, target: Player, itemName: String, @Optional amount: Int = 1) {
        if (amount <= 0) {
            actor.sendMessage("§cAmount must be greater than 0")
            return
        }

        // Find the custom item by name (case-insensitive)
        val itemFactory = customItems[itemName.lowercase()]

        if (itemFactory == null) {
            actor.sendMessage("§cUnknown custom item: §f$itemName")
            actor.sendMessage("§7Available items: §f${customItems.keys.joinToString(", ")}")
            return
        }

        // Give the custom item to the player
        repeat(amount) {
            val itemStack = itemFactory()
            target.inventory.addItemStack(itemStack)
        }

        // Send confirmation messages
        val sampleItem = itemFactory()
        val displayName = sampleItem.get(DataComponents.CUSTOM_NAME)?.let {
            net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(it)
        } ?: itemName

        actor.sendMessage("§aGave §f$amount §aof §f$displayName §ato §f${target.username}")
        target.sendMessage("§aYou received §f$amount §aof §f$displayName")
    }

    @Command("iteminfo")
    @Description("Inspect the item in your hand")
    fun itemInfoCommand(player: Player) {
        val itemInHand = player.itemInMainHand
        if (itemInHand.isAir) {
            player.sendMessage("§cYou must hold an item to get information about it")
            return
        }

        // First try to parse as an Experience booster
        val experienceBooster = Experience.fromItemStack(itemInHand)
        if (experienceBooster != null) {
            player.sendMessage("§6--- Experience Booster Info ---")
            player.sendMessage("§7Type: §fExperience Booster")
            player.sendMessage("§7Multiplier: §f${experienceBooster.multiplier}x")
            player.sendMessage("§7Duration: §f${experienceBooster.getRemainingDuration()?.let { "${it}s" } ?: "Permanent"}")
            player.sendMessage("§7Source: §f${itemInHand.getTag(net.minestom.server.tag.Tag.String("booster_source")) ?: "Unknown"}")
            player.sendMessage("§7Active: §f${if (experienceBooster.isActive()) "Yes" else "No"}")
            player.sendMessage("§7UID: §f${itemInHand.getTag(net.minestom.server.tag.Tag.String("item_uid")) ?: "None"}")
            return
        }

        // Fall back to regular Item
        val item = Item.fromItemStack(itemInHand)
        if (item == null) {
            player.sendMessage("§cThis is not a custom item")
            return
        }

        player.sendMessage("§6--- Item Info ---")
        player.sendMessage("§7Name: §f${item.name}")
        player.sendMessage("§7Display Name: §f${item.displayName}")
        player.sendMessage("§7Material: §f${item.displayItem}")
        player.sendMessage("§7Durability: §f${itemInHand.get(DataComponents.DAMAGE)}/${item.durability}")
        player.sendMessage("§7UID: §f${item.uid}")
    }

    @Command("givebooster")
    @Description("Give a custom experience booster to a player")
    fun giveBoosterCommand(
        actor: Player,
        target: Player,
        multiplier: Double,
        @Optional durationSeconds: Long? = null,
        @Optional amount: Int = 1
    ) {
        if (amount <= 0) {
            actor.sendMessage("§cAmount must be greater than 0")
            return
        }

        if (multiplier <= 0) {
            actor.sendMessage("§cMultiplier must be greater than 0")
            return
        }

        // Create the booster items
        repeat(amount) {
            val itemStack = if (durationSeconds != null && durationSeconds > 0) {
                Experience.createItem(multiplier, durationSeconds, "Admin Command")
            } else {
                Experience.createPermanentItem(multiplier, "Admin Command")
            }
            target.inventory.addItemStack(itemStack)
        }

        // Send confirmation messages
        val durationType = if (durationSeconds != null && durationSeconds > 0) "${durationSeconds}s" else "Permanent"
        actor.sendMessage("§aGave §f$amount §aof §f${multiplier}x Experience Booster ($durationType) §ato §f${target.username}")
        target.sendMessage("§aYou received §f$amount §aof §f${multiplier}x Experience Booster ($durationType)")
    }
}
