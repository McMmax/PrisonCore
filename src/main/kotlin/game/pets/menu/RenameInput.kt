package twizzy.tech.game.pets.menu

import eu.koboo.minestom.stomui.api.PlayerView
import eu.koboo.minestom.stomui.api.ViewBuilder
import eu.koboo.minestom.stomui.api.ViewRegistry
import eu.koboo.minestom.stomui.api.ViewType
import eu.koboo.minestom.stomui.api.component.ViewProvider
import eu.koboo.minestom.stomui.api.interaction.AnvilInputInteraction
import eu.koboo.minestom.stomui.api.item.ModifiableItem
import eu.koboo.minestom.stomui.api.item.ViewItem
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.minestom.server.entity.EntityCreature
import net.minestom.server.entity.Player
import net.minestom.server.item.Material
import twizzy.tech.game.items.containers.PetPouch
import twizzy.tech.game.pets.Pet
import twizzy.tech.game.pets.flying.Bat
import twizzy.tech.viewRegistry
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.iterator
import kotlin.collections.set

/**
 * Pet Rename Menu using Anvil interface
 */
class PetRenameMenu(
    registry: ViewRegistry,
    private val pet: Pet
) : ViewProvider(registry, ViewBuilder.of(ViewType.ANVIL).title("Rename Pet")), AnvilInputInteraction {

    // Store the current input text from anvil
    private var currentInput: String = ""

    override fun onOpen(view: PlayerView, player: Player) {
        // Input slot (left side of anvil)
        ViewItem.bySlot(view, 0)
            .material<ModifiableItem>(Material.NAME_TAG)
            .displayName<ModifiableItem>("<yellow>New name...")
            .lore<ModifiableItem>(listOf(
                "<gray>Current: <white>${MiniMessage.miniMessage().stripTags(pet.displayName)}",
                "",
                "<green>Type the new name in the text field",
                "<gray>Use MiniMessage formatting for colors",
                "<gray>Example: <red>Fire<gold>Dragon",
                "",
                "<yellow>Click the confirm button to apply!"
            ))
            .cancelClicking<ModifiableItem>()

        // Result slot (right side of anvil)
        ViewItem.bySlot(view, 2)
            .material<ModifiableItem>(Material.NAME_TAG)
            .displayName<ModifiableItem>("<green>Confirm Rename")
            .lore<ModifiableItem>(listOf(
                "<gray>New Name: <white>${if (currentInput.isEmpty()) "None" else currentInput}",
                "",
                "<yellow>Click to confirm!"
            ))
            .interaction<ModifiableItem> {
                if (currentInput.isNotBlank()) {
                    // Open the color selection menu instead of applying the rename directly
                    PetColorMenuProvider(viewRegistry, pet, currentInput).open(player)
                } else {
                    player.sendMessage(Component.text("Please enter a name first."))
                }
            }
    }

    override fun onAnvilInput(view: PlayerView, player: Player, input: String) {
        this.currentInput = input
        view.executeRebuild()
    }
}

/**
 * New menu for selecting a pet's name color
 */
