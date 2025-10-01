package stomui.views.pagination.renderer

import eu.koboo.minestom.stomui.api.item.PrebuiltItem
import eu.koboo.minestom.stomui.api.pagination.ItemRenderer
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material

class MaterialItemRenderer : ItemRenderer<Material> {

    override fun render(material: Material): PrebuiltItem {
        return PrebuiltItem.of(ItemStack.of(material))
    }
}
