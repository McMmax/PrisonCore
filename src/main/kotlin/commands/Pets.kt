package twizzy.tech.commands

import eu.koboo.minestom.stomui.api.*
import eu.koboo.minestom.stomui.api.component.ViewComponent
import eu.koboo.minestom.stomui.api.component.ViewProvider
import eu.koboo.minestom.stomui.api.item.ModifiableItem
import eu.koboo.minestom.stomui.api.item.PrebuiltItem
import eu.koboo.minestom.stomui.api.item.ViewItem
import eu.koboo.minestom.stomui.api.pagination.ItemRenderer
import eu.koboo.minestom.stomui.api.pagination.ViewPagination
import eu.koboo.minestom.stomui.api.slots.ViewPattern
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import net.minestom.server.component.DataComponents
import net.minestom.server.entity.Player
import net.minestom.server.inventory.click.Click
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import revxrsal.commands.annotation.Command
import revxrsal.commands.annotation.Description
import revxrsal.commands.minestom.annotation.CommandPermission
import twizzy.tech.game.items.containers.PetPouch
import twizzy.tech.game.pets.Pet
import twizzy.tech.game.pets.Rarity
import twizzy.tech.util.YamlFactory
import twizzy.tech.viewRegistry

class Pets {
    @Command("pets")
    @CommandPermission("command.pets")
    @Description("View your pet pouch contents")
    fun openPetPouch(actor: Player) {
        PetsMainMenuProvider().open(actor)
    }

    // Configuration cache
    private val lang by lazy { YamlFactory.loadConfig("lang.yml") }

    private class PetsMainMenuProvider : ViewProvider(
        viewRegistry,
        ViewBuilder.of(ViewType.SIZE_3_X_9)
            .title("<gold>Pets Menu")
    ) {
        override fun onOpen(view: PlayerView, player: Player) {
            setupMenuItems(view, player)
        }

        // Configuration cache
        private val lang by lazy { YamlFactory.loadConfig("lang.yml") }


        private fun setupMenuItems(view: PlayerView, player: Player) {
            // Clear all slots first
            for (i in 0 until 27) {
                ViewItem.bySlot(view, i).material<ModifiableItem?>(Material.AIR)
            }

            // Set up the three centered items (slots 12, 13, 14 in a 3x9 grid)
            setupCardsItem(view, player) // Left item (slot 12)
            setupPetPouchItem(view, player) // Middle item (slot 13)
            setupThirdItem(view, player) // Right item (slot 14)
        }

        private fun setupCardsItem(view: PlayerView, player: Player) {
            ViewItem.bySlot(view, 12)
                .material<ModifiableItem?>(Material.VEX_ARMOR_TRIM_SMITHING_TEMPLATE)
                ?.name<ModifiableItem?>("<yellow>Pet Cards")
                ?.lore<ModifiableItem?>(listOf(
                    "<gray>Buy pet cards and upgrades",
                    "<green>Click to open!"
                ))
                ?.interaction<ModifiableItem> {
                    PetCardsMenuProvider().open(player)
                    it.event.click
                }
        }

        private fun setupPetPouchItem(view: PlayerView, player: Player) {
            ViewItem.bySlot(view, 13)
                .material<ModifiableItem?>(Material.BUNDLE)
                ?.name<ModifiableItem?>("<gold>Pet Pouch")
                ?.lore<ModifiableItem?>(listOf(
                    "<gray>View and manage your pets",
                    "<green>Click to open!"
                ))
                ?.interaction<ModifiableItem> {
                    // Get pet pouch from player inventory
                    val pouchItem = player.inventory.getItemStack(7)
                    val petPouch = PetPouch.fromItemStack(pouchItem)
                    if (petPouch != null) {
                        PetPouchMenuProvider(petPouch).open(player)
                    } else {
                        val message = YamlFactory.getValue(lang, "pets.not_found_in_pouch", "&cThe selected pet was not found in your pouch.")
                        player.sendMessage(Component.text(message))
                    }
                }
        }

        private fun setupThirdItem(view: PlayerView, player: Player) {
            ViewItem.bySlot(view, 14)
                .material<ModifiableItem?>(Material.EXPERIENCE_BOTTLE)
                ?.name<ModifiableItem?>("<aqua>Pet Training")
                ?.lore<ModifiableItem?>(listOf(
                    "<gray>Train and level up your pets",
                    "<green>Click to open!"
                ))
                ?.interaction<ModifiableItem> {
                    player.sendMessage(Component.text("Pet training menu coming soon!", NamedTextColor.AQUA))
                }
        }
    }