class PetColorMenuProvider(
    registry: ViewRegistry,
    private val pet: Pet,
    private val newName: String
) : ViewProvider(registry, ViewBuilder.of(ViewType.SIZE_3_X_9).title("Choose a Color")) {

    private val colorMap = mapOf(
        Material.WHITE_WOOL to "<white>",
        Material.ORANGE_WOOL to "<gold>",
        Material.MAGENTA_WOOL to "<light_purple>",
        Material.LIGHT_BLUE_WOOL to "<aqua>",
        Material.YELLOW_WOOL to "<yellow>",
        Material.LIME_WOOL to "<green>",
        Material.PINK_WOOL to "<light_purple>",
        Material.GRAY_WOOL to "<gray>",
        Material.LIGHT_GRAY_WOOL to "<gray>",
        Material.CYAN_WOOL to "<dark_aqua>",
        Material.PURPLE_WOOL to "<dark_purple>",
        Material.BLUE_WOOL to "<blue>",
        Material.BROWN_WOOL to "<gold>",
        Material.GREEN_WOOL to "<dark_green>",
        Material.RED_WOOL to "<red>",
        Material.BLACK_WOOL to "<black>"
    )

    override fun onOpen(view: PlayerView, player: Player) {
        // Place colors in the center of the menu
        val startSlot = (27 - 16) / 2
        colorMap.entries.forEachIndexed { index, (material, colorTag) ->
            ViewItem.bySlot(view, startSlot + index)
                .material<ModifiableItem>(material)
                .displayName<ModifiableItem>(MiniMessage.miniMessage().deserialize("$colorTag$newName"))
                .lore<ModifiableItem>(listOf(
                    "Click to select this color"
                ))
                .interaction<ModifiableItem> {
                    val finalName = "$colorTag$newName"
                    applyPetRename(player, pet, finalName)
                    player.closeInventory()
                }
        }
    }

    fun applyPetRename(player: Player, pet: Pet, newDisplayName: String) {
        if (newDisplayName.isBlank() || MiniMessage.miniMessage().stripTags(newDisplayName).length > 32) {
            player.sendMessage(Component.text("<red>Pet name must be between 1-32 characters!"))
            return
        }

        // Use the pet's rename function
        pet.rename(newDisplayName)

        // Update the pet in the player's inventory if they are holding it
        val heldItem = player.itemInMainHand
        if (Pet.fromItemStack(heldItem)?.uid == pet.uid) {
            player.itemInMainHand = pet.toItemStack()
        }

        // Update the pet in the PetPouch
        val pouchItem = player.inventory.getItemStack(7)
        val petPouch = PetPouch.fromItemStack(pouchItem)
        if (petPouch != null) {
            var petUpdated = false
            // Update in contents
            for ((index, item) in petPouch.contents) {
                val pouchPet = Pet.fromItemStack(item)
                if (pouchPet != null && pouchPet.uid == pet.uid) {
                    petPouch.contents[index] = pet.toItemStack()
                    petUpdated = true
                    break
                }
            }
            // Update in selected pets
            for (i in 0 until petPouch.selectedPets.size) {
                val selectedPetItem = petPouch.selectedPets[i]
                if (selectedPetItem != null) {
                    val selectedPet = Pet.fromItemStack(selectedPetItem)
                    if (selectedPet != null && selectedPet.uid == pet.uid) {
                        petPouch.selectedPets[i] = pet.toItemStack()
                        petUpdated = true
                        break
                    }
                }
            }

            if (petUpdated) {
                player.inventory.setItemStack(7, petPouch.toItemStack())
            }
        }

        // Update active pet instance if it's the one being renamed
        val activePet = PetPouch.ACTIVE_PET_INSTANCES[player.uuid]
        if (activePet != null && activePet.uid == pet.uid) {
            activePet.rename(newDisplayName)
            // Find the entity associated with the player and update its name
            player.instance?.entities?.find { entity ->
                val creature = entity as? EntityCreature ?: return@find false
                val playerUuid = creature.getTag(Bat.TAG_PLAYER_UUID)
                val petCandidate = if (playerUuid != null) PetPouch.ACTIVE_PET_INSTANCES[playerUuid] else null
                petCandidate?.uid == pet.uid
            }?.let { petEntity ->
                val rarityColor = pet.rarity.displayColor
                val finalDisplayName = "$newDisplayName $rarityColor[Lvl ${pet.level}]"
                petEntity.customName = MiniMessage.miniMessage().deserialize(finalDisplayName)
                petEntity.isCustomNameVisible = true
            }
        }

        player.sendMessage(Component.text("Your pet has been renamed to: ").append(MiniMessage.miniMessage().deserialize(newDisplayName)))
    }
}