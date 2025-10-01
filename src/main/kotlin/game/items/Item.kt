package twizzy.tech.game.items

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import net.minestom.server.component.DataComponents
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import net.minestom.server.tag.Tag
import java.util.*

open class Item(
    val name: String,
    val displayName: String,
    val displayItem: Material,
    val isGlowing: Boolean = false,
    val unbreakable: Boolean = false,
    var durability: Int? = 1, // Default durability is 0
    val uid: UUID = UUID.randomUUID() // Add unique item ID with default random generation
) {
    /**
     * Creates a Minestom ItemStack from this Item
     * @return The created ItemStack
     */
    open fun toItemStack(): ItemStack {
        // Create a basic builder with the material
        var itemStack = ItemStack.builder(displayItem)
            .customName(Component.text(displayName))
            .set(TAG_ITEM_NAME, name)
            .set(DataComponents.MAX_DAMAGE, durability)
            .set(TAG_ITEM_UID, uid.toString()) // Save the UID
            .build()

        if (unbreakable) {
            itemStack = itemStack.with(DataComponents.UNBREAKABLE)
        }

        // Apply glowing effect if needed
        if (isGlowing) {
            itemStack = itemStack.withGlowing(true)
        }


        return itemStack
    }

    /**
     * Creates an Item from a Minestom ItemStack
     * @param itemStack The ItemStack to convert
     * @return The created Item, or null if conversion failed
     */
    companion object {
        // Define tags for storing item data
        private val TAG_ITEM_NAME = Tag.String("item_name")
        private val TAG_ITEM_UID = Tag.String("item_uid") // New tag for the UID

        fun fromItemStack(itemStack: ItemStack): Item? {
            // Retrieve our custom properties from the item tags
            val name = itemStack.getTag(TAG_ITEM_NAME) ?: return null
            val durability = itemStack.get(DataComponents.MAX_DAMAGE)
            val uidString = itemStack.getTag(TAG_ITEM_UID)

            // Parse the UUID or generate a new one if not present
            val uid = try {
                if (uidString != null) UUID.fromString(uidString) else UUID.randomUUID()
            } catch (e: IllegalArgumentException) {
                UUID.randomUUID() // Fallback if UUID parsing fails
            }

            // Extract display name as plain string from the custom name component
            val displayNameComponent = itemStack.get(DataComponents.CUSTOM_NAME)
            val displayName = displayNameComponent?.let { PlainTextComponentSerializer.plainText().serialize(it) } ?: name

            return Item(
                name = name,
                displayName = displayName,
                displayItem = itemStack.material(),
                durability = durability ?: 0,
                uid = uid
            )
        }
    }

    /**
     * Checks if this item equals another item by comparing their UIDs
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Item) return false
        return uid == other.uid
    }

    /**
     * Returns a hash code for this item based on its UID
     */
    override fun hashCode(): Int {
        return uid.hashCode()
    }
}
