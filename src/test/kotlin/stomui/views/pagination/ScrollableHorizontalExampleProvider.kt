package stomui.views.pagination

import stomui.views.pagination.renderer.MaterialItemRenderer
import eu.koboo.minestom.stomui.api.ViewBuilder
import eu.koboo.minestom.stomui.api.ViewRegistry
import eu.koboo.minestom.stomui.api.ViewType
import eu.koboo.minestom.stomui.api.component.ViewProvider
import eu.koboo.minestom.stomui.api.pagination.ViewPagination
import net.minestom.server.entity.Player
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import stomui.views.pagination.components.PaginationActionButtons
import stomui.views.pagination.components.PaginationBorder

class ScrollableHorizontalExampleProvider(registry: ViewRegistry) : ViewProvider(registry, ViewType.SIZE_6_X_9) {

    // Hierarchy of components:
    // - this
    //   - PaginationBorder
    //   - ViewPagination
    //   - PaginationActionButtons
    init {
        addChild(PaginationBorder())
        val pattern = registry.pattern(
            "K#######Z",
            "#SCROLFY#",
            "<SCROLFY>",
            "#SCROLFY#",
            "#SCROLFY#",
            "#########"
        )
        val pagination = registry.scrollable(
            MaterialItemRenderer(),
            ItemStack.AIR,
            pattern.getListOfSlots('S', 'C', 'R', 'O', 'L', 'F', 'Y')
        )
        pagination.setItemSorter(Comparator.comparing(Material::id))
        pagination.addItems(Material.values().toList())
        addChild(pagination)
        addChild(PaginationActionButtons(pagination, pattern))
    }

    override fun modifyBuilder(viewBuilder: ViewBuilder, player: Player) {
        viewBuilder.title("<red>Horizontal scrollable example")
    }
}
