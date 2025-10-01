package stomui.views.pagination.components

import eu.koboo.minestom.stomui.api.PlayerView
import eu.koboo.minestom.stomui.api.component.ViewComponent
import eu.koboo.minestom.stomui.api.interaction.Interactions
import eu.koboo.minestom.stomui.api.item.ModifiableItem
import eu.koboo.minestom.stomui.api.item.ViewItem
import eu.koboo.minestom.stomui.api.pagination.ViewPagination
import eu.koboo.minestom.stomui.api.slots.ViewPattern
import net.minestom.server.entity.Player
import net.minestom.server.item.Material

class PaginationActionButtons(
    private val pagination: ViewPagination<Material>,
    private val pattern: ViewPattern
) : ViewComponent() {

    init {
        addChild(pagination)
    }

    override fun onRebuild(view: PlayerView, player: Player) {
        // Sets the "previous" and "next" page buttons, based on the current page.
        // That's why these items are set in "onStateUpdate" instead of "onOpen".

        val nextName = if (pagination.hasNextPage()) {
            "<green>Next (${pagination.nextPage})"
        } else {
            "<red> No next page"
        }
        ViewItem.bySlot(view, pattern.getSlot('>'))
            .material<ModifiableItem?>(Material.ARROW)
            .name<ModifiableItem?>(nextName)
            .interaction<ModifiableItem?>(Interactions.toNextPage<Material?>(pagination))

        val previousName = if (pagination.hasPreviousPage()) {
            "<green>Previous (${pagination.previousPage})"
        } else {
            "<red>No previous page"
        }
        ViewItem.bySlot(view, pattern.getSlot('<'))
            .material<ModifiableItem?>(Material.ARROW)
            .name<ModifiableItem?>(previousName)
            .interaction<ModifiableItem?>(Interactions.toPreviousPage<Material?>(pagination))
    }

    override fun onOpen(view: PlayerView, player: Player) {
        // Add close and refresh buttons to the top inventory.
        // Since these items don't update on rebuilds (e.g. "next page navigation"),
        // we can add them in "onOpen" instead of "onRebuild".


        // Add close and refresh buttons to the top inventory.
        // Since these items don't update on rebuilds (e.g. "next page navigation"),
        // we can add them in "onOpen" instead of "onRebuild".
        ViewItem.bySlot(view, pattern.getSlot('K'))
            .material<ModifiableItem?>(Material.REDSTONE)
            .name<ModifiableItem?>("Close")
            .closeInventoryInteraction<ModifiableItem?>()

        ViewItem.bySlot(view, pattern.getSlot('Z'))
            .material<ModifiableItem?>(Material.NETHER_STAR)
            .name<ModifiableItem?>("Refresh")
            .interaction<ModifiableItem?>(Interactions.updatePagination<Material?>(pagination))
    }
}
