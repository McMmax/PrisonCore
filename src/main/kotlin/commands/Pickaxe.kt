package twizzy.tech.commands

import eu.koboo.minestom.stomui.api.PlayerView
import eu.koboo.minestom.stomui.api.ViewBuilder
import eu.koboo.minestom.stomui.api.ViewType
import eu.koboo.minestom.stomui.api.component.ViewProvider
import eu.koboo.minestom.stomui.api.interaction.Interaction
import eu.koboo.minestom.stomui.api.interaction.Interactions
import eu.koboo.minestom.stomui.api.interaction.ViewAction
import eu.koboo.minestom.stomui.api.item.ModifiableItem
import eu.koboo.minestom.stomui.api.item.ViewItem
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.component.DataComponents
import net.minestom.server.entity.Player
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import revxrsal.commands.annotation.Command
import revxrsal.commands.annotation.Subcommand
import twizzy.tech.game.items.pickaxe.Booster
import twizzy.tech.game.items.pickaxe.Enchant
import twizzy.tech.game.items.pickaxe.Pickaxe
import twizzy.tech.player.PlayerData
import twizzy.tech.util.YamlFactory
import twizzy.tech.viewRegistry
import java.math.BigDecimal
import java.util.*

@Command("pickaxe")
class Pickaxe {
    private val materialCache = mutableMapOf<String, Material>()
    private val enchantCache = mutableMapOf<Boolean, List<Enchant>>()

    @Command("pickaxe")
    suspend fun openInterface(actor: Player) {
        val pickaxe = Pickaxe.fromItemStack(actor.itemInMainHand)
        val mainMenu = createMainMenu(pickaxe)
        mainMenu.open(actor)
    }

    @Subcommand("get")
    fun getPickaxe(actor: Player) {
        val pickaxe = Pickaxe(
            blocksMined = 0L,
            level = 1,
            experience = 0,
            owner = actor.uuid.toString()
        )
        actor.inventory.addItemStack(pickaxe.toItemStack())
        actor.sendMessage(Component.text("You have been given a pickaxe!", NamedTextColor.GREEN))
    }

    // Main Menu
    private fun createMainMenu(pickaxe: Pickaxe?): ViewProvider {
        return object : ViewProvider(viewRegistry, ViewBuilder.of(ViewType.SIZE_3_X_9)) {
            override fun modifyBuilder(viewBuilder: ViewBuilder, player: Player) {
                viewBuilder.title("Pickaxe Info")
            }

            override fun onOpen(view: PlayerView, player: Player) {
                // Fill with glass panes
                for (slot in view.type.topSlots) {
                    if (slot !in listOf(11, 13, 15)) { // Skip button slots
                        ViewItem.bySlot(view, slot)
                            .material<ModifiableItem>(Material.GRAY_STAINED_GLASS_PANE)
                            .displayName<ModifiableItem>(" ")
                            .cancelClicking<ModifiableItem>()
                    }
                }

                // Boosters button
                ViewItem.bySlot(view, 11) // Row 2, Slot 3
                    .material<ModifiableItem>(Material.VEX_ARMOR_TRIM_SMITHING_TEMPLATE)
                    .displayName<ModifiableItem>("<light_purple><bold>Boosters</bold></light_purple>")
                    .lore<ModifiableItem>(
                        "<gold>Click to view boosters</gold>",
                        "<gray>Active: <green>${pickaxe?.activeBoosters()?.size ?: 0}</green></gray>"
                    )
                    .interaction<ModifiableItem>(Interactions.open(createBoostersMenu(pickaxe)))

                // Level info button (middle)
                ViewItem.bySlot(view, 13) // Row 2, Slot 5
                    .material<ModifiableItem>(Material.EXPERIENCE_BOTTLE)
                    .displayName<ModifiableItem>("<aqua><bold>Pickaxe Info</bold></aqua>")
                    .lore<ModifiableItem>(
                        "<gray>Level: <green>${pickaxe?.level ?: 1}</green></gray>",
                        "<gray>EXP: <yellow>${pickaxe?.experience ?: 0}</yellow><gray>/<yellow>1000</yellow></gray>",
                        "<gray>Blocks Mined: <green>${pickaxe?.blocksMined ?: 0}</green></gray>"
                    )
                    .cancelClicking<ModifiableItem>()

                // Enchants button
                ViewItem.bySlot(view, 15) // Row 2, Slot 7
                    .material<ModifiableItem>(Material.ENCHANTED_BOOK)
                    .displayName<ModifiableItem>("<aqua><bold>Enchants</bold></aqua>")
                    .lore<ModifiableItem>(
                        "<light_purple>Click to view enchants</light_purple>",
                        "<gray>Total: <green>${pickaxe?.enchants?.size ?: 0}</green></gray>"
                    )
                    .interaction<ModifiableItem>(Interactions.open(createEnchantsMenu(pickaxe)))
            }
        }
    }

