package stomui.views.switching

import eu.koboo.minestom.stomui.api.PlayerView
import eu.koboo.minestom.stomui.api.ViewBuilder
import eu.koboo.minestom.stomui.api.ViewRegistry
import eu.koboo.minestom.stomui.api.ViewType
import eu.koboo.minestom.stomui.api.component.ViewProvider
import eu.koboo.minestom.stomui.api.interaction.Interactions
import eu.koboo.minestom.stomui.api.item.ModifiableItem
import eu.koboo.minestom.stomui.api.item.ViewItem
import net.minestom.server.entity.Player
import net.minestom.server.item.Material

class ViewHistoryExampleProvider(registry: ViewRegistry, private val layerDepth: Int) :
    ViewProvider(registry, ViewBuilder.of(ViewType.SIZE_5_X_9)) {

    private val title = "History: $layerDepth"

    override fun modifyBuilder(viewBuilder: ViewBuilder, player: Player) {
        viewBuilder.title(title)
    }

    override fun onOpen(view: PlayerView, player: Player) {
        player.sendMessage(">> \"$title\" opened ${view.id}")
        for (viewItem in ViewItem.bySlotList(view, view.type.topSlots)) {
            viewItem.material<ModifiableItem>(Material.GRAY_STAINED_GLASS_PANE)
                .displayName<ModifiableItem>(" ")
        }
        val nextLayerDepth = layerDepth + 1
        val previousLayerDepth = layerDepth - 1

        ViewItem.byRowColumn(view, 1, 3)
            .material<ModifiableItem>(Material.BONE)
            .displayName<ModifiableItem>("Back depth $previousLayerDepth")
            .interaction<ModifiableItem>(Interactions.backToLastView())
        ViewItem.byRowColumn(view, 1, 5)
            .material<ModifiableItem>(Material.BONE)
            .displayName<ModifiableItem>("Forward depth $nextLayerDepth")
            .interaction<ModifiableItem>(Interactions.forwardToNextView())
        ViewItem.byRowColumn(view, 3, 4)
            .material<ModifiableItem>(Material.ARROW)
            .displayName<ModifiableItem>("New depth $nextLayerDepth")
            .interaction<ModifiableItem>(Interactions.open(ViewHistoryExampleProvider(registry, nextLayerDepth)))
    }

    override fun onClose(view: PlayerView, player: Player) {
        player.sendMessage(">> \"$title\" closed ${view.id}")
    }

    override fun toString(): String {
        return "MultiLayer{depth=$layerDepth}"
    }
}
