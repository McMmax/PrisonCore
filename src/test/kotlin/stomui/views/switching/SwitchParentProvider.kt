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

abstract class SwitchParentProvider(registry: ViewRegistry, protected val title: String) :
    ViewProvider(registry, ViewType.SIZE_5_X_9) {

    override fun modifyBuilder(viewBuilder: ViewBuilder, player: Player) {
        viewBuilder.title(title)
    }

    override fun onOpen(view: PlayerView, player: Player) {
        player.sendMessage(">> \"$title\" opened ${view.id}")
        for (viewItem in ViewItem.bySlotList(view, view.type.topSlots)) {
            viewItem.material<ModifiableItem>(Material.GRAY_STAINED_GLASS_PANE)
                .displayName<ModifiableItem>(" ")
        }
        val oppositeViewProvider = oppositeViewProvider()
        ViewItem.byRowColumn(view, 1, 4)
            .material<ModifiableItem>(Material.BONE)
            .displayName<ModifiableItem>("Back to \"${oppositeViewProvider.title}\" inventory")
            .interaction<ModifiableItem>(Interactions.backToLastView())
        ViewItem.byRowColumn(view, 3, 4)
            .material<ModifiableItem>(Material.ARROW)
            .displayName<ModifiableItem>("Open new \"${oppositeViewProvider.title}\" inventory")
            .interaction<ModifiableItem>(Interactions.open(oppositeViewProvider))
    }

    override fun onClose(view: PlayerView, player: Player) {
        player.sendMessage(">> \"$title\" closed ${view.id}")
    }

    abstract fun oppositeViewProvider(): SwitchParentProvider
}