    // Boosters Menu
    private fun createBoostersMenu(pickaxe: Pickaxe?): ViewProvider {
        return object : ViewProvider(viewRegistry, ViewBuilder.of(ViewType.SIZE_6_X_9)) {
            override fun modifyBuilder(viewBuilder: ViewBuilder, player: Player) {
                viewBuilder.title("Pickaxe Boosters")
            }

            override fun onOpen(view: PlayerView, player: Player) {
                // Initialize the booster registry
                Booster.Companion.BoosterRegistry.initialize()

                // Fill with glass panes
                for (slot in view.type.topSlots) {
                    ViewItem.bySlot(view, slot)
                        .material<ModifiableItem>(Material.GRAY_STAINED_GLASS_PANE)
                        .displayName<ModifiableItem>(" ")
                        .cancelClicking<ModifiableItem>()
                }

                // Back button
                ViewItem.bySlot(view, 45) // Row 6, Slot 1
                    .material<ModifiableItem>(Material.BARRIER)
                    .displayName<ModifiableItem>("<red>← Back</red>")
                    .lore<ModifiableItem>("<gray>Click to go back to the previous menu</gray>")
                    .interaction<ModifiableItem>(Interactions.backToLastView())

                // Active Boosters Section
                ViewItem.bySlot(view, 3) // Row 1, Slot 4
                    .material<ModifiableItem>(Material.BLACK_STAINED_GLASS_PANE)
                    .displayName<ModifiableItem>("<light_purple><bold>Active Boosters</bold></light_purple>")
                    .lore<ModifiableItem>("<gray>Click to toggle visibility</gray>")
                    .cancelClicking<ModifiableItem>()

                ViewItem.bySlot(view, 5) // Row 1, Slot 6
                    .material<ModifiableItem>(Material.BOOK)
                    .displayName<ModifiableItem>("<yellow><bold>View All Boosters</bold></yellow>")
                    .lore<ModifiableItem>("<gray>Click to view all available booster types</gray>")
                    .interaction<ModifiableItem>(Interactions.open(createAllBoostersMenu(pickaxe)))

                val activeBoosters = pickaxe?.activeBoosters() ?: emptyList()
                if (activeBoosters.isNotEmpty()) {
                    activeBoosters.take(18).forEachIndexed { i, booster ->
                        val row = 2 + (i / 9)
                        val slot = (row - 1) * 9 + (i % 9)
                        ViewItem.bySlot(view, slot)
                            .material<ModifiableItem>(Material.POTION)
                            .displayName<ModifiableItem>("<light_purple><bold>${booster.javaClass.simpleName}</bold></light_purple>")
                            .lore<ModifiableItem>(
                                "<gray>${booster.getDisplayString()}</gray>",
                                "<dark_gray>Click for more details</dark_gray>"
                            )
                            .interaction<ModifiableItem>(Interactions.open(createBoosterDetailMenu(booster)))
                    }
                } else {
                    ViewItem.bySlot(view, 22) // Row 3, Slot 5
                        .material<ModifiableItem>(Material.BARRIER)
                        .displayName<ModifiableItem>("<red><bold>No Active Boosters</bold></red>")
                        .lore<ModifiableItem>("<gray>You don't have any active boosters.</gray>")
                        .cancelClicking<ModifiableItem>()
                }

                // Available Boosters Section
                ViewItem.bySlot(view, 30) // Row 4, Slot 4
                    .material<ModifiableItem>(Material.BLACK_STAINED_GLASS_PANE)
                    .displayName<ModifiableItem>("<green><bold>Available Booster Types</bold></green>")
                    .lore<ModifiableItem>("<gray>Click to toggle visibility</gray>")
                    .cancelClicking<ModifiableItem>()

                val availableTypes = Booster.Companion.BoosterRegistry.getAllBoosterInfo().take(9)
                availableTypes.forEachIndexed { i, boosterInfo ->
                    val slot = 36 + i // Row 5, slots 1-9
                    ViewItem.bySlot(view, slot)
                        .material<ModifiableItem>(Material.PAPER)
                        .displayName<ModifiableItem>("<green><bold>${boosterInfo.displayName}</bold></green>")
                        .lore<ModifiableItem>(
                            "<gray>${boosterInfo.description}</gray>",
                            "<gray>Type: <yellow>${boosterInfo.className}</yellow></gray>",
                            "<dark_gray>This is an available booster type</dark_gray>"
                        )
                        .cancelClicking<ModifiableItem>()
                }
            }
        }
    }

