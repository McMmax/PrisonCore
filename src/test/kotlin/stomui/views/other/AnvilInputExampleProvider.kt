package stomui.views.other

import eu.koboo.minestom.stomui.api.PlayerView
import eu.koboo.minestom.stomui.api.ViewBuilder
import eu.koboo.minestom.stomui.api.ViewRegistry
import eu.koboo.minestom.stomui.api.ViewType
import eu.koboo.minestom.stomui.api.component.ViewProvider
import eu.koboo.minestom.stomui.api.interaction.AnvilInputInteraction
import eu.koboo.minestom.stomui.api.item.ModifiableItem
import eu.koboo.minestom.stomui.api.item.ViewItem
import net.minestom.server.entity.Player
import net.minestom.server.item.Material

class AnvilInputExampleProvider(registry: ViewRegistry) :
    ViewProvider(registry, ViewBuilder.of(ViewType.ANVIL).title("Simple anvil input example")),
    AnvilInputInteraction {

    override fun onOpen(view: PlayerView, player: Player) {
        // An inventory of type ANVIL only allows slots to range from 0 to 2.
        // With every text input, the client predicts the result
        // and that results in some items flickering.
        ViewItem.bySlot(view, 0)
            .material<ModifiableItem>(Material.REDSTONE)
            .displayName<ModifiableItem>("Close")
            .closeInventoryInteraction<ModifiableItem>()
    }

    override fun onAnvilInput(playerView: PlayerView, player: Player, input: String) {
        // The player entered or removed a character from the anvil text input field.
        // This method is provided by the AnvilInputInteraction interface
        player.sendMessage("Received input: $input")
    }
}
