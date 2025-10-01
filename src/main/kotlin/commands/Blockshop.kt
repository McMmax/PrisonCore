package twizzy.tech.commands

import eu.koboo.minestom.stomui.api.PlayerView
import eu.koboo.minestom.stomui.api.ViewBuilder
import eu.koboo.minestom.stomui.api.ViewRegistry
import eu.koboo.minestom.stomui.api.ViewType
import eu.koboo.minestom.stomui.api.component.ViewProvider
import eu.koboo.minestom.stomui.api.interaction.AnvilInputInteraction
import eu.koboo.minestom.stomui.api.interaction.Interaction
import eu.koboo.minestom.stomui.api.interaction.Interactions
import eu.koboo.minestom.stomui.api.item.ModifiableItem
import eu.koboo.minestom.stomui.api.item.PrebuiltItem
import eu.koboo.minestom.stomui.api.item.ViewItem
import eu.koboo.minestom.stomui.api.pagination.ItemFilter
import eu.koboo.minestom.stomui.api.pagination.ItemRenderer
import eu.koboo.minestom.stomui.api.pagination.ViewPagination
import eu.koboo.minestom.stomui.api.slots.ViewPattern
import net.minestom.server.MinecraftServer
import net.minestom.server.component.DataComponents
import net.minestom.server.entity.Player
import net.minestom.server.inventory.click.ClickType
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import net.minestom.server.timer.TaskSchedule
import revxrsal.commands.annotation.Command
import twizzy.tech.viewRegistry
import java.util.concurrent.ConcurrentHashMap

class Blockshop {

    private val blockShopProvider = BlockShopProvider(viewRegistry)

    @Command("blockshop", "bs")
    suspend fun openBlockshop(actor: Player) {
        blockShopProvider.open(actor)
    }