    // All Boosters Menu
    private fun createAllBoostersMenu(pickaxe: Pickaxe?): ViewProvider {
        return object : ViewProvider(viewRegistry, ViewBuilder.of(ViewType.SIZE_4_X_9)) {
            override fun modifyBuilder(viewBuilder: ViewBuilder, player: Player) {
                viewBuilder.title("All Booster Types")
            }

            override fun onOpen(view: PlayerView, player: Player) {
                // Fill with glass panes
                for (slot in view.type.topSlots) {
                    ViewItem.bySlot(view, slot)
                        .material<ModifiableItem>(Material.GRAY_STAINED_GLASS_PANE)
                        .displayName<ModifiableItem>(" ")
                        .cancelClicking<ModifiableItem>()
                }

                // Back button
                ViewItem.bySlot(view, 27) // Row 4, Slot 1
                    .material<ModifiableItem>(Material.BARRIER)
                    .displayName<ModifiableItem>("<red>← Back</red>")
                    .lore<ModifiableItem>("<gray>Click to go back to the previous menu</gray>")
                    .interaction<ModifiableItem>(Interactions.backToLastView())

                val allBoosterTypes = Booster.Companion.BoosterRegistry.getAllBoosterInfo()
                if (allBoosterTypes.isNotEmpty()) {
                    allBoosterTypes.forEachIndexed { i, boosterInfo ->
                        val row = 1 + (i / 9)
                        val slot = (row - 1) * 9 + (i % 9)
                        ViewItem.bySlot(view, slot)
                            .material<ModifiableItem>(Material.PAPER)
                            .displayName<ModifiableItem>("<green><bold>${boosterInfo.displayName}</bold></green>")
                            .lore<ModifiableItem>(
                                "<gray>${boosterInfo.description}</gray>",
                                "<gray>Type: <yellow>${boosterInfo.className}</yellow></gray>",
                                "<dark_gray>This is an available booster type</dark_gray>"
                            )
                            .cancelClicking<ModifiableItem>()
                    }
                } else {
                    ViewItem.bySlot(view, 13) // Row 2, Slot 5
                        .material<ModifiableItem>(Material.BARRIER)
                        .displayName<ModifiableItem>("<red><bold>No Booster Types Available</bold></red>")
                        .lore<ModifiableItem>("<gray>There are no booster types available in the system.</gray>")
                        .cancelClicking<ModifiableItem>()
                }
            }
        }
    }

    // Booster Detail Menu
    private fun createBoosterDetailMenu(booster: Booster): ViewProvider {
        return object : ViewProvider(viewRegistry, ViewBuilder.of(ViewType.SIZE_3_X_9)) {
            override fun modifyBuilder(viewBuilder: ViewBuilder, player: Player) {
                viewBuilder.title("Booster Details")
            }

            override fun onOpen(view: PlayerView, player: Player) {
                // Fill with glass panes
                for (slot in view.type.topSlots) {
                    if (slot != 13) { // Skip center slot
                        ViewItem.bySlot(view, slot)
                            .material<ModifiableItem>(Material.GRAY_STAINED_GLASS_PANE)
                            .displayName<ModifiableItem>(" ")
                            .cancelClicking<ModifiableItem>()
                    }
                }

                // Back button
                ViewItem.bySlot(view, 18) // Row 3, Slot 1
                    .material<ModifiableItem>(Material.BARRIER)
                    .displayName<ModifiableItem>("<red>← Back</red>")
                    .lore<ModifiableItem>("<gray>Click to go back to the previous menu</gray>")
                    .interaction<ModifiableItem>(Interactions.backToLastView())

                // Booster details
                ViewItem.bySlot(view, 13) // Row 2, Slot 5
                    .material<ModifiableItem>(Material.POTION)
                    .displayName<ModifiableItem>("<light_purple><bold>${booster.javaClass.simpleName}</bold></light_purple>")
                    .lore<ModifiableItem>(
                        "<gray>${booster.getDisplayString()}</gray>",
                        "<gray>Type: <yellow>${booster.javaClass.simpleName}</yellow></gray>",
                        "<gray>Status: <green>Active</green></gray>"
                    )
                    .cancelClicking<ModifiableItem>()
            }
        }
    }

