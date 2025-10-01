package twizzy.tech.game.items.pickaxe.boosters

import net.kyori.adventure.text.Component
import net.minestom.server.component.DataComponents
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import net.minestom.server.tag.Tag
import twizzy.tech.game.items.pickaxe.Booster
import twizzy.tech.util.YamlFactory
import java.time.Instant
import java.util.*

/**
 * Experience booster that multiplies the experience gained from mining blocks
 * @param multiplier The experience multiplier (e.g., 2.0 for double experience)
 * @param expiry The time when this booster expires (null for permanent)
 */
class Experience(
    multiplier: Double,
    expiry: Instant? = null
) : Booster(multiplier, expiry, "Experience Booster") {

    /**
     * Applies the experience multiplier to the given experience amount
     * @param baseExperience The base experience amount to multiply
     * @return The boosted experience amount (rounded to nearest integer)
     */
    fun applyBooster(baseExperience: Int): Int {
        if (!isActive()) return baseExperience
        return (baseExperience * multiplier).toInt()
    }

    /**
     * Applies the experience multiplier to the given experience amount (Long version)
     * @param baseExperience The base experience amount to multiply
     * @return The boosted experience amount
     */
    fun applyBooster(baseExperience: Long): Long {
        if (!isActive()) return baseExperience
        return (baseExperience * multiplier).toLong()
    }

    /**
     * Creates an ItemStack representation of this booster that can be given to players
     */
    fun toItemStack(source: String = "Unknown"): ItemStack {
        val config = twizzy.tech.game.Engine.gameConfig ?: emptyMap()

        // Get display properties from config
        val displayName = YamlFactory.getValue(config, "booster.experience.display", "§aExperience Booster")
        val materialName = YamlFactory.getValue(config, "booster.experience.item", "EXPERIENCE_BOTTLE")
        val loreConfig = YamlFactory.getValue(config, "booster.experience.lore", emptyList<String>())

        // Parse material
        val material: Material = Material.fromKey(materialName.uppercase()) ?: Material.EXPERIENCE_BOTTLE

        // Format duration text
        val durationText = if (expiry != null) {
            val remaining = getRemainingDuration()
            if (remaining != null && remaining > 0) {
                formatDuration(remaining)
            } else {
                "Expired"
            }
        } else {
            "Permanent"
        }

        // Format multiplier text
        val multiplierText = if (multiplier == multiplier.toInt().toDouble()) {
            "${multiplier.toInt()}"
        } else {
            String.format("%.1f", multiplier)
        }

        // Create lore with placeholders replaced
        val lore = loreConfig.map { line ->
            line.replace("%multiplier%", multiplierText)
                .replace("%duration%", durationText)
                .replace("%source%", source)
        }

        // Create the ItemStack with booster data
        var itemStack = ItemStack.builder(material)
            .customName(Component.text(displayName))
            .set(TAG_BOOSTER_TYPE, "Experience")
            .set(TAG_BOOSTER_MULTIPLIER, multiplier)
            .set(TAG_BOOSTER_SOURCE, source)
            .set(TAG_ITEM_UID, UUID.randomUUID().toString())
            .build()

        // Add duration if not permanent
        if (expiry != null) {
            val remainingSeconds = getRemainingDuration() ?: 0
            itemStack = itemStack.withTag(TAG_BOOSTER_DURATION, remainingSeconds)
        }

        // Set lore
        if (lore.isNotEmpty()) {
            itemStack = itemStack.with(DataComponents.LORE, lore.map { Component.text(it) })
        }

        return itemStack
    }

    companion object {
        // NBT Tags for storing booster data in items
        private val TAG_BOOSTER_TYPE = Tag.String("booster_type")
        private val TAG_BOOSTER_MULTIPLIER = Tag.Double("booster_multiplier")
        private val TAG_BOOSTER_DURATION = Tag.Long("booster_duration")
        private val TAG_BOOSTER_SOURCE = Tag.String("booster_source")
        private val TAG_ITEM_UID = Tag.String("item_uid")

        /**
         * Creates an Experience booster from an ItemStack
         * @param itemStack The ItemStack to parse
         * @return An Experience booster or null if the item is not a valid experience booster
         */
        fun fromItemStack(itemStack: ItemStack): Experience? {
            // Check if this is an experience booster item
            val boosterType = itemStack.getTag(TAG_BOOSTER_TYPE) ?: return null
            if (boosterType != "Experience") return null

            val multiplier = itemStack.getTag(TAG_BOOSTER_MULTIPLIER) ?: return null
            val durationSeconds = itemStack.getTag(TAG_BOOSTER_DURATION)

            // Calculate expiry time
            val expiry = if (durationSeconds != null && durationSeconds > 0) {
                Instant.now().plusSeconds(durationSeconds)
            } else {
                null
            }

            return Experience(multiplier, expiry)
        }

        /**
         * Checks if an ItemStack is an experience booster item
         */
        fun isExperienceBoosterItem(itemStack: ItemStack): Boolean {
            val boosterType = itemStack.getTag(TAG_BOOSTER_TYPE) ?: return false
            return boosterType == "Experience"
        }

        /**
         * Creates a temporary experience booster that lasts for the specified duration
         * @param multiplier The experience multiplier
         * @param durationSeconds How long the booster should last in seconds
         * @return A new Experience booster instance
         */
        fun temporary(multiplier: Double, durationSeconds: Long): Experience {
            val expiry = Instant.now().plusSeconds(durationSeconds)
            return Experience(multiplier, expiry)
        }

        /**
         * Creates a permanent experience booster
         * @param multiplier The experience multiplier
         * @return A new permanent Experience booster instance
         */
        fun permanent(multiplier: Double): Experience {
            return Experience(multiplier, null)
        }

        /**
         * Creates a temporary experience booster item that can be given to players
         */
        fun createItem(multiplier: Double, durationSeconds: Long, source: String = "Command"): ItemStack {
            val booster = temporary(multiplier, durationSeconds)
            return booster.toItemStack(source)
        }

        /**
         * Creates a permanent experience booster item that can be given to players
         */
        fun createPermanentItem(multiplier: Double, source: String = "Command"): ItemStack {
            val booster = permanent(multiplier)
            return booster.toItemStack(source)
        }

        /**
         * Common preset boosters for convenience
         */
        fun doubleXP(durationSeconds: Long) = temporary(2.0, durationSeconds)
        fun tripleXP(durationSeconds: Long) = temporary(3.0, durationSeconds)
        fun quadrupleXP(durationSeconds: Long) = temporary(4.0, durationSeconds)
        fun fiveTimesXP(durationSeconds: Long) = temporary(5.0, durationSeconds)

        fun permanentDoubleXP() = permanent(2.0)
        fun permanentTripleXP() = permanent(3.0)

        /**
         * Preset item creators
         */
        fun doubleXPItem(durationSeconds: Long, source: String = "Command") = createItem(2.0, durationSeconds, source)
        fun tripleXPItem(durationSeconds: Long, source: String = "Command") = createItem(3.0, durationSeconds, source)
        fun quadrupleXPItem(durationSeconds: Long, source: String = "Command") = createItem(4.0, durationSeconds, source)
        fun fiveTimesXPItem(durationSeconds: Long, source: String = "Command") = createItem(5.0, durationSeconds, source)

        fun permanentDoubleXPItem(source: String = "Command") = createPermanentItem(2.0, source)
        fun permanentTripleXPItem(source: String = "Command") = createPermanentItem(3.0, source)

        /**
         * Formats duration in seconds to a readable string
         */
        private fun formatDuration(seconds: Long): String {
            val hours = seconds / 3600
            val minutes = (seconds % 3600) / 60
            val secs = seconds % 60

            return when {
                hours > 0 -> "${hours}h ${minutes}m"
                minutes > 0 -> "${minutes}m ${secs}s"
                else -> "${secs}s"
            }
        }
    }
}