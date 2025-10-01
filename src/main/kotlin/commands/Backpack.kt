package twizzy.tech.commands

import eu.koboo.minestom.stomui.api.*
import eu.koboo.minestom.stomui.api.component.ViewComponent
import eu.koboo.minestom.stomui.api.component.ViewProvider
import eu.koboo.minestom.stomui.api.flags.Flags
import eu.koboo.minestom.stomui.api.interaction.Interaction
import eu.koboo.minestom.stomui.api.interaction.ViewAction
import eu.koboo.minestom.stomui.api.item.ModifiableItem
import eu.koboo.minestom.stomui.api.item.PrebuiltItem
import eu.koboo.minestom.stomui.api.item.ViewItem
import eu.koboo.minestom.stomui.api.pagination.ItemRenderer
import eu.koboo.minestom.stomui.api.pagination.ViewPagination
import eu.koboo.minestom.stomui.api.slots.ViewPattern
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.entity.Player
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import revxrsal.commands.annotation.Command
import revxrsal.commands.annotation.Description
import revxrsal.commands.minestom.annotation.CommandPermission
import twizzy.tech.player.PlayerData
import twizzy.tech.viewRegistry
import java.math.BigDecimal

class Backpack {

    @Command("backpack", "bp")
    @CommandPermission("command.backpack")
    @Description("View your backpack contents")
    fun openBackpack(actor: Player) {
        val playerData = PlayerData.getFromCache(actor.uuid) ?: run {
            actor.sendMessage(Component.text("Unable to load your backpack data.", NamedTextColor.RED))
            return
        }

        val backpack = twizzy.tech.game.items.backpack.Backpack.fromItemStack(actor.inventory.getItemStack(8))
        if (backpack != null) {
            println("Opening backpack for player ${actor.username} with ${backpack.contents.size} items.")
            BackpackMenuProvider(playerData, backpack).open(actor)
        }
    }

    @Command("backpack sell", "bp sell", "blockpack sell")
    @CommandPermission("command.backpack")
    @Description("View your blockpack contents")
    fun sellBlockpack(actor: Player) {
        val playerData = PlayerData.getFromCache(actor.uuid) ?: run {
            actor.sendMessage(Component.text("Unable to load your backpack data.", NamedTextColor.RED))
            return
        }

        handleSellAction(actor, playerData)
    }

    private fun handleSellAction(player: Player, playerData: PlayerData) {
        val sellValue = twizzy.tech.gameEngine.calculateTotalSellValue(playerData.blockpack)

        if (sellValue > BigDecimal.ZERO) {
            // Credit player balance
            playerData.balance = playerData.balance.add(sellValue)

            // Add profit to the player's backpack item (slot 8) and persist it
            runCatching {
                val bpItem = player.inventory.getItemStack(8)
                val bp = twizzy.tech.game.items.backpack.Backpack.fromItemStack(bpItem)
                if (bp != null) {
                    bp.addProfit(sellValue.toDouble())
                    player.inventory.setItemStack(8, bp.toItemStack())
                }
            }.onFailure { ex ->
                ex.printStackTrace()
                player.sendMessage(Component.text("Failed to update backpack profit: ${ex.message}", NamedTextColor.RED))
            }

            // Clear blockpack after selling
            playerData.blockpack.clear()
            player.sendMessage(
                Component.text("Sold your backpack for $${"%,.2f".format(sellValue)}!", NamedTextColor.GREEN)
            )
        } else {
            player.sendMessage(
                Component.text("No valuable items to sell.", NamedTextColor.GRAY)
            )
        }

        twizzy.tech.util.ComponentSidebar.updateScoreboardForPlayer(player.uuid)
    }