    // Enchants Menu
    private fun createEnchantsMenu(pickaxe: Pickaxe?): ViewProvider {
        return object : ViewProvider(viewRegistry, ViewBuilder.of(ViewType.SIZE_3_X_9)) {
            override fun modifyBuilder(viewBuilder: ViewBuilder, player: Player) {
                viewBuilder.title("Pickaxe Enchants")
            }

            override fun onOpen(view: PlayerView, player: Player) {
                // Fill with glass panes
                for (slot in view.type.topSlots) {
                    if (slot !in listOf(12, 14)) { // Skip button slots
                        ViewItem.bySlot(view, slot)
                            .material<ModifiableItem>(Material.GRAY_STAINED_GLASS_PANE)
                            .displayName<ModifiableItem>(" ")
                            .cancelClicking<ModifiableItem>()
                    }
                }

                // Back button
                ViewItem.bySlot(view, 18) // Row 3, Slot 1
                    .material<ModifiableItem>(Material.BARRIER)
                    .displayName<ModifiableItem>("<red>← Back</red>")
                    .lore<ModifiableItem>("<gray>Click to go back to the previous menu</gray>")
                    .interaction<ModifiableItem>(Interactions.backToLastView())

                // Token enchants button
                ViewItem.bySlot(view, 12) // Row 2, Slot 4
                    .material<ModifiableItem>(Material.EMERALD)
                    .displayName<ModifiableItem>("<green><bold>Token Enchants</bold></green>")
                    .lore<ModifiableItem>("<gray>Click to view token enchants</gray>")
                    .interaction<ModifiableItem>(Interactions.open(createEnchantListMenu(pickaxe, true)))

                // Soul enchants button
                ViewItem.bySlot(view, 14) // Row 2, Slot 6
                    .material<ModifiableItem>(Material.AMETHYST_SHARD)
                    .displayName<ModifiableItem>("<light_purple><bold>Soul Enchants</bold></light_purple>")
                    .lore<ModifiableItem>("<gray>Click to view soul enchants</gray>")
                    .interaction<ModifiableItem>(Interactions.open(createEnchantListMenu(pickaxe, false)))
            }
        }
    }

    // Enchant List Menu
    private fun createEnchantListMenu(pickaxe: Pickaxe?, isToken: Boolean): ViewProvider {
        return object : ViewProvider(viewRegistry, ViewBuilder.of(ViewType.SIZE_4_X_9)) {
            override fun modifyBuilder(viewBuilder: ViewBuilder, player: Player) {
                viewBuilder.title("${if (isToken) "Token" else "Soul"} Enchants")
            }

            override fun onOpen(view: PlayerView, player: Player) {
                // Fill with glass panes
                for (slot in view.type.topSlots) {
                    ViewItem.bySlot(view, slot)
                        .material<ModifiableItem>(Material.GRAY_STAINED_GLASS_PANE)
                        .displayName<ModifiableItem>(" ")
                        .cancelClicking<ModifiableItem>()
                }

                // Back button
                ViewItem.bySlot(view, 27) // Row 4, Slot 1
                    .material<ModifiableItem>(Material.BARRIER)
                    .displayName<ModifiableItem>("<red>← Back</red>")
                    .lore<ModifiableItem>("<gray>Click to go back to the previous menu</gray>")
                    .interaction<ModifiableItem>(Interactions.backToLastView())

                val enchants = getSortedEnchants(pickaxe, isToken)
                if (enchants.isEmpty()) {
                    ViewItem.bySlot(view, 13) // Row 2, Slot 5
                        .material<ModifiableItem>(Material.BARRIER)
                        .displayName<ModifiableItem>("<red><bold>No ${if (isToken) "Token" else "Soul"} Enchants</bold></red>")
                        .lore<ModifiableItem>("<gray>No enchants available in the system.</gray>")
                        .cancelClicking<ModifiableItem>()
                } else {
                    enchants.forEachIndexed { i, enchantInfo ->
                        val row = 1 + (i / 9)
                        val slot = (row - 1) * 9 + (i % 9)
                        val (enchant, isOwned, canApply, isLocked) = enchantInfo

                        val nameColor = if (isOwned) "<aqua><bold>" else if (canApply) "<green><bold>" else "<gray><bold>"
                        val status = when {
                            isOwned -> "<gray>[Level ${enchant.level}]</gray>"
                            canApply -> "<gray>[Unlocked]</gray>"
                            else -> "<gray>[Locked]</gray>"
                        }

                        val lore = createEnchantLore(enchant, isOwned, canApply, false).toMutableList()
                        if (isOwned) {
                            lore.add("<dark_gray>Shift-click to toggle enabled/disabled</dark_gray>")
                        }

                        ViewItem.bySlot(view, slot)
                            .material<ModifiableItem>(resolveMaterial(enchant.getDisplayItem()))
                            .displayName<ModifiableItem>("$nameColor${enchant.name}</bold> $status")
                            .lore<ModifiableItem>(*lore.toTypedArray())
                            .glint<ModifiableItem>(isOwned && enchant.isEnabled())
                            .interaction<ModifiableItem>(Interaction { action: ViewAction ->
                                if (isLocked) return@Interaction

                                // Open detail menu (removed shift-click detection since stomui doesn't expose click type)
                                val detailMenu = createEnchantDetailMenu(enchantInfo, player)
                                detailMenu.open(player)
                            })
                    }
                }
            }
        }
    }

