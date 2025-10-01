package twizzy.tech.game.pets

import net.minestom.server.entity.EntityType
import net.minestom.server.item.Material
import twizzy.tech.game.pets.flying.Bat

/**
 * Defines the types of pets available in the game.
 *
 * @param defaultName The default name for this pet type.
 * @param entityType The Minestom entity type associated with this pet.
 * @param petClass The class representing this pet's logic.
 * @param displayItem The material for the item that represents this pet in inventories.
 */
enum class PetType(
    val defaultName: String,
    val entityType: EntityType,
    val petClass: Class<out Pet>,
    val displayItem: Material,
    val defaultDiet: List<Material>
) {
    BAT(
        defaultName = "Bat",
        entityType = EntityType.BAT,
        petClass = Bat::class.java,
        displayItem = Material.BAT_SPAWN_EGG,
        defaultDiet = listOf(Material.MELON_SEEDS)
    );

    companion object {
        /**
         * Finds a [PetType] by its name, ignoring case.
         *
         * @param name The name of the pet type to find.
         * @return The matching [PetType], or null if not found.
         */
        fun fromString(name: String): PetType? {
            return entries.find { it.name.equals(name, ignoreCase = true) }
        }
    }
}