    private class PetPouchMenuProvider(
        private val petPouch: PetPouch
    ) : ViewProvider(
        viewRegistry,
        ViewBuilder.of(ViewType.SIZE_6_X_9)
            .title("<gold>Your Pet Pouch")
    ) {
        enum class SortDirection { ASC, DESC }
        enum class PetFilter { TYPE, RARITY, LEVEL, HAPPINESS, FOOD }
        private var currentFilter: PetFilter = PetFilter.TYPE
        private var sortDirection: SortDirection = SortDirection.DESC
        private val pagination: ViewPagination<ItemStack>
        private val pattern = registry.pattern(
            "#########",
            "##PPPPP##",  // Selected pets slots (P)
            "##     ##",
            "#^     v#",
            "##     ##",
            "##TRLHF##"
        )
        private var currentPageIndex: Int = 0
        private val totalSlots = 54  // 0-53 slots total

        init {
            addChild(PaginationBorder())
            pagination = createPagination()
            addChild(pagination)
            addChild(PaginationActionButtons(pagination, pattern, this))
            addChild(SelectedSlotsRenderer())
            addChild(FilterButtonsRenderer())
        }

        private fun createPagination(): ViewPagination<ItemStack> {
            return registry.pageable(
                PetItemRenderer(),
                ItemStack.AIR,
                pattern.getMergedSlots(' ')
            ).apply {
                val items = buildDisplayItems()
                addItems(items)
            }
        }

        override fun onOpen(view: PlayerView, player: Player) {
            currentPageIndex = 0
            pagination.refreshPage(view)
        }

        override fun onClose(view: PlayerView, player: Player) {
            savePetPouch(player)
        }

        private fun buildDisplayItems(): MutableList<ItemStack> {
            val pets = petPouch.contents.values.filter { !petPouch.selectedPets.contains(it) }
                .mapNotNull { item ->
                    val pet = Pet.fromItemStack(item)
                    if (pet != null) {
                        pet.update() // Ensure stats are up-to-date
                        pet to pet.toItemStack()
                    } else null
                }
            val sortedPets = when (currentFilter) {
                PetFilter.TYPE -> if (sortDirection == SortDirection.DESC)
                    pets.sortedByDescending { it.first.javaClass.simpleName }
                else
                    pets.sortedBy { it.first.javaClass.simpleName }
                PetFilter.RARITY -> if (sortDirection == SortDirection.DESC)
                    pets.sortedByDescending { it.first.rarity.ordinal }
                else
                    pets.sortedBy { it.first.rarity.ordinal }
                PetFilter.LEVEL -> if (sortDirection == SortDirection.DESC)
                    pets.sortedByDescending { it.first.level }
                else
                    pets.sortedBy { it.first.level }
                PetFilter.HAPPINESS -> if (sortDirection == SortDirection.DESC)
                    pets.sortedByDescending { it.first.happiness }
                else
                    pets.sortedBy { it.first.happiness }
                PetFilter.FOOD -> if (sortDirection == SortDirection.DESC)
                    pets.sortedByDescending { it.first.hunger }
                else
                    pets.sortedBy { it.first.hunger }
            }.map { it.second }
            val list = MutableList(totalSlots) { ItemStack.AIR }
            sortedPets.forEachIndexed { idx, item ->
                if (idx < totalSlots) list[idx] = item
            }
            return list
        }

        private fun savePetPouch(player: Player) {
            try {
                val updatedPouchItem = petPouch.toItemStack()
                player.inventory.setItemStack(7, updatedPouchItem)
            } catch (e: Exception) {
                player.sendMessage(Component.text("Failed to save pet pouch: ${e.message}", NamedTextColor.RED))
                e.printStackTrace()
            }
        }

        private fun refreshAll(view: PlayerView, player: Player) {
            pagination.clearItems()
            val items = buildDisplayItems()
            pagination.addItems(items)
            pagination.refreshPage(view)
            view.executeRebuild() // Rebuild the entire view to update selected slots
        }

        private fun getRequiredLevelForRarity(currentRarity: Rarity): Int {
            return when (currentRarity) {
                Rarity.COMMON -> 5
                Rarity.UNCOMMON -> 10
                Rarity.RARE -> 15
                Rarity.EPIC -> 20
                Rarity.LEGENDARY -> 30
                Rarity.MYTHIC -> 50
                Rarity.ETERNAL -> Int.MAX_VALUE // Cannot fuse eternal pets
            }
        }

        private class PaginationBorder : ViewComponent() {
            override fun onOpen(view: PlayerView, player: Player) {
                val topSlots = view.type.topSlots
                for (viewItem in ViewItem.bySlotList(view, topSlots)) {
                    viewItem?.material<ModifiableItem?>(Material.LIGHT_BLUE_STAINED_GLASS_PANE)
                        ?.displayName<ModifiableItem?>(" ")
                }
            }
        }

        private inner class PaginationActionButtons(
            private val pagination: ViewPagination<ItemStack>,
            private val pattern: ViewPattern,
            private val parentProvider: PetPouchMenuProvider
        ) : ViewComponent() {
            init {
                addChild(pagination)
            }

            override fun onRebuild(view: PlayerView, player: Player) {
                setupNavigationButtons(view, player)
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
                        // Use the correct method to go to next page
                        val currentPage = pagination.currentPage
                        val maxPages = pagination.totalPages
                        if (currentPage < maxPages) {
                            pagination.toPage(view, currentPage + 1)
                        }
                    }
            }

            private fun setupPreviousButton(view: PlayerView, player: Player) {
                ViewItem.bySlot(view, pattern.getSlot('^'))
                    .material<ModifiableItem?>(Material.ARROW)
                    ?.name<ModifiableItem?>("<green>Previous")
                    ?.interaction<ModifiableItem> {
                        // Use the correct method to go to previous page
                        val currentPage = pagination.currentPage
                        if (currentPage > 1) {
                            pagination.toPage(view, currentPage - 1)
                        }
                    }
            }
        }

