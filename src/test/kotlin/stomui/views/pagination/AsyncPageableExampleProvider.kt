package stomui.views.pagination

import stomui.views.pagination.renderer.MaterialItemRenderer
import eu.koboo.minestom.stomui.api.PlayerView
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
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class AsyncPageableExampleProvider(registry: ViewRegistry) : ViewProvider(registry, ViewType.SIZE_6_X_9) {

    private val pagination: ViewPagination<Material>

    init {
        addChild(PaginationBorder())
        val pattern = registry.pattern(
            "K#######Z",
            "#1111111#",
            "#1111111#",
            "#1111111#",
            "#1111111#",
            "##<###>##"
        )
        pagination = registry.pageable(
            MaterialItemRenderer(),
            ItemStack.AIR,
            pattern.getMergedSlots('1')
        )
        pagination.setItemSorter(Comparator.comparing(Material::id))
        addChild(pagination)
        addChild(PaginationActionButtons(pagination, pattern))
    }

    override fun modifyBuilder(viewBuilder: ViewBuilder, player: Player) {
        viewBuilder.title("<red>Async pageable")
    }

    override fun onOpen(view: PlayerView, player: Player) {
        // Now we can execute the async loading method
        // and await the completion of the future.
        // In the completion we can add the items to the pagination and
        // update the pagination on the given PlayerView.
        player.sendMessage("Loading async items..")
        val future = loadSomethingAsync()
        future.whenComplete { list, exception ->
            player.sendMessage("Finished future!")

            // Exception caught. Something went wrong. Notify your players about that.
            if (exception != null) {
                player.sendMessage("An error occurred while loading async pagination: ${exception.message}")
                return@whenComplete
            }

            // We got our needed results. Now update the pagination, please!
            if (list != null) {
                player.sendMessage("Found items!")
                pagination.addItems(list)
                pagination.refreshPage(view)
            }
        }
    }

    private fun loadSomethingAsync(): CompletableFuture<Collection<Material>> {
        // Create something running async...
        val future = CompletableFuture<Collection<Material>>()
        CompletableFuture.delayedExecutor(5, TimeUnit.SECONDS).execute {
            future.complete(Material.values().toList())
        }
        return future
    }
}
