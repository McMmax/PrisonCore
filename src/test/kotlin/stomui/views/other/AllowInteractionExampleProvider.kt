package stomui.views.other

import eu.koboo.minestom.stomui.api.PlayerView
import eu.koboo.minestom.stomui.api.ViewBuilder
import eu.koboo.minestom.stomui.api.ViewRegistry
import eu.koboo.minestom.stomui.api.ViewType
import eu.koboo.minestom.stomui.api.component.ViewProvider
import eu.koboo.minestom.stomui.api.flags.Flags
import eu.koboo.minestom.stomui.api.item.ModifiableItem
import eu.koboo.minestom.stomui.api.item.ViewItem
import eu.koboo.minestom.stomui.api.slots.ViewPattern
import net.minestom.server.entity.Player
import net.minestom.server.item.Material

class AllowInteractionExampleProvider(registry: ViewRegistry) :
    ViewProvider(registry, ViewBuilder.of(ViewType.SIZE_6_X_9)
        .title("Allow interactions example")
        // These Flags make it easier to allow overall interactions
        // instead of iterating over every slot
        // and marking it interactable / allowing their interactions.
        .withFlags(
            Flags.ALLOW_BOTTOM_INTERACTION,
            Flags.ALLOW_ITEM_DRAGGING
        )) {

    private val pattern: ViewPattern

    init {
        // This is the pattern, we want to fill with logic and items.
        // '#' = Placeholder item
        // ' ' = Interactable slot (Items from player can be placed here.)
        pattern = registry.pattern(
            "#########",
            "#       #",
            "#       #",
            "#       #",
            "#       #",
            "#########"
        )
    }

    override fun onOpen(view: PlayerView, player: Player) {

        // Getting all top border slots by the predefined pattern.
        val topBorderSlots = pattern.getSlots('#')


        // Setting all placeholder items, based on the slotList from above.
        for (viewItem in ViewItem.bySlotList(view, topBorderSlots)) {
            viewItem.material<ModifiableItem?>(Material.GRAY_STAINED_GLASS_PANE)
                .name<ModifiableItem?>(" ")
        }


        // A simple close item, at the first slot of the top inventory.
        ViewItem.bySlot(view, 0)
            .material<ModifiableItem?>(Material.REDSTONE)
            .displayName<ModifiableItem?>("Close")
            .closeInventoryInteraction<ModifiableItem?>()


        // Allowing interactions/placing on all slots of the character ' ' (a whitespace).
        val slotList = pattern.getSlots(' ')
        for (viewItem in ViewItem.bySlotList(view, slotList)) {
            viewItem.allowClicking<ModifiableItem?>()
        }
    }
}
