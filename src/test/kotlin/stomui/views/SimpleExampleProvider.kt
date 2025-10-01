package stomui.views

import eu.koboo.minestom.stomui.api.PlayerView
import eu.koboo.minestom.stomui.api.ViewBuilder
import eu.koboo.minestom.stomui.api.ViewRegistry
import eu.koboo.minestom.stomui.api.ViewType
import eu.koboo.minestom.stomui.api.component.Priority
import eu.koboo.minestom.stomui.api.component.ViewProvider
import eu.koboo.minestom.stomui.api.item.ModifiableItem
import eu.koboo.minestom.stomui.api.item.ViewItem
import net.minestom.server.entity.Player
import net.minestom.server.item.Material

class SimpleExampleProvider(registry: ViewRegistry) : ViewProvider(registry, ViewBuilder.of(ViewType.SIZE_6_X_9)) {

    // Open by calling:
    // val exampleProvider = SimpleExampleProvider(registry)
    // exampleProvider.open(player)

    override fun modifyBuilder(viewBuilder: ViewBuilder, player: Player) {
        // Modify the ViewBuilder, based on the player who will open the inventory.
        // The instance of the ViewBuilder parameter is a copy of the ViewBuilder from the constructor,
        // because every place gets his own instance of the ViewBuilder.
        viewBuilder.title("Welcome, ${player.username}")
    }

    override fun onOpen(view: PlayerView, player: Player) {
        // Gets executed before the player opens the inventory.

        // Getting all slot numbers from the top inventory of the provided ViewType.
        val allSlotsOfTopInventory = view.type.topSlots

        // Getting all view items for all slots of the top inventory.
        // (Makes it easier to change material and names, using the ViewItem api)
        val allItemsOfTopInventory = ViewItem.bySlotList(view, allSlotsOfTopInventory)

        // Setting all ItemStacks to material gray stained-glass panes with empty names
        // and disable any clicking on them.
        for (viewItem in allItemsOfTopInventory) {
            viewItem.material<ModifiableItem?>(Material.GRAY_STAINED_GLASS_PANE)
                .name<ModifiableItem?>(" ")
                .cancelClicking<ModifiableItem?>()
        };


        // Creating a simple close item in the first slot of the top inventory
        ViewItem.bySlot(view, 0)
            .material<ModifiableItem?>(Material.GRAY_STAINED_GLASS_PANE)
            .name<ModifiableItem?>(" ")
            .closeInventoryInteraction<ModifiableItem?>()
    }

    override fun onClose(view: PlayerView, player: Player) {
        // Gets executed if the player closes or swaps the inventory.
        // Other events that could cause this method to be executed are:
        // - PlayerDisconnectEvent
        // - InventoryCloseEvent
        // - Player gets opened a new PlayerView
        player.sendMessage("You've closed the view")
    }

    override fun onRebuild(view: PlayerView, player: Player) {
        // Gets called everytime view.updateState() is called.
    }

    override fun getPriority(): Priority {
        // Can be used to define the rendering order of children within a parent component
        return Priority.LOW
    }
}
