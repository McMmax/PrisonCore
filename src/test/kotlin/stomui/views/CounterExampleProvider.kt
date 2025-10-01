package stomui.views

import eu.koboo.minestom.stomui.api.PlayerView
import eu.koboo.minestom.stomui.api.ViewBuilder
import eu.koboo.minestom.stomui.api.ViewRegistry
import eu.koboo.minestom.stomui.api.ViewType
import eu.koboo.minestom.stomui.api.component.ViewProvider
import eu.koboo.minestom.stomui.api.interaction.Interaction
import eu.koboo.minestom.stomui.api.interaction.ViewAction
import eu.koboo.minestom.stomui.api.item.ModifiableItem
import eu.koboo.minestom.stomui.api.item.ViewItem
import net.minestom.server.entity.Player
import net.minestom.server.item.Material

class CounterExampleProvider(registry: ViewRegistry) : ViewProvider(
    registry,
    ViewBuilder.of(ViewType.SIZE_5_X_1).title("Counter example")
) {

    private var count = 0

    override fun onOpen(view: PlayerView, player: Player) {
        val allSlotsOfTopInventory = view.type.topSlots
        for (viewItem in ViewItem.bySlotList(view, allSlotsOfTopInventory)) {
            viewItem.material<ModifiableItem?>(Material.GRAY_STAINED_GLASS_PANE)
                .name<ModifiableItem?>(" ")
                .cancelClicking<ModifiableItem?>()
        }
    }

    override fun onRebuild(view: PlayerView, player: Player) {
        ViewItem.bySlot(view, 2)
            .material<ModifiableItem?>(Material.GREEN_DYE)
            .displayName<ModifiableItem?>("Count: " + count)
            .lore<ModifiableItem?>(
                "Click to increment count.",
                "Current count is: " + count
            )
            .interaction<ModifiableItem?>(Interaction { action: ViewAction? ->
                count++
                view.executeRebuild()
            })
    }
}