        private inner class SelectedSlotsRenderer : ViewComponent() {
            private val pSlots by lazy { pattern.getSlots('P') }

            // Configuration cache
            private val lang by lazy { YamlFactory.loadConfig("lang.yml") }

            override fun onRebuild(view: PlayerView, player: Player) {
                for (i in 0 until PetPouch.MAX_SELECTED_PETS) {
                    val item = petPouch.selectedPets.getOrNull(i) ?: ItemStack.AIR
                    val isActive = petPouch.activePetSlot == i
                    val slotIndex = pSlots.getOrNull(i)
                    if (slotIndex != null) {
                        val viewItem = ViewItem.bySlot(view, slotIndex)

                        if (item.isAir) {
                            // Create a non-glowing glass pane for empty slots
                            val glassPane = ItemStack.builder(Material.GRAY_STAINED_GLASS_PANE)
                                .customName(Component.text("§7Empty Slot ${i + 1}"))
                                .build()

                            viewItem.material<ModifiableItem?>(Material.GRAY_STAINED_GLASS_PANE)
                                ?.item<ModifiableItem?>(glassPane) // Explicitly set the item to ensure no glowing
                                ?.displayName<ModifiableItem?>("<gray>Empty Slot ${i + 1}")
                                ?.lore<ModifiableItem?>(listOf("<yellow>Select a pet from below"))
                                ?.cancelClicking<ModifiableItem?>()
                        } else {
                            val petNameComponent = item.get(DataComponents.CUSTOM_NAME) ?: Component.text("Unknown Pet")
                            val petName = MiniMessage.miniMessage().serialize(petNameComponent)
                            val finalName = if (isActive) "<green>● $petName" else petName

                            viewItem.material<ModifiableItem?>(item.material())
                                ?.item<ModifiableItem?>(if (isActive) item.withGlowing(true) else item)
                                ?.displayName<ModifiableItem?>(MiniMessage.miniMessage().deserialize(finalName))
                                ?.lore<ModifiableItem?>(buildSelectedPetLore(item, isActive))
                                ?.cancelClicking<ModifiableItem?>()
                                ?.interaction<ModifiableItem> {
                                    if (it.event.click is Click.Right) {
                                        // Right-click to unselect - use new method signature
                                        val wasActive = (petPouch.activePetSlot == i)
                                        if (petPouch.unselectPet(i)) {
                                            if (wasActive) {
                                                petPouch.deactivatePet(player)
                                            }
                                            savePetPouch(player)
                                            refreshAll(view, player)
                                            val message = YamlFactory.getMessage("pets.unselected", mapOf("pet_name" to petName))
                                            player.sendMessage(message)
                                        }
                                    } else if (it.event.click is Click.Left) {
                                        val previousActiveSlot = petPouch.activePetSlot
                                        // If switching or deactivating, deactivate previous pet
                                        if (previousActiveSlot != -1 && previousActiveSlot != i) {
                                            petPouch.deactivatePet(player)
                                        }
                                        // Activate or deactivate current slot
                                        if (petPouch.activePetSlot == i) {
                                            petPouch.deactivatePet(player)
                                            savePetPouch(player)
                                            refreshAll(view, player)
                                        } else {
                                            val instance = player.instance
                                            val position = player.position
                                            petPouch.activatePet(player, i, instance, position)
                                            savePetPouch(player)
                                            refreshAll(view, player)
                                        }
                                    }
                                }
                        }
                    }
                }
            }

            override fun onOpen(view: PlayerView, player: Player) {
                onRebuild(view, player)
            }

            private fun buildSelectedPetLore(item: ItemStack, isActive: Boolean): List<String> {
                val mm = MiniMessage.miniMessage()
                val baseLore = item.get(DataComponents.LORE)?.map { mm.serialize(it) } ?: emptyList()
                val lore = baseLore.toMutableList()

                lore.add("")
                if (isActive) {
                    lore.add("<green>✓ Currently Active")
                } else {
                    lore.add("<yellow>Left-click to activate")
                }
                lore.add("<red>Right-click to unselect")

                return lore
            }
        }

        private inner class FilterButtonsRenderer : ViewComponent() {
            private val filterSlots = mapOf(
                'T' to PetFilter.TYPE,
                'R' to PetFilter.RARITY,
                'L' to PetFilter.LEVEL,
                'H' to PetFilter.HAPPINESS,
                'F' to PetFilter.FOOD
            )
            private fun setupFilterButtons(view: PlayerView, player: Player) {
                for ((char, filter) in filterSlots) {
                    val slot = pattern.getSlot(char)
                    val isSelected = currentFilter == filter
                    val material = when (filter) {
                        PetFilter.TYPE -> Material.CAT_SPAWN_EGG
                        PetFilter.RARITY -> Material.NETHER_STAR
                        PetFilter.LEVEL -> Material.EXPERIENCE_BOTTLE
                        PetFilter.HAPPINESS -> Material.HONEY_BOTTLE
                        PetFilter.FOOD -> Material.BREAD
                    }
                    val directionArrow = if (isSelected) {
                        if (sortDirection == SortDirection.DESC) " ↓" else " ↑"
                    } else ""
                    val name = when (filter) {
                        PetFilter.TYPE -> "<yellow>Type"
                        PetFilter.RARITY -> "<aqua>Rarity"
                        PetFilter.LEVEL -> "<green>Level"
                        PetFilter.HAPPINESS -> "<gold>Happiness"
                        PetFilter.FOOD -> "<red>Food"
                    } + if (isSelected) " <green>(Selected)" else "" + directionArrow
                    ViewItem.bySlot(view, slot)
                        .material<ModifiableItem?>(material)
                        ?.name<ModifiableItem?>(name)
                        ?.lore<ModifiableItem?>(listOf("<gray>Sort by $name", "<gray>Left click: select", "<gray>Right click: reverse order"))
                        ?.interaction<ModifiableItem> {
                            if (it.event.click is Click.Left) {
                                if (currentFilter != filter) {
                                    currentFilter = filter
                                    sortDirection = SortDirection.DESC
                                    refreshAll(view, player)
                                    view.executeRebuild()
                                }
                            } else if (it.event.click is Click.Right) {
                                if (currentFilter == filter) {
                                    sortDirection = if (sortDirection == SortDirection.DESC) SortDirection.ASC else SortDirection.DESC
                                    refreshAll(view, player)
                                    view.executeRebuild()
                                }
                            }
                        }
                }
            }
            override fun onOpen(view: PlayerView, player: Player) {
                setupFilterButtons(view, player)
            }
            override fun onRebuild(view: PlayerView, player: Player) {
                setupFilterButtons(view, player)
            }
        }

