package stomui.views.multiview

import eu.koboo.minestom.stomui.api.ViewBuilder
import eu.koboo.minestom.stomui.api.ViewRegistry
import eu.koboo.minestom.stomui.api.ViewType
import eu.koboo.minestom.stomui.api.component.ViewProvider
import net.minestom.server.entity.Player

class TwoPlayerExampleProvider(viewRegistry: ViewRegistry) : ViewProvider(
    viewRegistry,
    ViewBuilder.of(ViewType.SIZE_6_X_9).title("MultiView")
) {

    override fun modifyBuilder(builder: ViewBuilder, player: Player) {
        // Because all methods get a provided PlayerView OR Player,
        // you can always update the PlayerView according to the provided player.
        // That means a component is not forced to only exist within one view.
        builder.title("Welcome, ${player.username} to the component of two players!")

        // Check out CommandComponentForTwoPlayers for an example on how to use that component.
    }
}