    // Copy of SearchExampleProvider
    private class BlockShopProvider(registry: ViewRegistry) :
        ViewProvider(registry, ViewBuilder.of(VIEW_TYPE).title("Block Shop - Search:")),
        AnvilInputInteraction {

        companion object {
            private val ALL_MATERIALS = Material.values().toList()
            private val VIEW_TYPE = ViewType.ANVIL
            // Store original inventories for each player (only for anvil view)
            private val savedInventories = ConcurrentHashMap<Player, Array<ItemStack>>()

            fun savePlayerInventory(player: Player) {
                val inventory = player.inventory
                val items = Array(inventory.size) { index ->
                    val originalItem = inventory.getItemStack(index)
                    // Save the complete ItemStack as-is to preserve all NBT data, meta, etc.
                    originalItem
                }
                savedInventories[player] = items
            }

            fun restorePlayerInventory(player: Player) {
                savedInventories[player]?.let { items ->
                    val inventory = player.inventory
                    inventory.clear()

                    // Restore the complete ItemStacks
                    items.forEachIndexed { index, item ->
                        inventory.setItemStack(index, item)
                    }

                    savedInventories.remove(player)
                }
            }

            fun hasSavedInventory(player: Player): Boolean {
                return savedInventories.containsKey(player)
            }
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
                MaterialItemRenderer(registry),
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
            // Save player's inventory when opening the anvil search (whether first time or returning from block view)
            savePlayerInventory(player)

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
            restorePlayerInventory(player)
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

    // Copy of SearchItemFilter
    private class SearchItemFilter : ItemFilter<Material> {

        private var textInput: String? = null

        fun setTextInput(textInput: String) {
            this.textInput = textInput
        }

        override fun include(item: Material): Boolean {
            // Only blocks please. Why only blocks?
            // To show filtering based on categories or other parameters
            if (item == Material.AIR || !item.isBlock) {
                return false
            }
            // No text input → display all items.
            if (textInput.isNullOrEmpty()) {
                return true
            }
            // We got an input, so we check materials names.
            if (!item.name().lowercase().contains(textInput!!.lowercase())) {
                return false
            }
            // Got that in your name material? Good for you, have fun paginated!
            return true
        }
    }

    // Copy of MaterialItemRenderer
    private class MaterialItemRenderer(private val registry: ViewRegistry) : ItemRenderer<Material> {

        override fun render(material: Material): PrebuiltItem {
            return PrebuiltItem.of(ItemStack.of(material))
                .lore<ModifiableItem>(
                    "<gray>Material: <white>${material.name()}",
                    "<gray>Block ID: <white>${material.id()}",
                    "",
                    "<yellow>Left click: <white>Buy 1",
                    "<yellow>Right click: <white>Buy 64",
                    "<yellow>Shift + Left click: <white>Buy stack"
                )
                .interaction(Interactions.open(BlockDetailProvider(registry, material)))
        }

        private fun formatMaterialName(material: Material): String {
            return material.name()
                .lowercase()
                .split('_')
                .joinToString(" ") { word ->
                    word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                }
        }
    }

    // Block detail view provider - the buy menu for specific blocks
    private class BlockDetailProvider(registry: ViewRegistry, private val material: Material) :
        ViewProvider(registry, ViewBuilder.of(ViewType.SIZE_5_X_9)) {

        private var buyAmount = 1

        override fun modifyBuilder(viewBuilder: ViewBuilder, player: Player) {
            viewBuilder.title("Block Details: ${formatMaterialName(material)}")
        }

        override fun onOpen(view: PlayerView, player: Player) {
            // Reset buy amount when opening
            buyAmount = 1

            // Fill background with glass panes
            for (viewItem in ViewItem.bySlotList(view, view.type.topSlots)) {
                viewItem.material<ModifiableItem>(Material.GRAY_STAINED_GLASS_PANE)
                    .displayName<ModifiableItem>(" ")
                    .cancelClicking<ModifiableItem>()
            }

            // Back button
            ViewItem.byRowColumn(view, 4, 0)
                .material<ModifiableItem>(Material.ARROW)
                .displayName<ModifiableItem>("<red>Back to Block Shop")
                .interaction<ModifiableItem> { event ->
                    event.player.inventory.clear()
                }
                .interaction<ModifiableItem>(Interactions.open(BlockShopProvider(registry)))
        }

        override fun onRebuild(view: PlayerView, player: Player) {
            // Red glass panes for decreasing (left side)
            // -32 button
            ViewItem.byRowColumn(view, 2, 1)
                .material<ModifiableItem>(Material.RED_STAINED_GLASS_PANE)
                .displayName<ModifiableItem>("<red>-32")
                .amount<ModifiableItem>(32)
                .interaction<ModifiableItem> { event ->
                    buyAmount = maxOf(1, buyAmount - 32)
                    view.executeRebuild()
                }

            // -16 button
            ViewItem.byRowColumn(view, 2, 2)
                .material<ModifiableItem>(Material.RED_STAINED_GLASS_PANE)
                .displayName<ModifiableItem>("<red>-16")
                .amount<ModifiableItem>(16)
                .interaction<ModifiableItem> { event ->
                    buyAmount = maxOf(1, buyAmount - 16)
                    view.executeRebuild()
                }

            // -1 button
            ViewItem.byRowColumn(view, 2, 3)
                .material<ModifiableItem>(Material.RED_STAINED_GLASS_PANE)
                .displayName<ModifiableItem>("<red>-1")
                .amount<ModifiableItem>(1)
                .interaction<ModifiableItem> { event ->
                    buyAmount = maxOf(1, buyAmount - 1)
                    view.executeRebuild()
                }

            // Center item (the block to buy)
            val displayAmount = if (buyAmount > 99) buyAmount / 64 else buyAmount
            val isStackMode = buyAmount > 99

            ViewItem.byRowColumn(view, 2, 4)
                .material<ModifiableItem>(material)
                .addComponent<Int, ModifiableItem?>(DataComponents.MAX_STACK_SIZE, 99)
                .amount<ModifiableItem>(displayAmount)
                .displayName<ModifiableItem>("<yellow>${formatMaterialName(material)}")
                .lore<ModifiableItem>(
                    "<gray>Material: <white>${material.name()}",
                    "<gray>Block ID: <white>${material.id()}",
                    "",
                    if (isStackMode) {
                        "<yellow>Buy Amount: <white>$buyAmount blocks (<green>${buyAmount / 64} stacks</green>)"
                    } else {
                        "<yellow>Buy Amount: <white>$buyAmount blocks"
                    },
                    if (isStackMode) {
                        "<gray>Display shows: <white>${buyAmount / 64} stacks"
                    } else {
                        "<gray>Display shows: <white>$buyAmount blocks"
                    },
                    "",
                    "<green>Click to buy $buyAmount ${formatMaterialName(material)}"
                )
                .interaction<ModifiableItem> { event ->
                    // Handle the actual purchase here
                    event.player.sendMessage("Buying $buyAmount ${formatMaterialName(material)}")
                    // Add actual buy logic here
                }

            // Green glass panes for increasing (right side)
            // +1 button
            ViewItem.byRowColumn(view, 2, 5)
                .material<ModifiableItem>(Material.GREEN_STAINED_GLASS_PANE)
                .displayName<ModifiableItem>("<green>+1")
                .amount<ModifiableItem>(1)
                .interaction<ModifiableItem> { event ->
                    buyAmount = minOf(2304, buyAmount + 1) // Max 36 stacks
                    view.executeRebuild()
                }

            // +16 button
            ViewItem.byRowColumn(view, 2, 6)
                .material<ModifiableItem>(Material.GREEN_STAINED_GLASS_PANE)
                .displayName<ModifiableItem>("<green>+16")
                .amount<ModifiableItem>(16)
                .interaction<ModifiableItem> { event ->
                    buyAmount = minOf(2304, buyAmount + 16) // Max 36 stacks
                    view.executeRebuild()
                }

            // +32 button
            ViewItem.byRowColumn(view, 2, 7)
                .material<ModifiableItem>(Material.GREEN_STAINED_GLASS_PANE)
                .displayName<ModifiableItem>("<green>+32")
                .amount<ModifiableItem>(32)
                .interaction<ModifiableItem> { event ->
                    buyAmount = minOf(2304, buyAmount + 32) // Max 36 stacks
                    view.executeRebuild()
                }
        }

        override fun onClose(view: PlayerView, player: Player) {
            // Only restore inventory if this is the final close (not going back to search)
            // Check if player is still in the shop system
            if (!isPlayerInShopSystem(player)) {
                BlockShopProvider.restorePlayerInventory(player)
            }
        }

        private fun isPlayerInShopSystem(player: Player): Boolean {
            // Check if player will return to the anvil search view
            // This is a simple heuristic - if they have a saved inventory, they're still in the shop
            return BlockShopProvider.hasSavedInventory(player)
        }

        private fun formatMaterialName(material: Material): String {
            return material.name()
                .lowercase()
                .split('_')
                .joinToString(" ") { word ->
                    word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                }
        }
    }
}