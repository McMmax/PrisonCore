package twizzy.tech.commands

import net.minestom.server.component.DataComponents
import net.minestom.server.entity.Player
import revxrsal.commands.annotation.Command
import revxrsal.commands.annotation.Description
import revxrsal.commands.annotation.Optional
import revxrsal.commands.minestom.annotation.CommandPermission

class Fix {

    @Command("fix")
    @Description("Repair the item in your hand")
    @CommandPermission("command.fix")
    fun fixHand(actor: Player) {
        actor.itemInMainHand = actor.itemInMainHand.with(DataComponents.DAMAGE, 0)
        actor.sendMessage("You have repaired this item.")
    }

    @Command("fixall")
    @Description("Repair all items in your inventory")
    @CommandPermission("command.fixall")
    fun fixAll(actor: Player, @Optional player: Player?) {
        val playerToFix = player ?: actor
        val inventory = playerToFix.inventory
        var repairedCount = 0

        // Fix all items in the main inventory slots
        for (i in 0 until inventory.size) {
            val item = inventory.getItemStack(i)
            if (!item.isAir && item.has(DataComponents.DAMAGE)) {
                val damage = item.get(DataComponents.DAMAGE) ?: 0
                if (damage > 0) {
                    inventory.setItemStack(i, item.with(DataComponents.DAMAGE, 0))
                    repairedCount++
                }
            }
        }

        // Fix items in equipment slots (helmet, chestplate, leggings, boots)
        val helmet = playerToFix.helmet
        if (!helmet.isAir && helmet.has(DataComponents.DAMAGE)) {
            val damage = helmet.get(DataComponents.DAMAGE) ?: 0
            if (damage > 0) {
                playerToFix.helmet = helmet.with(DataComponents.DAMAGE, 0)
                repairedCount++
            }
        }

        val chestplate = playerToFix.chestplate
        if (!chestplate.isAir && chestplate.has(DataComponents.DAMAGE)) {
            val damage = chestplate.get(DataComponents.DAMAGE) ?: 0
            if (damage > 0) {
                playerToFix.chestplate = chestplate.with(DataComponents.DAMAGE, 0)
                repairedCount++
            }
        }

        val leggings = playerToFix.leggings
        if (!leggings.isAir && leggings.has(DataComponents.DAMAGE)) {
            val damage = leggings.get(DataComponents.DAMAGE) ?: 0
            if (damage > 0) {
                playerToFix.leggings = leggings.with(DataComponents.DAMAGE, 0)
                repairedCount++
            }
        }

        val boots = playerToFix.boots
        if (!boots.isAir && boots.has(DataComponents.DAMAGE)) {
            val damage = boots.get(DataComponents.DAMAGE) ?: 0
            if (damage > 0) {
                playerToFix.boots = boots.with(DataComponents.DAMAGE, 0)
                repairedCount++
            }
        }

        // Fix offhand item
        val offhandItem = playerToFix.itemInOffHand
        if (!offhandItem.isAir && offhandItem.has(DataComponents.DAMAGE)) {
            val damage = offhandItem.get(DataComponents.DAMAGE) ?: 0
            if (damage > 0) {
                playerToFix.itemInOffHand = offhandItem.with(DataComponents.DAMAGE, 0)
                repairedCount++
            }
        }

        // Send appropriate messages
        if (player != null) {
            if (repairedCount > 0) {
                actor.sendMessage("You have repaired $repairedCount items for ${player.username}.")
                player.sendMessage("${actor.username} has repaired $repairedCount of your items.")
            } else {
                actor.sendMessage("${player.username} has no items that need repairing.")
            }
        } else {
            if (repairedCount > 0) {
                actor.sendMessage("You have repaired $repairedCount items.")
            } else {
                actor.sendMessage("No items needed repairing.")
            }
        }
    }
}