    // Enchant Detail Menu
    private fun createEnchantDetailMenu(enchantInfo: EnchantInfo, player: Player): ViewProvider {
        return object : ViewProvider(viewRegistry, ViewBuilder.of(ViewType.SIZE_3_X_9)) {
            private var currentEnchantInfo = enchantInfo

            override fun modifyBuilder(viewBuilder: ViewBuilder, player: Player) {
                viewBuilder.title("Enchant Details")
            }

            override fun onOpen(view: PlayerView, player: Player) {
                setupEnchantDetailMenu(view, player, currentEnchantInfo)
            }

            override fun onRebuild(view: PlayerView, player: Player) {
                setupEnchantDetailMenu(view, player, currentEnchantInfo)
            }

            private fun setupEnchantDetailMenu(view: PlayerView, player: Player, info: EnchantInfo) {
                // Fill with glass panes
                for (slot in view.type.topSlots) {
                    ViewItem.bySlot(view, slot)
                        .material<ModifiableItem>(Material.GRAY_STAINED_GLASS_PANE)
                        .displayName<ModifiableItem>(" ")
                        .cancelClicking<ModifiableItem>()
                }

                // Back button
                ViewItem.bySlot(view, 18) // Row 3, Slot 1
                    .material<ModifiableItem>(Material.BARRIER)
                    .displayName<ModifiableItem>("<red>← Back</red>")
                    .lore<ModifiableItem>("<gray>Click to go back to the previous menu</gray>")
                    .interaction<ModifiableItem>(Interactions.backToLastView())

                // Main enchant display
                ViewItem.bySlot(view, 4) // Row 1, Slot 5
                    .material<ModifiableItem>(resolveMaterial(info.enchant.getDisplayItem()))
                    .displayName<ModifiableItem>("<aqua><bold>${info.enchant.name}</bold></aqua>")
                    .lore<ModifiableItem>(*createEnchantLore(info.enchant, info.isOwned, info.canApply, true).toTypedArray())
                    .glint<ModifiableItem>(info.isOwned && info.enchant.isEnabled())
                    .cancelClicking<ModifiableItem>()

                val canUpgrade = info.isOwned && info.enchant.canUpgrade()
                val canPurchase = !info.isOwned && info.canApply

                if (canUpgrade || canPurchase) {
                    // Add upgrade controls
                    addUpgradeControls(view, player, info)
                } else if (info.isOwned) {
                    ViewItem.bySlot(view, 13) // Row 2, Slot 5
                        .material<ModifiableItem>(Material.BARRIER)
                        .displayName<ModifiableItem>("<red><bold>MAX LEVEL</bold></red>")
                        .lore<ModifiableItem>("<gray>Level: <yellow>${info.enchant.level}/${info.enchant.getMaxLevel()}</yellow></gray>")
                        .cancelClicking<ModifiableItem>()
                } else {
                    ViewItem.bySlot(view, 13) // Row 2, Slot 5
                        .material<ModifiableItem>(Material.BARRIER)
                        .displayName<ModifiableItem>("<red><bold>LOCKED</bold></red>")
                        .lore<ModifiableItem>("<gray>Requires Level ${info.enchant.getRequiredPickaxeLevel()}</gray>")
                        .cancelClicking<ModifiableItem>()
                }

                // Enchant removal option
                if (info.isOwned) {
                    ViewItem.bySlot(view, 26) // Row 3, Slot 8
                        .material<ModifiableItem>(Material.FIRE_CHARGE)
                        .displayName<ModifiableItem>("<dark_red><bold>Remove Enchant</bold></dark_red>")
                        .lore<ModifiableItem>("<gray>Permanently remove this enchant</gray>")
                        .interaction<ModifiableItem>(Interaction { _: ViewAction ->
                            handleEnchantRemoval(player, info.enchant)
                        })
                }
            }

            private fun addUpgradeControls(view: PlayerView, player: Player, info: EnchantInfo) {
                val amounts = listOf(1, 5, 10)

                // Decrease buttons (reversed order: -10, -5, -1)
                amounts.reversed().forEachIndexed { i, amount ->
                    ViewItem.bySlot(view, 9 + i) // Row 2, slots 1-3
                        .material<ModifiableItem>(Material.RED_STAINED_GLASS_PANE)
                        .displayName<ModifiableItem>("<red>-$amount</red>")
                        .lore<ModifiableItem>("<gray>Decrease amount</gray>")
                        .amount<ModifiableItem>(amount)
                        .interaction<ModifiableItem>(Interaction { _: ViewAction ->
                            val newAmount = (info.upgradeAmount - amount).coerceAtLeast(1)
                            currentEnchantInfo = info.withUpgradeAmount(newAmount)
                            view.executeRebuild()
                        })
                }

                // Upgrade button
                ViewItem.bySlot(view, 13) // Row 2, Slot 5
                    .material<ModifiableItem>(Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE)
                    .displayName<ModifiableItem>("<green><bold>${if (info.isOwned) "Upgrade" else "Purchase"}</bold></green>")
                    .lore<ModifiableItem>(
                        "<gray>Amount: <yellow>${info.upgradeAmount}</yellow></gray>",
                        "<gray>Total: <green>${calculateTotalCost(info)}</green></gray>",
                        "<gray>Balance: <yellow>${getPlayerBalance(player, info.enchant)}</yellow></gray>"
                    )
                    .amount<ModifiableItem>(info.upgradeAmount)
                    .interaction<ModifiableItem>(Interaction { _: ViewAction ->
                        handleUpgrade(player, info)
                    })

                // Increase buttons (normal order: +1, +5, +10)
                amounts.forEachIndexed { i, amount ->
                    ViewItem.bySlot(view, 15 + i) // Row 2, slots 7-9
                        .material<ModifiableItem>(Material.GREEN_STAINED_GLASS_PANE)
                        .displayName<ModifiableItem>("<green>+$amount</green>")
                        .lore<ModifiableItem>("<gray>Increase amount</gray>")
                        .amount<ModifiableItem>(amount)
                        .interaction<ModifiableItem>(Interaction { _: ViewAction ->
                            val maxPossible = if (info.isOwned) {
                                info.enchant.getMaxLevel() - info.enchant.level
                            } else {
                                info.enchant.getMaxLevel()
                            }
                            val newAmount = (info.upgradeAmount + amount).coerceAtMost(maxPossible.coerceAtLeast(1))
                            currentEnchantInfo = info.withUpgradeAmount(newAmount)
                            view.executeRebuild()
                        })
                }
            }
        }
    }

