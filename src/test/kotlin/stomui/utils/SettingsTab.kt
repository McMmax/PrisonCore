package stomui.utils

import net.minestom.server.item.Material

enum class SettingsTab(val material: Material, val displayName: String) {
    PROFILE(Material.OAK_BOAT, "Profile"),
    CLAN(Material.ACACIA_BOAT, "Clan"),
    FRIEND(Material.GOLDEN_APPLE, "Friends")
}
