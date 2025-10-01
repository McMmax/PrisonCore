package twizzy.tech.game.items.backpack

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import net.kyori.adventure.text.Component
import net.kyori.adventure.nbt.BinaryTagIO
import net.kyori.adventure.nbt.CompoundBinaryTag
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import net.minestom.server.tag.Tag
import twizzy.tech.game.items.Item
import twizzy.tech.util.CompactNotation
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.*

class Backpack(
    val owner: String,
    val contents: MutableMap<Int, ItemStack> = mutableMapOf(),
    var profit: Double = 0.0,
    name: String = "backpack",
    displayName: String = "Backpack",
    displayItem: Material = Material.NETHER_STAR,
    isGlowing: Boolean = true,
    unbreakable: Boolean = true,
    uid: UUID = UUID.randomUUID()
) : Item(
    name = name,
    displayName = displayName,
    displayItem = displayItem,
    isGlowing = isGlowing,
    unbreakable = unbreakable,
    uid = uid
) {
    companion object {
        private val TAG_BACKPACK_OWNER = Tag.String("backpack_owner")
        private val TAG_BACKPACK_CONTENTS = Tag.String("backpack_contents")
        private val TAG_BACKPACK_PROFIT = Tag.Double("backpack_profit")
        private val gson = Gson()

        // Encode an ItemStack (incl. full NBT) to Base64
        private fun encodeItemBase64(item: ItemStack): String {
            val nbt = item.toItemNBT()
            val baos = ByteArrayOutputStream()
            BinaryTagIO.writer().write(nbt, baos)
            return Base64.getEncoder().encodeToString(baos.toByteArray())
        }

        // Decode an ItemStack from Base64 (full NBT)
        private fun decodeItemBase64(data: String): ItemStack? {
            return try {
                val bytes = Base64.getDecoder().decode(data)
                val nbt = BinaryTagIO.reader().read(ByteArrayInputStream(bytes)) as CompoundBinaryTag
                ItemStack.fromItemNBT(nbt)
            } catch (e: Exception) {
                null
            }
        }

        /**
         * Loads backpack contents from an ItemStack
         * Supports legacy format (material/amount) and new Base64 NBT format
         */
        fun loadBackpackContents(backpackItem: ItemStack): Map<Int, ItemStack> {
            val contentsStr = backpackItem.getTag(TAG_BACKPACK_CONTENTS) ?: return emptyMap()
            if (contentsStr.isEmpty()) return emptyMap()

            return try {
                val json = JsonParser.parseString(contentsStr).asJsonObject
                val contents = mutableMapOf<Int, ItemStack>()

                for ((slotKey, element) in json.entrySet()) {
                    val slot = slotKey.toIntOrNull() ?: continue

                    // New format: string Base64
                    if (element.isJsonPrimitive && element.asJsonPrimitive.isString) {
                        val base64 = element.asString
                        val item = decodeItemBase64(base64)
                        if (item != null && !item.isAir && item.amount() > 0) contents[slot] = item
                        continue
                    }

                    // Legacy format: { material, amount }
                    if (element.isJsonObject) {
                        val obj: JsonObject = element.asJsonObject
                        val matName = if (obj.has("material")) obj.get("material").asString else null
                        val amount = if (obj.has("amount")) obj.get("amount").asInt else 1
                        if (matName != null) {
                            val material = Material.values().find { it.name() == matName }
                            if (material != null) contents[slot] = ItemStack.of(material, amount)
                        }
                    }
                }

                contents
            } catch (e: Exception) {
                println("Error loading backpack contents: ${e.message}")
                emptyMap()
            }
        }

        /**
         * Saves backpack contents to an ItemStack using Base64 NBT per slot
         */
        fun saveBackpackContents(backpackItem: ItemStack, contents: Map<Int, ItemStack>): ItemStack {
            return try {
                val contentsData: Map<String, String> = contents
                    .filter { (_, item) -> !item.isAir && item.amount() > 0 }
                    .mapKeys { it.key.toString() }
                    .mapValues { (_, item) -> encodeItemBase64(item) }

                val contentsStr = gson.toJson(contentsData)

                backpackItem.builder()
                    .set(TAG_BACKPACK_CONTENTS, contentsStr)
                    .build()
            } catch (e: Exception) {
                println("Error saving backpack contents: ${e.message}")
                backpackItem
            }
        }

        /**
         * Gets the total number of items in a backpack ItemStack
         */
        fun getBackpackItemCount(backpackItem: ItemStack): Int {
            return loadBackpackContents(backpackItem).size
        }

        /**
         * Checks if a backpack ItemStack has any contents
         */
        fun hasBackpackContents(backpackItem: ItemStack): Boolean {
            val contentsStr = backpackItem.getTag(TAG_BACKPACK_CONTENTS) ?: ""
            return contentsStr.isNotEmpty()
        }

        /**
         * Clears all contents from a backpack ItemStack
         */
        fun clearBackpackContents(backpackItem: ItemStack): ItemStack {
            return backpackItem.builder()
                .set(TAG_BACKPACK_CONTENTS, "")
                .build()
        }

        /**
         * Creates a Backpack object from an ItemStack if it represents a valid backpack
         */
        fun fromItemStack(itemStack: ItemStack): Backpack? {
            val baseItem = Item.fromItemStack(itemStack) ?: return null
            val owner = itemStack.getTag(TAG_BACKPACK_OWNER) ?: return null
            val profit = itemStack.getTag(TAG_BACKPACK_PROFIT) ?: 0.0
            val contents = loadBackpackContents(itemStack).toMutableMap()

            return Backpack(
                owner = owner,
                contents = contents,
                profit = profit,
                name = baseItem.name,
                displayName = baseItem.displayName,
                displayItem = baseItem.displayItem,
                isGlowing = baseItem.isGlowing,
                unbreakable = baseItem.unbreakable,
                uid = baseItem.uid
            )
        }
    }

    /**
     * Loads contents from a backpack ItemStack into this instance
     * @param backpackItem The ItemStack to load from
     */
    fun loadFromItemStack(backpackItem: ItemStack) {
        val loadedContents = loadBackpackContents(backpackItem)
        contents.clear()
        contents.putAll(loadedContents)

        // Update profit if it exists in the item
        val itemProfit = backpackItem.getTag(TAG_BACKPACK_PROFIT)
        if (itemProfit != null) {
            profit = itemProfit
        }
    }

    /**
     * Saves the current contents to the provided backpack ItemStack
     * @param backpackItem The ItemStack to save to
     * @return Updated ItemStack with current contents
     */
    fun saveToItemStack(backpackItem: ItemStack): ItemStack {
        return saveBackpackContents(backpackItem, contents)
            .builder()
            .set(TAG_BACKPACK_PROFIT, profit)
            .build()
    }

    /**
     * Sets an item at a specific slot
     */
    fun setItem(slot: Int, itemStack: ItemStack?) {
        if (itemStack == null || itemStack.material() == Material.AIR) {
            contents.remove(slot)
        } else {
            contents[slot] = itemStack
        }
    }

    /**
     * Gets an item from a specific slot
     */
    fun getItem(slot: Int): ItemStack? {
        return contents[slot]
    }

    /**
     * Adds an item to the first available slot
     * @param itemStack The item to add
     * @return The slot where the item was placed, or -1 if no space available
     */
    fun addItem(itemStack: ItemStack): Int {
        // Find the first available slot (0-53 for a double chest)
        for (slot in 0..53) {
            if (!contents.containsKey(slot)) {
                contents[slot] = itemStack
                return slot
            }
        }
        return -1
    }

    /**
     * Removes an item from a specific slot
     */
    fun removeItem(slot: Int): ItemStack? {
        return contents.remove(slot)
    }

    /**
     * Gets the number of items in the backpack
     */
    fun getItemCount(): Int {
        return contents.size
    }

    /**
     * Gets all occupied slots
     */
    fun getOccupiedSlots(): Set<Int> {
        return contents.keys
    }

    /**
     * Clears all contents
     */
    fun clearContents() {
        contents.clear()
    }

    /**
     * Adds profit to the backpack
     */
    fun addProfit(amount: Double) {
        profit += amount
    }

    /**
     * Creates a formatted backpack ItemStack with lore containing owner and contents information
     */
    override fun toItemStack(): ItemStack {
        // Get the base ItemStack from parent class
        val baseItemStack = super.toItemStack()

        // Format profit value for display
        val formattedProfit = "$${CompactNotation.format(profit)}"

        // Add lore with backpack information
        val lore = mutableListOf<Component>()
        lore.add(Component.text("§7Owner: §f$owner"))
        lore.add(Component.text("§7Items: §e${getItemCount()}"))
        lore.add(Component.text("§7Profit: §6$formattedProfit"))
        lore.add(Component.text(""))
        lore.add(Component.text("§8Right-click to open"))

        // Serialize contents using Base64 NBT per slot
        val contentsData: Map<String, String> = contents
            .filter { (_, item) -> !item.isAir && item.amount() > 0 }
            .mapKeys { it.key.toString() }
            .mapValues { (_, item) ->
                try {
                    encodeItemBase64(item)
                } catch (e: Exception) {
                    ""
                }
            }
        val contentsStr = gson.toJson(contentsData)

        // Build a new ItemStack with our custom backpack data
        return baseItemStack.builder()
            .set(TAG_BACKPACK_OWNER, owner)
            .set(TAG_BACKPACK_CONTENTS, contentsStr)
            .set(TAG_BACKPACK_PROFIT, profit)
            .lore(lore)
            .build()
    }
}