    // Helper methods
    private fun getSortedEnchants(pickaxe: Pickaxe?, isToken: Boolean): List<EnchantInfo> {
        val enchants = getCachedEnchants(isToken)
        val playerEnchants = pickaxe?.enchants ?: emptyList()
        val pickaxeLevel = pickaxe?.level ?: 1

        return enchants.map { enchant ->
            val owned = playerEnchants.find { it.name.equals(enchant.name, true) }
            val isOwned = owned != null
            val displayEnchant = owned ?: enchant
            val canApply = enchant.canApplyToPickaxe(pickaxeLevel)

            EnchantInfo(
                enchant = displayEnchant,
                isOwned = isOwned,
                canApply = canApply,
                isLocked = !canApply && !isOwned
            )
        }.sortedWith(compareBy(
            { it.isLocked },  // Locked last
            { -it.enchant.level }, // Higher levels first
            { it.enchant.name }    // Alphabetical
        ))
    }

    private fun getCachedEnchants(isToken: Boolean): List<Enchant> {
        return enchantCache.getOrPut(isToken) {
            Pickaxe.Companion.EnchantRegistry.initialize()
            Pickaxe.Companion.EnchantRegistry.getAllRegisteredTypes()
                .mapNotNull { type ->
                    Pickaxe.Companion.EnchantRegistry.createEnchant(type, 1)?.takeIf {
                        isToken == it.isTokenEnchant()
                    }
                }
        }
    }

    private fun resolveMaterial(displayItem: String): Material {
        return materialCache.getOrPut(displayItem) {
            when {
                displayItem.contains(":") -> {
                    // Handle namespaced keys like "minecraft:hopper"
                    Material.fromKey(displayItem.lowercase()) ?: Material.BOOK
                }
                else -> {
                    // Handle simple material names like "HOPPER" or "hopper"
                    val materialName = displayItem.lowercase()
                    Material.fromKey(materialName)
                        ?: Material.fromKey("minecraft:$materialName")
                        ?: Material.BOOK
                }
            }
        }
    }

