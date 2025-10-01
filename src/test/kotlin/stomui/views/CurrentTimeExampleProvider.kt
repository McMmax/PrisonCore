package stomui.views

import eu.koboo.minestom.stomui.api.PlayerView
import eu.koboo.minestom.stomui.api.ViewBuilder
import eu.koboo.minestom.stomui.api.ViewRegistry
import eu.koboo.minestom.stomui.api.ViewType
import eu.koboo.minestom.stomui.api.component.ViewProvider
import eu.koboo.minestom.stomui.api.item.ModifiableItem
import eu.koboo.minestom.stomui.api.item.ViewItem
import net.minestom.server.MinecraftServer
import net.minestom.server.entity.Player
import net.minestom.server.item.Material
import net.minestom.server.timer.TaskSchedule
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class CurrentTimeExampleProvider(registry: ViewRegistry) : ViewProvider(registry, ViewBuilder.of(ViewType.SIZE_5_X_1)) {

    companion object {
        private val FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss")
    }

    private var currentTime: LocalTime = LocalTime.now()

    override fun modifyBuilder(viewBuilder: ViewBuilder, player: Player) {
        viewBuilder.title(getCurrentTime())
    }

    override fun onOpen(view: PlayerView, player: Player) {
        val allSlotsOfTopInventory = view.type.topSlots
        for (viewItem in ViewItem.bySlotList(view, allSlotsOfTopInventory)) {
            viewItem.material<ModifiableItem?>(Material.GRAY_STAINED_GLASS_PANE)
                .name<ModifiableItem?>(" ")
                .cancelClicking<ModifiableItem?>()
        }
        MinecraftServer.getSchedulerManager().scheduleTask(
            Runnable {
                currentTime = LocalTime.now()
                view.setTitle(getCurrentTime())
            },
            TaskSchedule.immediate(),
            TaskSchedule.seconds(1)
        )
    }

    private fun getCurrentTime(): String {
        return "<yellow>${FORMATTER.format(currentTime)}</yellow>"
    }
}
