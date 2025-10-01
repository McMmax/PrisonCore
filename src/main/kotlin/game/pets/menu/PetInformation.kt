package twizzy.tech.game.pets.menu

import eu.koboo.minestom.stomui.api.PlayerView
import eu.koboo.minestom.stomui.api.ViewBuilder
import eu.koboo.minestom.stomui.api.ViewRegistry
import eu.koboo.minestom.stomui.api.ViewType
import eu.koboo.minestom.stomui.api.component.ViewProvider
import eu.koboo.minestom.stomui.api.interaction.Interactions
import eu.koboo.minestom.stomui.api.item.ModifiableItem
import eu.koboo.minestom.stomui.api.item.ViewItem
import net.kyori.adventure.text.minimessage.MiniMessage
import net.minestom.server.entity.Player
import net.minestom.server.item.Material
import twizzy.tech.game.items.containers.PetPouch
import twizzy.tech.game.pets.Pet
import twizzy.tech.game.pets.flying.Bat

class PetInformation(
    registry: ViewRegistry,
    private val pet: Pet
) : ViewProvider(registry, ViewBuilder.of(ViewType.SIZE_6_X_9)) {

    private val title = "Pet Information"

    override fun modifyBuilder(viewBuilder: ViewBuilder, player: Player) {
        viewBuilder.title(title)
    }

    override fun onOpen(view: PlayerView, player: Player) {
        // Get the latest pet data from the active pet instance
        val latestPet = getLatestPetData(pet, player)

        // Background - fill all slots with glass panes
        for (slot in 0 until 54) {
            ViewItem.bySlot(view, slot)
                .material<ModifiableItem>(Material.GRAY_STAINED_GLASS_PANE)
                .displayName<ModifiableItem>(" ")
        }

        // Main pet icon (center top)
        ViewItem.byRowColumn(view, 1, 4)
            .material<ModifiableItem>(latestPet.petType.displayItem)
            .displayName<ModifiableItem>(latestPet.displayName)
            .lore<ModifiableItem>(listOf(
                "<gray>Level: <white>${latestPet.level}",
                "<gray>Rarity: ${latestPet.rarity.displayName}",
                "<gray>Experience: <aqua>${latestPet.experience}",
                "",
                "<gray>Hunger: <green>${latestPet.hunger}% <gray>| <gray>Happiness: <light_purple>${latestPet.happiness}%",
                "<gray>State: <yellow>${latestPet.getState()}",
                "",
                "<dark_gray>UID: ${latestPet.uid.toString().take(8)}..."
            ))

        // Leveling Information (left side)
        ViewItem.byRowColumn(view, 2, 2)
            .material<ModifiableItem>(Material.EXPERIENCE_BOTTLE)
            .displayName<ModifiableItem>("<aqua><bold>Leveling Information")
            .lore<ModifiableItem>(listOf(
                "<gray>Current Level: <white>${latestPet.level}",
                "<gray>Current XP: <aqua>${latestPet.experience}",
                "<gray>Next Level XP: <white>${latestPet.getExperienceForLevel(latestPet.level + 1)}",
                "",
                "<gray>Progress: ${getExperienceProgressBar(latestPet)}",
                "",
                if (latestPet.level >= latestPet.getMaxLevel())
                    "<gold><bold>MAX LEVEL REACHED!"
                else
                    "<gray>XP Needed: <yellow>${latestPet.getExperienceForLevel(latestPet.level + 1) - latestPet.experience}"
            ))

        // Rename Pet (right side)
        ViewItem.byRowColumn(view, 2, 6)
            .material<ModifiableItem>(Material.ANVIL)
            .displayName<ModifiableItem>("<gold><bold>Rename Pet")
            .lore<ModifiableItem>(listOf(
                "<gray>Current Name: <white>${MiniMessage.miniMessage().stripTags(latestPet.displayName)}",
                "",
                "<green>Click to rename your pet!",
                "<gray>Use MiniMessage formatting for colors"
            ))
            .interaction<ModifiableItem> { event ->
                PetRenameMenu(registry, latestPet).open(event.player)
            }

        // Bat-specific actions
        if (latestPet is Bat) {
            ViewItem.byRowColumn(view, 3, 4)
                .material<ModifiableItem>(Material.SOUL_LANTERN)
                .displayName<ModifiableItem>("<gold><bold>Bat Actions")
                .lore<ModifiableItem>(listOf(
                    "<gray>Click to view bat-specific actions."
                ))
                .interaction<ModifiableItem> {
                    BatCollection(registry, latestPet).open(player)
                }
        }

        // Close button (bottom center)
        ViewItem.byRowColumn(view, 5, 4)
            .material<ModifiableItem>(Material.BARRIER)
            .displayName<ModifiableItem>("<red><bold>Close")
            .lore<ModifiableItem>(listOf("<gray>Click to close this menu"))
            .interaction<ModifiableItem>(Interactions.close())
    }

    /**
     * Get experience progress bar
     */
    private fun getExperienceProgressBar(pet: Pet): String {
        val currentLevelXP = pet.getExperienceForLevel(pet.level)
        val nextLevelXP = pet.getExperienceForLevel(pet.level + 1)

        if (pet.level >= pet.getMaxLevel()) {
            return "<gold>████████████████████ <yellow>MAX"
        }

        val progress = ((pet.experience - currentLevelXP).toDouble() / (nextLevelXP - currentLevelXP)).coerceIn(0.0, 1.0)
        val totalBars = 20
        val filledBars = (progress * totalBars).toInt()

        val progressBar = buildString {
            repeat(filledBars) { append("█") }
            repeat(totalBars - filledBars) { append("░") }
        }

        return "<green>$progressBar <white>${((progress * 100).toInt())}%"
    }

    /**
     * Gets the latest pet data from the active pet instances
     */
    private fun getLatestPetData(originalPet: Pet, player: Player): Pet {
        val latestPet = PetPouch.ACTIVE_PET_INSTANCES[player.uuid] ?: originalPet
        latestPet.update() // Ensure stats are up-to-date
        return latestPet
    }
}