    private fun createEnchantLore(
        enchant: Enchant,
        isOwned: Boolean,
        canApply: Boolean,
        showStatus: Boolean
    ): List<String> {
        val lore = mutableListOf<String>()
        val currency = if (enchant.isTokenEnchant()) "Tokens" else "Souls"

        if (showStatus) {
            lore.add(when {
                isOwned -> "<green>✓ OWNED</green>"
                canApply -> "<yellow>✓ UNLOCKED</yellow>"
                else -> "<red>✗ LOCKED</red>"
            })
        }

        // Clean the display string of legacy color codes before embedding in MiniMessage
        val cleanDisplayString = enchant.getDisplayString()
            .replace("§0", "").replace("§1", "").replace("§2", "").replace("§3", "")
            .replace("§4", "").replace("§5", "").replace("§6", "").replace("§7", "")
            .replace("§8", "").replace("§9", "").replace("§a", "").replace("§b", "")
            .replace("§c", "").replace("§d", "").replace("§e", "").replace("§f", "")
            .replace("§k", "").replace("§l", "").replace("§m", "").replace("§n", "")
            .replace("§o", "").replace("§r", "")

        lore.add("<gray>$cleanDisplayString</gray>")
        lore.add("<gray>Type: <yellow>${enchant.getEnchantType()}</yellow></gray>")
        lore.add("<gray>Currency: <yellow>$currency</yellow></gray>")
        lore.add("<gray>Required Level: <yellow>${enchant.getRequiredPickaxeLevel()}</yellow></gray>")

        if (enchant.getEnchantType() != "Effect") {
            lore.add("<gray>Activation: <yellow>${"%.2f".format(enchant.getActivationChance())}%</yellow></gray>")
        }

        when {
            isOwned && enchant.canUpgrade() ->
                lore.add("<gray>Upgrade: <green>${enchant.getUpgradePrice()} $currency</green></gray>")
            isOwned ->
                lore.add("<red><bold>MAX LEVEL</bold></red>")
            canApply ->
                lore.add("<gray>Purchase: <green>${enchant.getBasePrice()} $currency</green></gray>")
            else ->
                lore.add("<red><bold>Requires Level ${enchant.getRequiredPickaxeLevel()}</bold></red>")
        }

        if (canApply) lore.add("<dark_gray>Click for details</dark_gray>")
        return lore
    }

    private fun calculateTotalCost(info: EnchantInfo): BigDecimal {
        return if (info.isOwned) {
            // For upgrades: multiply upgrade price by the number of levels being upgraded
            info.enchant.getUpgradePrice().multiply(BigDecimal(info.upgradeAmount))
        } else {
            // For new enchants: calculate total cost for purchasing multiple levels
            var totalCost = BigDecimal.ZERO
            for (level in 1..info.upgradeAmount) {
                // Create a temporary enchant at each level to get the correct price
                val tempEnchant = Pickaxe.Companion.EnchantRegistry.createEnchant(
                    Pickaxe.Companion.EnchantRegistry.getTypeName(info.enchant),
                    level
                )
                if (tempEnchant != null) {
                    if (level == 1) {
                        totalCost = totalCost.add(tempEnchant.getBasePrice())
                    } else {
                        totalCost = totalCost.add(tempEnchant.getUpgradePrice())
                    }
                }
            }
            totalCost
        }
    }

    private fun getPlayerBalance(player: Player, enchant: Enchant): String {
        val playerData = PlayerData.getFromCache(player.uuid) ?: return "0"
        val balance = if (enchant.isTokenEnchant()) playerData.tokens else playerData.souls
        return twizzy.tech.util.CompactNotation.format(balance)
    }

