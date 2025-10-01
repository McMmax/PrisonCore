package twizzy.tech.game.items.containers

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import net.minestom.server.tag.Tag
import twizzy.tech.game.pets.Pet
import twizzy.tech.util.YamlFactory
import java.util.*

class PetPouch(
    val contents: MutableMap<Int, ItemStack> = mutableMapOf(),
    val selectedPets: MutableList<ItemStack?> = MutableList(MAX_SELECTED_PETS) { null },
    var activePetSlot: Int = -1,
    val owner: UUID,
    val uid: UUID = UUID.randomUUID()
) {

    private val displayName: String = "Pet Pouch"
    private val displayItem: Material = Material.BUNDLE
    private val isGlowing: Boolean = true

    fun toItemStack(): ItemStack {
        val pouchLore = mutableListOf<Component>()
        pouchLore.add(Component.text("A special pouch to hold your pets."))
        pouchLore.add(Component.text("Owner: ${owner}"))


        var builder = ItemStack.builder(displayItem)
            .customName(MiniMessage.miniMessage().deserialize(displayName))
            .lore(pouchLore)
            .set(TAG_PETPOUCH_UID, uid.toString())
            .set(TAG_PETPOUCH_OWNER, owner)
            .set(TAG_PETPOUCH_ACTIVE_SLOT, activePetSlot)

        // Save contents
        val contentsJson = JsonObject()
        for ((slot, item) in contents) {
            val pet = Pet.fromItemStack(item)
            val updatedItem = pet?.apply { update() }?.toItemStack() ?: item
            contentsJson.addProperty(slot.toString(), encodeItemBase64(updatedItem))
        }
        builder = builder.set(TAG_PETPOUCH_CONTENTS, contentsJson.toString())

        // Save selected pets
        val selectedStr = selectedPets.joinToString(",") {
            it?.let {
                val pet = Pet.fromItemStack(it)
                val updatedItem = pet?.apply { update() }?.toItemStack() ?: it
                encodeItemBase64(updatedItem)
            } ?: ""
        }
        builder = builder.set(TAG_PETPOUCH_SELECTED_SLOTS, selectedStr)


        var itemStack = builder.build()
        if (isGlowing) {
            itemStack = itemStack.withGlowing(true)
        }
        return itemStack
    }

    companion object {
        private val TAG_PETPOUCH_UID = Tag.String("petpouch_uid")
        private val TAG_PETPOUCH_OWNER = Tag.UUID("petpouch_owner")
        private val TAG_PETPOUCH_CONTENTS = Tag.String("petpouch_contents")
        private val TAG_PETPOUCH_SELECTED_SLOTS = Tag.String("petpouch_selected_slots")
        private val TAG_PETPOUCH_ACTIVE_SLOT = Tag.Integer("petpouch_active_slot")

        const val MAX_SELECTED_PETS = 5 // 5 slots for selected pets

        // Global tracker of active pet instances per player (persists across menu instances)
        val ACTIVE_PET_INSTANCES = mutableMapOf<UUID, Pet>() // Now keyed by player UUID

        private fun encodeItemBase64(item: ItemStack): String {
            // Use the same stable NBT serialization as the backpack system
            val nbt = item.toItemNBT()
            val baos = java.io.ByteArrayOutputStream()
            net.kyori.adventure.nbt.BinaryTagIO.writer().write(nbt, baos)
            return Base64.getEncoder().encodeToString(baos.toByteArray())
        }

        private fun decodeItemBase64(data: String): ItemStack? {
            return try {
                val bytes = Base64.getDecoder().decode(data)
                val nbt = net.kyori.adventure.nbt.BinaryTagIO.reader().read(java.io.ByteArrayInputStream(bytes))
                ItemStack.fromItemNBT(nbt)
            } catch (e: Exception) {
                println("Error decoding item: ${e.message}")
                null
            }
        }

        fun loadPetPouchContents(pouchItem: ItemStack): Map<Int, ItemStack> {
            val contentsStr = pouchItem.getTag(TAG_PETPOUCH_CONTENTS) ?: return emptyMap()
            if (contentsStr.isEmpty()) return emptyMap()
            return try {
                val json = JsonParser.parseString(contentsStr).asJsonObject
                val contents = mutableMapOf<Int, ItemStack>()
                for ((slotKey, element) in json.entrySet()) {
                    val slot = slotKey.toIntOrNull() ?: continue
                    if (element.isJsonPrimitive && element.asJsonPrimitive.isString) {
                        val base64 = element.asString
                        val item = decodeItemBase64(base64)
                        if (item != null && !item.isAir) contents[slot] = item
                    }
                }
                contents
            } catch (e: Exception) {
                println("Error loading pet pouch contents: ${e.message}")
                emptyMap()
            }
        }

        fun loadSelectedSlots(pouchItem: ItemStack): MutableList<ItemStack?> {
            val selectedSlotsStr = pouchItem.getTag(TAG_PETPOUCH_SELECTED_SLOTS) ?: return MutableList(MAX_SELECTED_PETS) { null }
            if (selectedSlotsStr.isEmpty()) return MutableList(MAX_SELECTED_PETS) { null }
            return try {
                val base64Strings = selectedSlotsStr.split(",")
                MutableList(MAX_SELECTED_PETS) { index ->
                    base64Strings.getOrNull(index)?.takeIf { it.isNotEmpty() }?.let { decodeItemBase64(it) }
                }
            } catch (e: Exception) {
                println("Error loading selected slots: ${e.message}")
                MutableList(MAX_SELECTED_PETS) { null }
            }
        }

        fun saveSelectedSlots(pouchItem: ItemStack, selectedSlots: List<ItemStack?>): ItemStack {
            return try {
                val selectedStr = selectedSlots.joinToString(",") { it?.let { encodeItemBase64(it) } ?: "" }
                pouchItem.builder()
                    .set(TAG_PETPOUCH_SELECTED_SLOTS, selectedStr)
                    .build()
            } catch (e: Exception) {
                println("Error saving selected slots: ${e.message}")
                pouchItem
            }
        }

        fun savePetPouch(pouch: PetPouch, pouchItem: ItemStack): ItemStack {
            var builder = pouchItem.builder()
                .set(TAG_PETPOUCH_UID, pouch.uid.toString())
                .set(TAG_PETPOUCH_OWNER, pouch.owner)
                .set(TAG_PETPOUCH_ACTIVE_SLOT, pouch.activePetSlot)

            // Save contents
            val contentsJson = JsonObject()
            for ((slot, item) in pouch.contents) {
                contentsJson.addProperty(slot.toString(), encodeItemBase64(item))
            }
            builder = builder.set(TAG_PETPOUCH_CONTENTS, contentsJson.toString())

            // Save selected pets
            val selectedStr = pouch.selectedPets.joinToString(",") { it?.let { encodeItemBase64(it) } ?: "" }
            builder = builder.set(TAG_PETPOUCH_SELECTED_SLOTS, selectedStr)

            return builder.build()
        }

        fun fromItemStack(itemStack: ItemStack): PetPouch? {
            if (itemStack.material() != Material.BUNDLE) return null

            val uidString = itemStack.getTag(TAG_PETPOUCH_UID) ?: return null
            val uid = UUID.fromString(uidString)
            val owner = itemStack.getTag(TAG_PETPOUCH_OWNER) ?: return null
            val contents = loadPetPouchContents(itemStack).toMutableMap()
            val selectedSlots = loadSelectedSlots(itemStack)
            val activePetSlot = itemStack.getTag(TAG_PETPOUCH_ACTIVE_SLOT) ?: -1
            return PetPouch(
                contents = contents,
                selectedPets = selectedSlots,
                activePetSlot = activePetSlot,
                owner = owner,
                uid = uid
            )
        }
    }

    fun addItem(item: ItemStack): Int {
        for (slot in 0..53) {
            if (!contents.containsKey(slot)) {
                contents[slot] = item
                return slot
            }
        }
        return -1
    }

    fun removeItem(slot: Int): ItemStack? {
        return contents.remove(slot)
    }

    fun getItem(slot: Int): ItemStack? {
        return contents[slot]
    }

    fun getPetCount(): Int {
        return contents.values.count { Pet.fromItemStack(it) != null }
    }

    // Pet selection methods - UPDATED LOGIC
    fun selectPet(contentsSlot: Int): Boolean {
        if (contentsSlot !in contents.keys) return false

        val petItem = contents[contentsSlot] ?: return false
        val pet = Pet.fromItemStack(petItem)
        if (pet == null) return false // Ensure it's actually a pet

        // Check if already selected by UID
        if (selectedPets.any { it != null && Pet.fromItemStack(it)?.uid == pet.uid }) {
            return false
        }

        // NEW: Check if a pet of the same type (name) is already selected
        if (selectedPets.any { selectedItem ->
            selectedItem != null && Pet.fromItemStack(selectedItem)?.petType == pet.petType
        }) {
            return false // Don't allow selecting pets of the same type
        }

        // Find first available selected slot
        val selectedSlot = selectedPets.indexOfFirst { it == null }
        if (selectedSlot == -1) return false // No available slots

        selectedPets[selectedSlot] = petItem
        return true
    }

    fun unselectPet(selectedSlot: Int): Boolean {
        if (selectedSlot !in 0 until MAX_SELECTED_PETS) return false

        selectedPets[selectedSlot] ?: return false
        selectedPets[selectedSlot] = null

        // Update active pet slot if needed
        if (activePetSlot == selectedSlot) {
            activePetSlot = -1
        }

        return true
    }

    // Helper method to get the contents slot for a selected pet
    fun getContentsSlotForSelectedPet(selectedSlot: Int): Int? {
        if (selectedSlot !in 0 until MAX_SELECTED_PETS) return null
        val selectedItem = selectedPets[selectedSlot] ?: return null

        return contents.entries.find { it.value == selectedItem }?.key
    }

    // OLD METHODS - Keep for backward compatibility but mark as deprecated
    @Deprecated("Use selectPet(contentsSlot: Int) instead", ReplaceWith("selectPet(contentsSlot)"))
    fun selectPet(item: ItemStack): Boolean {
        if (selectedPets.contains(item)) return false
        val idx = selectedPets.indexOfFirst { it == null }
        if (idx == -1) return false
        selectedPets[idx] = item
        return true
    }


    fun getActivePet(): ItemStack? {
        if (activePetSlot < 0 || activePetSlot >= MAX_SELECTED_PETS) return null
        return selectedPets[activePetSlot]
    }

    fun deactivateActivePet() {
        activePetSlot = -1
    }

    fun getSelectedPetCount(): Int {
        return selectedPets.count { it != null }
    }

    fun getAvailableSelectedSlots(): List<Int> {
        return selectedPets.mapIndexedNotNull { idx, v -> if (v == null) idx else null }
    }

    fun activatePet(player: net.minestom.server.entity.Player, slot: Int, instance: net.minestom.server.instance.Instance, position: net.minestom.server.coordinate.Pos) {
        val item = selectedPets.getOrNull(slot)
        val pet = item?.let { Pet.fromItemStack(it) }

        // Deactivate any currently active pet for the player
        deactivatePet(player)

        // Activate new pet
        if (pet != null) {
            pet.onActivate(instance, position, player)
            ACTIVE_PET_INSTANCES[player.uuid] = pet // Use player's UUID as the key
            activePetSlot = slot
            val petName = PlainTextComponentSerializer.plainText().serialize(MiniMessage.miniMessage().deserialize(pet.displayName))
            val message = YamlFactory.getMessage("pets.activated", mapOf("pet_name" to petName))
            player.sendMessage(Component.text(message))
        } else {
            activePetSlot = -1
        }
    }

    fun deactivatePet(player: net.minestom.server.entity.Player) {
        // Find the active pet associated with this player
        val activePet = ACTIVE_PET_INSTANCES[player.uuid]

        if (activePet != null) {
            activePet.onDeactivate()
            ACTIVE_PET_INSTANCES.remove(player.uuid)
            val petName = PlainTextComponentSerializer.plainText().serialize(MiniMessage.miniMessage().deserialize(activePet.displayName))
            val message = YamlFactory.getMessage("pets.deactivated", mapOf("pet_name" to petName))
            player.sendMessage(Component.text(message))
        }
        activePetSlot = -1
    }

    /**
     * Adds experience to the currently active pet in the pouch.
     * Updates the ItemStack in selectedPets accordingly.
     */
    fun addExperienceToActivePet(amount: Long): Boolean {
        if (activePetSlot < 0 || activePetSlot >= selectedPets.size) return false
        val itemStack = selectedPets[activePetSlot] ?: return false
        val pet = Pet.fromItemStack(itemStack) ?: return false
        pet.experience += amount
        selectedPets[activePetSlot] = pet.toItemStack()
        return true
    }

    fun getAllPets(): List<ItemStack> {
        val allPetItems = (contents.values + selectedPets.filterNotNull()).toList()
        return allPetItems.distinctBy { it.getTag(Pet.TAG_UID) }
    }
}