    private class BackpackMenuProvider(
        private val playerData: PlayerData,
        private val backpack: twizzy.tech.game.items.backpack.Backpack
    ) : ViewProvider(
        viewRegistry,
        ViewBuilder.of(ViewType.SIZE_6_X_9)
            .title("<blue>Your Backpack")
            .withFlags(
                Flags.ALLOW_BOTTOM_INTERACTION,
                Flags.ALLOW_ITEM_DRAGGING
            )
    ) {
        private val pagination: ViewPagination<ItemStack>
        private val pattern = registry.pattern(
            "K###^###Z",
            "##     ##",
            "##     ##",
            "##     ##",
            "##     ##",
            "##U#v#S##"
        )

        // Track the current page explicitly to compute absolute slot indices
        private var currentPageIndex: Int = 0

        // Backpack capacity: 10 pages * 20 slots
        private val slotsPerPage = 20
        private val totalSlots = slotsPerPage * 10

        init {
            addChild(PaginationBorder())
            pagination = createPagination()
            addChild(pagination)
            addChild(PaginationActionButtons(pagination, pattern, this))
        }

        private fun createPagination(): ViewPagination<ItemStack> {
            return registry.pageable(
                BackpackItemRenderer(),
                ItemStack.AIR,
                pattern.getMergedSlots(' ')
            ).apply {
                // Build a 200-length list from absolute slot indices, fill with AIR
                val items = buildDisplayItems()
                addItems(items)
            }
        }

        override fun onOpen(view: PlayerView, player: Player) {
            currentPageIndex = 0
            pagination.refreshPage(view)
            player.sendMessage(Component.text("Backpack opened!", NamedTextColor.GREEN))
        }

        override fun onClose(view: PlayerView, player: Player) {
            // Persist current page before closing
            saveBackpackItems(view, player)
            // No pagination sync here to avoid resetting the current page unexpectedly
        }

        // Build a 200-size list (10 pages * 20 slots) where each index is an absolute slot
        private fun buildDisplayItems(): MutableList<ItemStack> {
            val list = MutableList(totalSlots) { ItemStack.AIR }
            backpack.contents.forEach { (slot, item) ->
                if (slot in 0 until totalSlots) list[slot] = item
            }
            return list
        }

        // Sync the pagination's internal item list from the current backpack contents
        private fun syncPaginationWithBackpack() {
            pagination.clearItems()
            val items = buildDisplayItems()
            pagination.addItems(items)
        }

        private fun saveBackpackItems(view: PlayerView, player: Player) {
            try {
                val currentItems = getCurrentPageItems(view)
                val pageBase = currentPageIndex * slotsPerPage

                // Update only the current page range in the backpack map
                currentItems.forEachIndexed { index, item ->
                    val absSlot = pageBase + index
                    if (absSlot in 0 until totalSlots) {
                        if (item == ItemStack.AIR) {
                            backpack.contents.remove(absSlot)
                        } else {
                            backpack.contents[absSlot] = item
                        }
                    }
                }

                // Save updated backpack to player's inventory slot 8
                val updatedBackpackItem = backpack.toItemStack()
                player.inventory.setItemStack(8, updatedBackpackItem)

                // Do not reset pagination here; it can invalidate current page and cause exceptions
            } catch (e: Exception) {
                player.sendMessage(Component.text("Failed to save items: ${e.message}", NamedTextColor.RED))
                e.printStackTrace()
            }
        }

        private fun getCurrentPageItems(view: PlayerView): List<ItemStack> {
            val backpackSlots = pattern.getSlots(' ')
            return backpackSlots.map { slot ->
                ViewItem.bySlot(view, slot).item
            }
        }

        private class PaginationBorder : ViewComponent() {
            override fun onOpen(view: PlayerView, player: Player) {
                val topSlots = view.type.topSlots
                for (viewItem in ViewItem.bySlotList(view, topSlots)) {
                    viewItem?.material<ModifiableItem?>(Material.GRAY_STAINED_GLASS_PANE)
                        ?.displayName<ModifiableItem?>(" ")
                }
            }
        }

        private class PaginationActionButtons(
            private val pagination: ViewPagination<ItemStack>,
            private val pattern: ViewPattern,
            private val parentProvider: BackpackMenuProvider
        ) : ViewComponent() {
            init {
                addChild(pagination)
            }

            override fun onRebuild(view: PlayerView, player: Player) {
                setupNavigationButtons(view, player)
                setupSellButton(view, player)
            }

            override fun onOpen(view: PlayerView, player: Player) {
                setupActionButtons(view, player)
            }

            private fun setupNavigationButtons(view: PlayerView, player: Player) {
                setupNextButton(view, player)
                setupPreviousButton(view, player)
            }

            private fun setupNextButton(view: PlayerView, player: Player) {
                ViewItem.bySlot(view, pattern.getSlot('v'))
                    .material<ModifiableItem?>(Material.ARROW)
                    ?.name<ModifiableItem?>("<green>Next")
                    ?.interaction<ModifiableItem> {
                        if (parentProvider.currentPageIndex < 9) {
                            parentProvider.saveBackpackItems(view, player)
                            // Rebuild pagination items from saved backpack so the next page reflects changes
                            parentProvider.syncPaginationWithBackpack()
                            parentProvider.currentPageIndex += 1
                            // toPage expects 1-based page numbers
                            pagination.toPage(view, parentProvider.currentPageIndex + 1)
                        }
                    }
            }

            private fun setupPreviousButton(view: PlayerView, player: Player) {
                ViewItem.bySlot(view, pattern.getSlot('^'))
                    .material<ModifiableItem?>(Material.ARROW)
                    ?.name<ModifiableItem?>("<green>Previous")
                    ?.interaction<ModifiableItem> {
                        if (parentProvider.currentPageIndex > 0) {
                            parentProvider.saveBackpackItems(view, player)
                            // Rebuild pagination items from saved backpack so the previous page reflects changes
                            parentProvider.syncPaginationWithBackpack()
                            parentProvider.currentPageIndex -= 1
                            // toPage expects 1-based page numbers
                            pagination.toPage(view, parentProvider.currentPageIndex + 1)
                        }
                    }
            }

            private fun setupActionButtons(view: PlayerView, player: Player) {
                setupCloseButton(view)
                setupRefreshButton(view, player)
                setupSellButton(view, player)
            }

            private fun setupCloseButton(view: PlayerView) {
                ViewItem.bySlot(view, pattern.getSlot('K'))
                    .material<ModifiableItem?>(Material.REDSTONE)
                    ?.name<ModifiableItem?>("Close")
                    ?.closeInventoryInteraction<ModifiableItem?>()
            }

            private fun setupRefreshButton(view: PlayerView, player: Player) {
                ViewItem.bySlot(view, pattern.getSlot('Z'))
                    .material<ModifiableItem?>(Material.NETHER_STAR)
                    ?.name<ModifiableItem?>("Refresh")
                    ?.interaction<ModifiableItem?> {
                        // Rebuild the 200-slot list from current contents
                        parentProvider.syncPaginationWithBackpack()
                        pagination.refreshPage(view)
                        player.sendMessage(Component.text("Backpack refreshed!", NamedTextColor.GREEN))
                    }
            }

            private fun setupSellButton(view: PlayerView, player: Player) {
                updateSellButton(view, player, parentProvider.playerData)
            }

            private fun updateSellButton(view: PlayerView, player: Player, playerData: PlayerData) {
                val totalItems = playerData.blockpack.values.sum()
                val sellValue = twizzy.tech.gameEngine.calculateTotalSellValue(playerData.blockpack)

                ViewItem.bySlot(view, pattern.getSlot('S'))
                    .material<ModifiableItem>(Material.EMERALD)
                    .displayName<ModifiableItem>("<green><bold>Sell Blockpack</bold></green>")
                    .lore<ModifiableItem>(
                        "<gray>Blocks: <green>${String.format("%,d", totalItems)}</green></gray>",
                        "<gray>Value: <yellow>$${"%,.2f".format(sellValue)}</yellow></gray>",
                        "",
                        "<yellow>Click to sell all items!</yellow>"
                    )
                    .glint<ModifiableItem>(sellValue > BigDecimal.ZERO)
                    .interaction<ModifiableItem>(Interaction { action: ViewAction ->
                        Backpack().handleSellAction(player, playerData)
                        view.executeRebuild()
                    })
            }
        }

        private class BackpackItemRenderer : ItemRenderer<ItemStack> {
            override fun render(itemStack: ItemStack): PrebuiltItem {
                return PrebuiltItem.of(itemStack).allowClicking<PrebuiltItem>()
            }
        }
    }
}
