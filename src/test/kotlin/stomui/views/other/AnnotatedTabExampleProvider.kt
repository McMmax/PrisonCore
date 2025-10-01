package stomui.views.other

import eu.koboo.minestom.stomui.api.PlayerView
import eu.koboo.minestom.stomui.api.ViewBuilder
import eu.koboo.minestom.stomui.api.ViewRegistry
import eu.koboo.minestom.stomui.api.ViewType
import eu.koboo.minestom.stomui.api.annotations.Rebuildable
import eu.koboo.minestom.stomui.api.annotations.Slot
import eu.koboo.minestom.stomui.api.annotations.components.AnnotationRenderComponent
import eu.koboo.minestom.stomui.api.component.ViewProvider
import eu.koboo.minestom.stomui.api.item.ModifiableItem
import eu.koboo.minestom.stomui.api.item.PrebuiltItem
import eu.koboo.minestom.stomui.api.item.ViewItem
import eu.koboo.minestom.stomui.api.slots.SlotIterator
import net.minestom.server.entity.Player
import net.minestom.server.item.Material
import stomui.utils.SettingsTab

class AnnotatedTabExampleProvider(registry: ViewRegistry) :
    ViewProvider(registry, ViewBuilder.of(ViewType.SIZE_6_X_9).title("Annotated tab example")) {

    var currentTab: SettingsTab = SettingsTab.PROFILE
        private set

    private val tabContentSlots: SlotIterator

    init {
        // Creating a SlotIterator with all slots of the content.
        tabContentSlots = SlotIterator.of(this)
            .startPosition(1, 1)
            .endPosition(5, 8)
            .blacklistTopBorder()

        addChild(AnnotationRenderComponent())
    }

    override fun onRebuild(view: PlayerView, player: Player) {

        // Getting all ViewItems by the slots of the tab content.
        for (viewItem in ViewItem.bySlotIterator(view, tabContentSlots)) {
            // Changing the material and name, just to visual changes in the example

            viewItem.material<ModifiableItem?>(currentTab.material)
                .displayName<ModifiableItem?>(currentTab.displayName + " Settings Name")
        }
    }

    @Slot(0)
    // No @Rebuildable means, only build on view opening.
    fun closeItem(): PrebuiltItem? {
        // Closes the inventory
        return PrebuiltItem.empty()
            .material<ModifiableItem?>(Material.REDSTONE)
            .displayName<ModifiableItem?>("Close")
            .closeInventoryInteraction<PrebuiltItem?>()
    }

    @Slot(2)
    @Rebuildable // Rebuilds with "onRebuild"
    fun profileItem(): PrebuiltItem {
        // Changes the current tab to PROFILE
        return createSettingsItem(SettingsTab.PROFILE)
    }

    @Slot(4)
    @Rebuildable // Rebuilds with "onRebuild"
    fun clanItem(): PrebuiltItem {
        // Changes the current tab to CLAN
        return createSettingsItem(SettingsTab.CLAN)
    }

    @Slot(6)
    @Rebuildable // Rebuilds with "onRebuild"
    fun friendsItem(): PrebuiltItem {
        // Changes the current tab to FRIEND
        return createSettingsItem(SettingsTab.FRIEND)
    }

    private fun createSettingsItem(ownTab: SettingsTab): PrebuiltItem {
        // Method to create a tab item, based on the given ownTab.
        return PrebuiltItem.empty()
            .material<ModifiableItem>(ownTab.material)
            .displayName<ModifiableItem>(ownTab.displayName)
            // Because the item gets rebuild "onStateUpdate" we can just check
            // the currentTab here.
            .glint<ModifiableItem>(currentTab == ownTab)
            .interaction { action ->
                // If the user clicks this item,
                // we update the current tab field in this component instance,
                // and we call updateState(), to ensure the tab item,
                // which was selected starts glinting (enchantment glow)
                this.currentTab = ownTab
                action.view.executeRebuild()
            }
    }

    override fun onOpen(view: PlayerView, player: Player) {
        // Getting all topSlots by the ViewType of the view.
        val topSlots = view.type.topSlots

        // Getting all ViewItems by the slot list of the ViewType.
        for (viewItem in ViewItem.bySlotList(view, topSlots)) {
            // Setting placeholder items
            viewItem.material<ModifiableItem>(Material.GRAY_STAINED_GLASS_PANE)
                .displayName<ModifiableItem>(" ")
                .cancelClicking<ModifiableItem>()
        }
    }
}
