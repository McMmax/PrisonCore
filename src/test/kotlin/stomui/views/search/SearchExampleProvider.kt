package stomui.views.search

import stomui.views.pagination.renderer.MaterialItemRenderer
import eu.koboo.minestom.stomui.api.PlayerView
import eu.koboo.minestom.stomui.api.ViewBuilder
import eu.koboo.minestom.stomui.api.ViewRegistry
import eu.koboo.minestom.stomui.api.ViewType
import eu.koboo.minestom.stomui.api.component.ViewProvider
import eu.koboo.minestom.stomui.api.interaction.AnvilInputInteraction
import eu.koboo.minestom.stomui.api.interaction.Interactions
import eu.koboo.minestom.stomui.api.item.ModifiableItem
import eu.koboo.minestom.stomui.api.item.ViewItem
import eu.koboo.minestom.stomui.api.pagination.ViewPagination
import eu.koboo.minestom.stomui.api.slots.ViewPattern
import net.minestom.server.entity.Player
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material

class SearchExampleProvider(registry: ViewRegistry) :
    ViewProvider(registry, ViewBuilder.of(VIEW_TYPE).title("Search:")),
    AnvilInputInteraction {

    companion object {
        private val ALL_MATERIALS = Material.values().toList()
        private val VIEW_TYPE = ViewType.ANVIL
    }

    private val pattern: ViewPattern
    private val filter: SearchItemFilter
    private val pagination: ViewPagination<Material>

    init {
        pattern = registry.pattern(
            "#########",
            "#########",
            "#########",
            "A <   >  "
        )
        // This method allows offsetting the slots of the top inventory,
        // so the pattern applies to the bottom inventory.
        // You could also offset the pattern by a specific slot amount,
        // but this is more convenient and fail-safe.
        pattern.offsetTopInventory(VIEW_TYPE)

        pagination = registry.pageable(
            MaterialItemRenderer(),
            ItemStack.AIR,
            pattern.getSlots('#')
        )
        pagination.setItemSorter(Comparator.comparing(Material::id))
        filter = SearchItemFilter()
        pagination.setItemFilter(filter)
        pagination.addItems(ALL_MATERIALS)
        addChild(pagination)
    }

    override fun onOpen(view: PlayerView, player: Player) {
        view.bottomInventory.clear()

        ViewItem.bySlot(view, 0)
            .material<ModifiableItem>(Material.PAPER)
            .name<ModifiableItem>("")
            .cancelClicking<ModifiableItem>()

        for (slot in pattern.getSlots(' ')) {
            ViewItem.bySlot(view, slot)
                .material<ModifiableItem>(Material.GRAY_STAINED_GLASS_PANE)
                .name<ModifiableItem>(" ")
                .cancelClicking<ModifiableItem>()
        }
    }

    override fun onClose(view: PlayerView, player: Player) {
        view.bottomInventory.clear()
    }

    override fun onRebuild(view: PlayerView, player: Player) {
        val nextName = if (pagination.hasNextPage()) {
            "<green>Next (${pagination.nextPage})"
        } else {
            "<red> No next page"
        }
        ViewItem.bySlot(view, pattern.getSlot('>'))
            .material<ModifiableItem>(Material.ARROW)
            .name<ModifiableItem>(nextName)
            .glint<ModifiableItem>(pagination.hasNextPage())
            .interaction<ModifiableItem>(Interactions.toNextPage(pagination))

        val previousName = if (pagination.hasPreviousPage()) {
            "<green>Previous (${pagination.previousPage})"
        } else {
            "<red>No previous page"
        }
        ViewItem.bySlot(view, pattern.getSlot('<'))
            .material<ModifiableItem>(Material.ARROW)
            .name<ModifiableItem>(previousName)
            .glint<ModifiableItem>(pagination.hasPreviousPage())
            .interaction<ModifiableItem>(Interactions.toPreviousPage(pagination))

        ViewItem.bySlot(view, pattern.getSlot('A'))
            .material<ModifiableItem>(Material.GOLD_NUGGET)
            .name<ModifiableItem>("<gray>Amount: <gold>${pagination.totalItems}")
            .cancelClicking<ModifiableItem>()
    }

    override fun onAnvilInput(playerView: PlayerView, player: Player, searchInput: String) {
        player.sendMessage("Received input: $searchInput")
        filter.setTextInput(searchInput)
        pagination.refreshPage(playerView)
    }
}