    // Action handlers
    private fun handleUpgrade(player: Player, info: EnchantInfo) {
        // Get the current pickaxe from the player's main hand
        val currentPickaxe = Pickaxe.fromItemStack(player.itemInMainHand) ?: run {
            player.sendMessage(Component.text("You must be holding a pickaxe!", NamedTextColor.RED))
            return
        }

        // Get player data for currency checking
        val playerData = PlayerData.getFromCache(player.uuid) ?: run {
            player.sendMessage(Component.text("Could not load your data!", NamedTextColor.RED))
            return
        }

        // Check if player has enough currency
        val totalCost = calculateTotalCost(info)
        val currency = if (info.enchant.isTokenEnchant()) "Tokens" else "Souls"
        val playerBalance = if (info.enchant.isTokenEnchant()) playerData.tokens else playerData.souls

        if (playerBalance < totalCost) {
            val message = YamlFactory.getMessage("commands.pickaxe.enchants.insufficient_funds", mapOf(
                "currency" to currency,
                "amount" to totalCost.toString()
            ))
            player.sendMessage(Component.text(message))
            return
        }

        val updatedPickaxe = if (info.isOwned) {
            // Upgrade existing enchant
            val existingEnchant = currentPickaxe.getEnchantByName(info.enchant.name)
            if (existingEnchant == null) {
                player.sendMessage(Component.text("Enchant not found on pickaxe!", NamedTextColor.RED))
                return
            }

            val newLevel = (existingEnchant.level + info.upgradeAmount).coerceAtMost(existingEnchant.getMaxLevel())
            val updatedEnchant = Pickaxe.Companion.EnchantRegistry.createEnchant(
                Pickaxe.Companion.EnchantRegistry.getTypeName(existingEnchant),
                newLevel
            ) ?: run {
                player.sendMessage(Component.text("Failed to upgrade enchant!", NamedTextColor.RED))
                return
            }

            // Remove old enchant and add upgraded one
            val filteredEnchants = currentPickaxe.enchants.filter { !it.name.equals(info.enchant.name, true) }
            currentPickaxe.copy(enchants = filteredEnchants + updatedEnchant)
        } else {
            // Add new enchant
            val newEnchant = Pickaxe.Companion.EnchantRegistry.createEnchant(
                Pickaxe.Companion.EnchantRegistry.getTypeName(info.enchant),
                info.upgradeAmount
            ) ?: run {
                player.sendMessage(Component.text("Failed to create enchant!", NamedTextColor.RED))
                return
            }

            currentPickaxe.addEnchant(newEnchant)
        }

        // Deduct currency from player balance
        if (info.enchant.isTokenEnchant()) {
            playerData.tokens = playerData.tokens.subtract(totalCost)
        } else {
            playerData.souls = playerData.souls.subtract(totalCost)
        }

        // Update the player's item
        player.setItemInMainHand(updatedPickaxe.toItemStack())

        // Send success message
        val messageKey = if (info.isOwned) "commands.pickaxe.enchants.upgraded" else "commands.pickaxe.enchants.added"
        val finalLevel = if (info.isOwned) info.enchant.level + info.upgradeAmount else info.upgradeAmount

        val message = YamlFactory.getMessage(messageKey, mapOf(
            "enchant" to info.enchant.name,
            "level" to finalLevel.toString(),
            "amount" to totalCost.toString(),
            "currency" to currency
        ))
        player.sendMessage(Component.text(message))

        // Close the menu
        player.closeInventory()
    }

    private fun handleEnchantRemoval(player: Player, enchant: Enchant) {
        player.sendMessage(Component.text("Removed ${enchant.name} from your pickaxe!", NamedTextColor.GREEN))
        player.closeInventory()
    }

    private fun handleEnchantToggle(player: Player, enchant: Enchant, view: PlayerView) {
        // Get the current pickaxe from the player's main hand
        val currentPickaxe = Pickaxe.fromItemStack(player.itemInMainHand) ?: run {
            player.sendMessage(Component.text("You must be holding a pickaxe!", NamedTextColor.RED))
            return
        }

        // Find the actual enchant on the pickaxe
        val actualEnchant = currentPickaxe.getEnchantByName(enchant.name)
        if (actualEnchant == null) {
            player.sendMessage(Component.text("Enchant '${enchant.name}' not found on pickaxe!", NamedTextColor.RED))
            return
        }

        // Toggle the enchant's enabled state
        val updatedPickaxe = currentPickaxe.toggleEnchant(actualEnchant.name)

        // Update the player's item
        player.setItemInMainHand(updatedPickaxe.toItemStack())

        // Get the updated enchant to check its new state
        val toggledEnchant = updatedPickaxe.getEnchantByName(actualEnchant.name)
        val statusMessage = if (toggledEnchant?.isEnabled() == true) {
            "Enabled ${actualEnchant.name}!"
        } else {
            "Disabled ${actualEnchant.name}!"
        }

        player.sendMessage(Component.text(statusMessage, if (toggledEnchant?.isEnabled() == true) NamedTextColor.GREEN else NamedTextColor.RED))

        // Rebuild the view to update glinting
        view.executeRebuild()
    }

    // Data classes
    private data class EnchantInfo(
        val enchant: Enchant,
        val isOwned: Boolean,
        val canApply: Boolean,
        val isLocked: Boolean,
        val upgradeAmount: Int = 1
    ) {
        fun withUpgradeAmount(newAmount: Int): EnchantInfo {
            val maxPossible = if (isOwned) {
                // For owned enchants, can upgrade to max level
                enchant.getMaxLevel() - enchant.level
            } else {
                // For new enchants, can purchase up to max level (starting from level 1)
                enchant.getMaxLevel()
            }
            val clampedAmount = newAmount.coerceIn(1, maxPossible.coerceAtLeast(1))
            return copy(upgradeAmount = clampedAmount)
        }
    }
}