        private inner class PetItemRenderer : ItemRenderer<ItemStack> {

            // Configuration cache
            private val lang by lazy { YamlFactory.loadConfig("lang.yml") }

            // Track last drop attempt per player (UUID -> Pair<slot, petUid>)
            private val lastDropAttempt = mutableMapOf<java.util.UUID, Pair<Int, String>>()

            override fun render(itemStack: ItemStack): PrebuiltItem {
                if (itemStack.isAir || Pet.fromItemStack(itemStack) == null) {
                    return PrebuiltItem.of(itemStack)
                }

                // Create a new ItemStack with action instructions added to the lore
                val existingLore = itemStack.get(DataComponents.LORE) ?: emptyList()
                val newLore = existingLore.toMutableList()

                // Add action instructions
                newLore.add(Component.text(""))
                newLore.add(MiniMessage.miniMessage().deserialize("<yellow>Left-click to select"))
                newLore.add(MiniMessage.miniMessage().deserialize("<blue>Right-click to fuse"))
                newLore.add(MiniMessage.miniMessage().deserialize("<red>Drop to remove from pouch"))

                val enhancedItemStack = itemStack.withLore(newLore)

                return PrebuiltItem.of(enhancedItemStack).apply {
                    interaction<ModifiableItem> {
                        val player = it.player
                        val displayedPet = Pet.fromItemStack(itemStack)
                        val petUid = displayedPet?.uid?.toString() ?: itemStack.hashCode().toString()

                        // Find contents slot by matching pet UID instead of ItemStack reference
                        val contentsSlot = petPouch.contents.entries.find { entry ->
                            val entryPet = Pet.fromItemStack(entry.value)
                            entryPet?.uid == displayedPet?.uid && !petPouch.selectedPets.any { selectedItem ->
                                selectedItem != null && Pet.fromItemStack(selectedItem)?.uid == entryPet?.uid
                            }
                        }?.key

                        if (it.event.click is Click.Left) {
                            if (contentsSlot != null && displayedPet != null) {
                                // Check if pet type is already selected before attempting to select
                                val isDuplicateType = petPouch.selectedPets.any { selectedItem ->
                                    selectedItem != null && Pet.fromItemStack(selectedItem)?.petType == displayedPet.petType
                                }

                                if (isDuplicateType) {
                                    it.player.sendMessage(Component.text("You already have a ${displayedPet.petType.defaultName} pet selected!", NamedTextColor.RED))
                                } else if (petPouch.selectPet(contentsSlot)) {
                                    savePetPouch(it.player)
                                    refreshAll(it.view, it.player)
                                    val message = YamlFactory.getMessage("pets.selected", mapOf("pet_name" to displayedPet.displayName))
                                    it.player.sendMessage(message)
                                } else {
                                    it.player.sendMessage(Component.text("Cannot select pet - no available slots!", NamedTextColor.RED))
                                }
                            } else {
                                val message = YamlFactory.getValue(lang, "pets.not_found_in_pouch", "&cThe selected pet was not found in your pouch.")
                                it.player.sendMessage(MiniMessage.miniMessage().deserialize(message))
                            }
                        } else if (it.event.click is Click.Right) {
                            // Open fuse menu for this pet type - but only if pet meets level requirement
                            if (displayedPet != null) {
                                val requiredLevel = getRequiredLevelForRarity(displayedPet.rarity)
                                if (displayedPet.level >= requiredLevel) {
                                    PetFuseMenuProvider(petPouch, displayedPet).open(it.player)
                                } else {
                                    it.player.sendMessage(Component.text("Pet must be at least level $requiredLevel to fuse!", NamedTextColor.RED))
                                }
                            }
                        } else if (it.event.click is Click.DropSlot) {
                            if (contentsSlot != null) {
                                val last = lastDropAttempt[player.uuid]
                                if (last == null || last.first != contentsSlot || last.second != petUid) {
                                    lastDropAttempt[player.uuid] = contentsSlot to petUid
                                    player.sendMessage(Component.text("Press drop again to confirm pet removal!", NamedTextColor.RED))
                                } else {
                                    petPouch.contents.remove(contentsSlot)
                                    savePetPouch(player)
                                    refreshAll(it.view, player)
                                    player.sendMessage(Component.text("Pet removed from pouch!", NamedTextColor.YELLOW))
                                    lastDropAttempt.remove(player.uuid)
                                }
                            }
                        } else {
                            // Reset confirmation if any other interaction
                            lastDropAttempt.remove(player.uuid)
                        }
                        null
                    }
                }
            }
        }
    }

    private class PetCardsMenuProvider : ViewProvider(
        viewRegistry,
        ViewBuilder.of(ViewType.SIZE_3_X_9)
            .title("<yellow>Pet Card Packs")
    ) {
        override fun onOpen(view: PlayerView, player: Player) {
            setupPackItems(view, player)
        }
        private fun setupPackItems(view: PlayerView, player: Player) {
            // Rookie Pack - COMMON and UNCOMMON
            ViewItem.bySlot(view, 11)
                .material<ModifiableItem?>(Material.PAPER)
                ?.name<ModifiableItem?>("<white>Rookie Pack")
                ?.lore<ModifiableItem?>(listOf("<gray>Unboxes 3 random rookie pets.", "<gray>Contains Common and Uncommon pets.", "<green>Click to open!"))
                ?.interaction<ModifiableItem> {
                    openPack(player, listOf(Rarity.COMMON, Rarity.UNCOMMON))
                }
            // Trainer Pack - RARE and EPIC
            ViewItem.bySlot(view, 13)
                .material<ModifiableItem?>(Material.BOOK)
                ?.name<ModifiableItem?>("<aqua>Trainer Pack")
                ?.lore<ModifiableItem?>(listOf("<gray>Unboxes 3 random trainer pets.", "<gray>Contains Rare and Epic pets.", "<green>Click to open!"))
                ?.interaction<ModifiableItem> {
                    openPack(player, listOf(Rarity.RARE, Rarity.EPIC))
                }
            // Master Pack - LEGENDARY and MYTHIC
            ViewItem.bySlot(view, 15)
                .material<ModifiableItem?>(Material.ENCHANTED_BOOK)
                ?.name<ModifiableItem?>("<gold>Master Pack")
                ?.lore<ModifiableItem?>(listOf("<gray>Unboxes 3 random master pets.", "<gray>Contains Legendary and Mythic pets.", "<green>Click to open!", "<red>Eternal pets cannot be unboxed!"))
                ?.interaction<ModifiableItem> {
                    openPack(player, listOf(Rarity.LEGENDARY, Rarity.MYTHIC))
                }
        }
        private fun openPack(player: Player, availableRarities: List<Rarity>) {
            val pets = List(3) { Pet.random(availableRarities.random()) }
            PetRevealMenuProvider(pets).open(player)
        }
    }

    private class PetRevealMenuProvider(
        private val pets: List<Pet>
    ) : ViewProvider(
        viewRegistry,
        ViewBuilder.of(ViewType.SIZE_1_X_9)
            .title("<gold>Pet Reveal")
    ) {
        private val state = Array(pets.size) { RevealState.HIDDEN }
        enum class RevealState { HIDDEN, REVEALED, CLAIMED }
        override fun onOpen(view: PlayerView, player: Player) {
            for (i in pets.indices) {
                showHidden(view, player, i)
            }
        }
        private fun showHidden(view: PlayerView, player: Player, slotIndex: Int) {
            state[slotIndex] = RevealState.HIDDEN
            val pet = pets[slotIndex]
            ViewItem.bySlot(view, 3 + slotIndex)
                .material<ModifiableItem?>(pet.rarity.material)
                ?.name<ModifiableItem?>("${pet.rarity.displayName} Pet")
                ?.lore<ModifiableItem?>(listOf("<gray>Click to reveal!"))
                ?.interaction<ModifiableItem> {
                    if (state[slotIndex] == RevealState.HIDDEN) {
                        showRevealed(view, player, slotIndex)
                    }
                }
        }
        private fun showRevealed(view: PlayerView, player: Player, slotIndex: Int) {
            state[slotIndex] = RevealState.REVEALED
            val pet = pets[slotIndex]
            ViewItem.bySlot(view, 3 + slotIndex)
                .material<ModifiableItem?>(pet.petType.displayItem)
                ?.name<ModifiableItem?>("${pet.rarity.displayName} ${pet.displayName}")
                ?.lore<ModifiableItem?>(listOf(
                    "<gray>Pet: <white>${pet.petType.defaultName}",
                    "<yellow>Level: ${pet.level}",
                    "<green>Click to claim pet!"
                ))
                ?.interaction<ModifiableItem> {
                    if (state[slotIndex] == RevealState.REVEALED) {
                        showClaimed(view, player, slotIndex)
                        addPetToPouch(player, pet)
                        checkAllClaimed(player)
                    }
                }
        }
        private fun showClaimed(view: PlayerView, player: Player, slotIndex: Int) {
            state[slotIndex] = RevealState.CLAIMED
            ViewItem.bySlot(view, 3 + slotIndex)
                .material<ModifiableItem?>(Material.AIR)
                ?.name<ModifiableItem?>("")
                ?.lore<ModifiableItem?>(listOf("<gray>Added to your pouch!"))
                ?.interaction<ModifiableItem> {}
        }
        private fun addPetToPouch(player: Player, pet: Pet) {
            val pouchItem = player.inventory.getItemStack(7)
            val petPouch = PetPouch.fromItemStack(pouchItem)
            if (petPouch != null) {
                val slot = (0..53).firstOrNull { !petPouch.contents.containsKey(it) }
                if (slot != null) {
                    petPouch.contents[slot] = pet.toItemStack()
                    player.inventory.setItemStack(7, petPouch.toItemStack())
                    player.sendMessage(Component.text("You claimed a ${pet.rarity.displayName} ${pet.petType.defaultName} pet!", NamedTextColor.GREEN))
                } else {
                    player.sendMessage(Component.text("Your pet pouch is full!", NamedTextColor.RED))
                }
            } else {
                player.sendMessage(Component.text("Unable to find your pet pouch!", NamedTextColor.RED))
            }
        }
        private fun checkAllClaimed(player: Player) {
            if (state.all { it == RevealState.CLAIMED }) {
                PetCardsMenuProvider().open(player)
            }
        }
    }

    private class PetFuseMenuProvider(
        private val petPouch: PetPouch,
        private val targetPet: Pet // The pet that was right-clicked to open this menu
    ) : ViewProvider(
        viewRegistry,
        ViewBuilder.of(ViewType.SIZE_6_X_9)
            .title("<blue>Fuse ${targetPet.displayName}")
    ) {
        private val selectedForFusion = mutableListOf<Int>() // Store contents slot indices
        private val targetPetSlot: Int // Store the slot of the pet being fused
        private val pagination: ViewPagination<ItemStack>
        private val pattern = registry.pattern(
            "#########",
            "##SSSFP##",  // Selected pets for fusion (S), target pet (P), fuse (F)
            "##     ##",
            "#^     v#",
            "##     ##",
            "#########"
        )

        init {
            // Find the target pet's slot in the pouch
            targetPetSlot = petPouch.contents.entries.find { entry ->
                val entryPet = Pet.fromItemStack(entry.value)
                entryPet?.uid == targetPet.uid
            }?.key ?: -1

            addChild(PaginationBorder())
            pagination = createPagination()
            addChild(pagination)
            addChild(PaginationActionButtons(pagination, pattern, this))
            addChild(FusionSlotsRenderer())
        }

        private fun createPagination(): ViewPagination<ItemStack> {
            return registry.pageable(
                FusePetItemRenderer(),
                ItemStack.AIR,
                pattern.getMergedSlots(' ')
            ).apply {
                val items = buildFusionItems()
                addItems(items)
            }
        }

        override fun onOpen(view: PlayerView, player: Player) {
            pagination.refreshPage(view)
        }

        override fun onClose(view: PlayerView, player: Player) {
            // Save any changes back to the pet pouch
            try {
                val updatedPouchItem = petPouch.toItemStack()
                player.inventory.setItemStack(7, updatedPouchItem)
            } catch (e: Exception) {
                player.sendMessage(Component.text("Failed to save pet pouch: ${e.message}", NamedTextColor.RED))
            }
        }

        private fun buildFusionItems(): MutableList<ItemStack> {
            val requiredLevel = getRequiredLevel(targetPet.rarity)

            // Show pets of the same type as target pet, but exclude the target pet itself, selected pets, pets below required level, and pets currently selected for fusion
            val fusionPets = petPouch.contents.entries
                .filter { entry ->
                    val pet = Pet.fromItemStack(entry.value)
                    pet != null &&
                    pet.petType == targetPet.petType &&
                    pet.level >= requiredLevel && // Only show pets that meet the level requirement
                    entry.key != targetPetSlot && // Exclude the target pet being fused
                    !selectedForFusion.contains(entry.key) && // Exclude pets currently selected for fusion
                    !petPouch.selectedPets.any { selectedItem ->
                        selectedItem != null && Pet.fromItemStack(selectedItem)?.uid == pet.uid
                    }
                }
                .map { it.value }
                .mapNotNull { item ->
                    val pet = Pet.fromItemStack(item)
                    if (pet != null) pet to pet.toItemStack() else null
                }
                // Sort by level highest to lowest
                .sortedByDescending { it.first.level }
                .map { it.second }

            val list = MutableList(20) { ItemStack.AIR }
            fusionPets.forEachIndexed { idx, item ->
                if (idx < 20) list[idx] = item
            }
            return list
        }

        private fun refreshFusionView(view: PlayerView) {
            pagination.clearItems()
            val items = buildFusionItems()
            pagination.addItems(items)
            pagination.refreshPage(view)
            view.executeRebuild()
        }

        private fun getRequiredLevel(currentRarity: Rarity): Int {
            return when (currentRarity) {
                Rarity.COMMON -> 5
                Rarity.UNCOMMON -> 10
                Rarity.RARE -> 15
                Rarity.EPIC -> 20
                Rarity.LEGENDARY -> 30
                Rarity.MYTHIC -> 50
                Rarity.ETERNAL -> Int.MAX_VALUE // Cannot upgrade eternal pets
            }
        }

        private fun getNextRarity(currentRarity: Rarity): Rarity? {
            return when (currentRarity) {
                Rarity.COMMON -> Rarity.UNCOMMON
                Rarity.UNCOMMON -> Rarity.RARE
                Rarity.RARE -> Rarity.EPIC
                Rarity.EPIC -> Rarity.LEGENDARY
                Rarity.LEGENDARY -> Rarity.MYTHIC
                Rarity.MYTHIC -> Rarity.ETERNAL
                Rarity.ETERNAL -> null // Cannot upgrade eternal pets
            }
        }

        private fun canFuse(): Boolean {
            // Check if target pet meets level requirement
            val requiredLevel = getRequiredLevel(targetPet.rarity)
            if (targetPet.level < requiredLevel) return false

            // Need exactly 3 sacrifice pets
            if (selectedForFusion.size != 3) return false

            val selectedPets = selectedForFusion.mapNotNull { slot ->
                petPouch.contents[slot]?.let { Pet.fromItemStack(it) }
            }

            if (selectedPets.size != 3) return false

            // All sacrifice pets must be same type and at least required level
            return selectedPets.all { pet ->
                pet.petType == targetPet.petType &&
                pet.level >= requiredLevel
            }
        }

        private fun performFusion(player: Player): Boolean {
            if (!canFuse()) return false

            val nextRarity = getNextRarity(targetPet.rarity) ?: return false
            val sacrificePets = selectedForFusion.mapNotNull { petPouch.contents[it] }
            val highestLevel = (sacrificePets.mapNotNull { Pet.fromItemStack(it)?.level } + listOf(targetPet.level)).maxOrNull() ?: targetPet.level

            // Create a new pet of the same type with the next rarity
            val constructor = targetPet.petType.petClass.getConstructor(Rarity::class.java, Int::class.java)
            val upgradedPet = constructor.newInstance(nextRarity, highestLevel)

            // Remove sacrificed pets and target pet from the pouch
            (selectedForFusion + targetPetSlot).forEach { slot ->
                petPouch.removeItem(slot)
            }

            // Add the new upgraded pet to the pouch
            petPouch.addItem(upgradedPet.toItemStack())

            // Clear the fusion UI state
            selectedForFusion.clear()

            player.sendMessage(Component.text("Fusion successful! You obtained a ${upgradedPet.rarity.displayName} ${upgradedPet.petType.defaultName} pet!", NamedTextColor.GREEN))
            return true
        }

        private inner class FusionSlotsRenderer : ViewComponent() {
            private val fusionSlots by lazy { pattern.getSlots('S') }
            private val targetPetSlot by lazy { pattern.getSlot('P') }
            private val fuseButtonSlot by lazy { pattern.getSlot('F') }

            override fun onRebuild(view: PlayerView, player: Player) {
                setupFusionSlots(view, player)
                setupTargetPetSlot(view, player)
                setupFuseButton(view, player)
            }

            override fun onOpen(view: PlayerView, player: Player) {
                onRebuild(view, player)
            }

            private fun setupFusionSlots(view: PlayerView, player: Player) {
                for (i in 0 until 3) { // 3 fusion slots for sacrifice pets
                    val slotIndex = fusionSlots.getOrNull(i) ?: continue
                    val viewItem = ViewItem.bySlot(view, slotIndex)

                    if (i < selectedForFusion.size) {
                        // Show selected sacrifice pet
                        val contentsSlot = selectedForFusion[i]
                        val petItem = petPouch.contents[contentsSlot]
                        if (petItem != null) {
                            val pet = Pet.fromItemStack(petItem)
                            val displayName = pet?.displayName ?: "Unknown Pet"

                            viewItem.material<ModifiableItem?>(petItem.material())
                                ?.item<ModifiableItem?>(petItem.withGlowing(true))
                                ?.name<ModifiableItem?>("<red>$displayName")
                                ?.lore<ModifiableItem?>(buildSacrificeSlotLore(petItem))
                                ?.interaction<ModifiableItem> {
                                    // Click to remove from fusion
                                    selectedForFusion.removeAt(i)
                                    refreshFusionView(it.view)
                                    player.sendMessage(Component.text("Pet removed from sacrifice!", NamedTextColor.YELLOW))
                                }
                        }
                    } else {
                        // Empty sacrifice slot
                        val glassPane = ItemStack.builder(Material.RED_STAINED_GLASS_PANE)
                            .customName(Component.text("§cEmpty Sacrifice Slot ${i + 1}"))
                            .build()

                        viewItem.material<ModifiableItem?>(Material.RED_STAINED_GLASS_PANE)
                            ?.item<ModifiableItem?>(glassPane)
                            ?.name<ModifiableItem?>("<gray>Empty Sacrifice Slot ${i + 1}")
                            ?.lore<ModifiableItem?>(listOf("<yellow>Select pets from below to sacrifice"))
                            ?.cancelClicking<ModifiableItem?>()
                    }
                }
            }

            private fun setupTargetPetSlot(view: PlayerView, player: Player) {
                val viewItem = ViewItem.bySlot(view, targetPetSlot)
                val targetItemStack = petPouch.contents[this@PetFuseMenuProvider.targetPetSlot]

                if (targetItemStack != null) {
                    val displayName = targetPet.displayName
                    val requiredLevel = getRequiredLevel(targetPet.rarity)
                    val canFuseLevel = targetPet.level >= requiredLevel
                    val nextRarity = getNextRarity(targetPet.rarity)

                    viewItem.material<ModifiableItem?>(targetItemStack.material())
                        ?.item<ModifiableItem?>(targetItemStack.withGlowing(true))
                        ?.name<ModifiableItem?>(("<green>$displayName").let { PlainTextComponentSerializer.plainText().serialize(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(it)) })
                        ?.lore<ModifiableItem?>(listOf(
                            "<gray>Level: <yellow>${targetPet.level}",
                            "<gray>Current Rarity: ${targetPet.rarity.displayName}",
                            "<gray>Required Level: <yellow>$requiredLevel",
                            if (canFuseLevel) "<green>✓ Ready to fuse" else "<red>✗ Level too low",
                            "",
                            "<gray>After Fusion:",
                            "<gray>New Rarity: ${nextRarity?.displayName ?: "Max Level"}",
                            "<gray>New Level: <yellow>Highest of sacrificed pets",
                            "",
                            "<blue>This pet will be upgraded!"
                        ))
                        ?.cancelClicking<ModifiableItem?>()
                } else {
                    // Fallback if target pet not found
                    viewItem.material<ModifiableItem?>(Material.BARRIER)
                        ?.name<ModifiableItem?>("<red>Target Pet Not Found")
                        ?.cancelClicking<ModifiableItem?>()
                }
            }

            private fun setupFuseButton(view: PlayerView, player: Player) {
                val viewItem = ViewItem.bySlot(view, fuseButtonSlot)
                val canPerformFusion = canFuse()

                if (canPerformFusion) {
                    val selectedPets = selectedForFusion.mapNotNull { slot ->
                        petPouch.contents[slot]?.let { Pet.fromItemStack(it) }
                    }
                    val highestLevel = selectedPets.maxOf { it.level }
                    val nextRarity = getNextRarity(targetPet.rarity)

                    viewItem.material<ModifiableItem?>(Material.NETHER_STAR)
                        ?.name<ModifiableItem?>("<green>Fuse Pet!")
                        ?.lore<ModifiableItem?>(listOf(
                            "<gray>Sacrifice ${selectedPets.size} ${targetPet.petType.defaultName} pets",
                            "<gray>Upgrade: ${targetPet.rarity.displayName} → ${nextRarity?.displayName}",
                            "<gray>New Level: <yellow>$highestLevel",
                            "",
                            "<green>Click to fuse!"
                        ))
                        ?.interaction<ModifiableItem> {
                            if (performFusion(player)) {
                                player.sendMessage(Component.text("Pet successfully upgraded to ${nextRarity?.displayName ?: "higher rarity"}!", NamedTextColor.GREEN))
                                // Close the fuse menu and reopen the main pet pouch
                                PetPouchMenuProvider(petPouch).open(player)
                            } else {
                                player.sendMessage(Component.text("Failed to fuse pet!", NamedTextColor.RED))
                            }
                        }
                } else {
                    val requiredLevel = getRequiredLevel(targetPet.rarity)
                    val isTargetReady = targetPet.level >= requiredLevel

                    viewItem.material<ModifiableItem?>(Material.BARRIER)
                        ?.name<ModifiableItem?>("<red>Cannot Fuse")
                        ?.lore<ModifiableItem?>(listOf(
                            "<gray>Requirements:",
                            if (!isTargetReady) "<red>✗ Target pet must be level $requiredLevel" else "<green>✓ Target pet ready",
                            if (selectedForFusion.size != 3) "<red>✗ Need 3 sacrifice pets (${selectedForFusion.size}/3)" else "<green>✓ 3 sacrifice pets selected",
                            "<gray>• Sacrifice pets must be level $requiredLevel+"
                        ))
                        ?.cancelClicking<ModifiableItem?>()
                }
            }

            private fun buildSacrificeSlotLore(item: ItemStack): List<String> {
                val lore = mutableListOf<String>()
                val pet = Pet.fromItemStack(item)

                if (pet != null) {
                    lore.add("<gray>Level: <yellow>${pet.level}")
                    lore.add("<gray>Rarity: ${pet.rarity.displayName}")
                    lore.add("<red>Will be sacrificed")
                }

                lore.add("")
                lore.add("<red>Click to remove from sacrifice")

                return lore
            }
        }

        private inner class FusePetItemRenderer : ItemRenderer<ItemStack> {
            override fun render(itemStack: ItemStack): PrebuiltItem {
                if (itemStack == ItemStack.AIR || Pet.fromItemStack(itemStack) == null) {
                    return PrebuiltItem.of(itemStack)
                }

                val pet = Pet.fromItemStack(itemStack)
                val isSelected = selectedForFusion.any { slot ->
                    val slotPet = petPouch.contents[slot]?.let { Pet.fromItemStack(it) }
                    slotPet?.uid == pet?.uid
                }

                // Add action instructions to lore
                val existingLore = itemStack.get(DataComponents.LORE) ?: emptyList()
                val newLore = existingLore.toMutableList()

                if (pet != null) {
                    val requiredLevel = getRequiredLevel(targetPet.rarity) // Use target pet's rarity for requirement
                    val canSacrifice = pet.level >= requiredLevel

                    newLore.add(Component.text(""))
                    newLore.add(MiniMessage.miniMessage()
                        .deserialize("<gray>Required Level: <yellow>$requiredLevel"))

                    if (canSacrifice) {
                        newLore.add(MiniMessage.miniMessage()
                            .deserialize("<green>✓ Can be sacrificed"))
                        newLore.add(MiniMessage.miniMessage()
                            .deserialize("<yellow>Click to ${if (isSelected) "remove from" else "add to"} sacrifice"))
                    } else {
                        newLore.add(MiniMessage.miniMessage()
                            .deserialize("<red>✗ Level too low"))
                    }
                }

                val enhancedItemStack = itemStack.builder().lore(newLore).build()

                return PrebuiltItem.of(if (isSelected) enhancedItemStack.withGlowing(true) else enhancedItemStack).apply {
                    interaction<ModifiableItem> {
                        val clickedPet = Pet.fromItemStack(itemStack)
                        if (clickedPet != null) {
                            val contentsSlot = petPouch.contents.entries.find { entry ->
                                val entryPet = Pet.fromItemStack(entry.value)
                                entryPet?.uid == clickedPet.uid
                            }?.key

                            if (contentsSlot != null) {
                                val requiredLevel = getRequiredLevel(targetPet.rarity)

                                if (clickedPet.level < requiredLevel) {
                                    it.player.sendMessage(Component.text("Pet must be at least level $requiredLevel to sacrifice!", NamedTextColor.RED))
                                    return@interaction
                                }

                                if (selectedForFusion.contains(contentsSlot)) {
                                    // Remove from sacrifice
                                    selectedForFusion.remove(contentsSlot)
                                    it.player.sendMessage(Component.text("Pet removed from sacrifice!", NamedTextColor.YELLOW))
                                } else if (selectedForFusion.size < 3) {
                                    // Add to sacrifice
                                    selectedForFusion.add(contentsSlot)
                                    it.player.sendMessage(Component.text("Pet added to sacrifice! (${selectedForFusion.size}/3)", NamedTextColor.GREEN))
                                } else {
                                    it.player.sendMessage(Component.text("Sacrifice slots are full! (3/3)", NamedTextColor.RED))
                                }

                                refreshFusionView(it.view)
                            }
                        }
                        null
                    }
                }
            }
        }

        private inner class PaginationActionButtons(
            private val pagination: ViewPagination<ItemStack>,
            private val pattern: ViewPattern,
            private val parentProvider: PetFuseMenuProvider
        ) : ViewComponent() {
            init {
                addChild(pagination)
            }

            override fun onRebuild(view: PlayerView, player: Player) {
                setupNavigationButtons(view, player)
            }

            private fun setupNavigationButtons(view: PlayerView, player: Player) {
                setupPreviousButton(view, player)
                setupNextButton(view, player)
            }

            private fun setupPreviousButton(view: PlayerView, player: Player) {
                ViewItem.bySlot(view, pattern.getSlot('^'))
                    .material<ModifiableItem?>(Material.ARROW)
                    ?.name<ModifiableItem?>("<green>Previous")
                    ?.interaction<ModifiableItem> {
                        // Use the correct method to go to previous page
                        val currentPage = pagination.currentPage
                        if (currentPage > 1) {
                            pagination.toPage(view, currentPage - 1)
                        }
                    }
            }

            private fun setupNextButton(view: PlayerView, player: Player) {
                ViewItem.bySlot(view, pattern.getSlot('v'))
                    .material<ModifiableItem?>(Material.ARROW)
                    ?.name<ModifiableItem?>("<green>Next")
                    ?.interaction<ModifiableItem> {
                        // Use the correct method to go to next page
                        val currentPage = pagination.currentPage
                        val maxPages = pagination.totalPages
                        if (currentPage < maxPages) {
                            pagination.toPage(view, currentPage + 1)
                        }
                    }
            }
        }

        private class PaginationBorder : ViewComponent() {
            override fun onOpen(view: PlayerView, player: Player) {
                val topSlots = view.type.topSlots
                for (viewItem in ViewItem.bySlotList(view, topSlots)) {
                    viewItem?.material<ModifiableItem?>(Material.BLUE_STAINED_GLASS_PANE)
                        ?.displayName<ModifiableItem?>(" ")
                }
            }
        }
    }
}
