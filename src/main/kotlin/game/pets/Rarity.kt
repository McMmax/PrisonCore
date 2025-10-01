package twizzy.tech.game.pets

import net.minestom.server.item.Material

enum class Rarity(val material: Material, val displayColor: String, val displayName: String, val level: Int) {
    COMMON(Material.SENTRY_ARMOR_TRIM_SMITHING_TEMPLATE, "<white>", "Common", 1),
    UNCOMMON(Material.DUNE_ARMOR_TRIM_SMITHING_TEMPLATE, "<green>", "Uncommon", 5), // # Need 3 level 3s to fuse to Uncommon
    RARE(Material.COAST_ARMOR_TRIM_SMITHING_TEMPLATE, "<blue>", "Rare", 10),
    EPIC(Material.WILD_ARMOR_TRIM_SMITHING_TEMPLATE, "<light_purple>", "Epic", 15),
    LEGENDARY(Material.TIDE_ARMOR_TRIM_SMITHING_TEMPLATE, "<gold>", "Legendary", 20),
    MYTHIC(Material.WARD_ARMOR_TRIM_SMITHING_TEMPLATE, "<red>", "Mythic", 30),
    ETERNAL(Material.SPIRE_ARMOR_TRIM_SMITHING_TEMPLATE, "<dark_red>", "Eternal", 50)
}