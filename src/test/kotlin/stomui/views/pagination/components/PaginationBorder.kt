package stomui.views.pagination.components

import eu.koboo.minestom.stomui.api.PlayerView
import eu.koboo.minestom.stomui.api.component.ViewComponent
import eu.koboo.minestom.stomui.api.item.ModifiableItem
import eu.koboo.minestom.stomui.api.item.ViewItem
import net.minestom.server.entity.Player
import net.minestom.server.item.Material

class PaginationBorder : ViewComponent() {

    override fun onOpen(view: PlayerView, player: Player) {
        // Sets the material to gray stained-glass pane for all border slots in the top inventory.
        val topSlots = view.type.topSlots
        for (viewItem in ViewItem.bySlotList(view, topSlots)) {
            viewItem.material<ModifiableItem?>(Material.GRAY_STAINED_GLASS_PANE)
                .displayName<ModifiableItem?>(" ")
        }
    }
}
