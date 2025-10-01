package twizzy.tech.game.pets

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.minestom.server.entity.Entity
import net.minestom.server.entity.Player
import net.minestom.server.instance.Instance
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import net.minestom.server.tag.Tag
import twizzy.tech.util.YamlFactory
import java.util.*
import kotlin.math.max
import kotlin.math.pow

/**
 * The base class for all pets. This class is abstract and should be extended by specific pet implementations.
 *
 * @property petType The type of the pet, which defines its core characteristics.
 * @property displayName The custom name of the pet. Defaults to the pet type's default name.
 * @property rarity The rarity of the pet.
 * @property level The current level of the pet.
 * @property experience The current experience of the pet.
 * @property lastPlayedWith The timestamp when the pet was last played with.
 * @property lastEaten The timestamp when the pet was last fed.
 * @property hunger The current hunger level of the pet (0-100).
 * @property happiness The current happiness level of the pet (0-100).
 * @property uid The unique identifier for this pet instance.
 */
abstract class Pet(
    val petType: PetType,
    var displayName: String,
    val rarity: Rarity,
    var level: Int = 1,
    var experience: Long = 0L,
    var lastPlayedWith: Long = System.currentTimeMillis(),
    var lastEaten: Long = System.currentTimeMillis(),
    var hunger: Int = (0..100).random(),
    var happiness: Int = (60..100).random(),
    val uid: UUID = UUID.randomUUID(),
    var diet: List<Material> = petType.defaultDiet
) {

    // Configuration cache
    private val config by lazy { YamlFactory.loadConfig("game/config.yml") }

    var active: Boolean = false

    /**
     * This method is called periodically to apply a passive effect to the owner.
     * It should be implemented by subclasses to define pet-specific passive abilities.
     *
     * @param player The player who owns the pet.
     */
    abstract fun passiveEffect(player: Player)

    /**
     * Renames the pet to the given new name.
     *
     * @param newName The new name for the pet.
     */
    fun rename(newName: String) {
        this.displayName = newName
    }

    /**
     * Updates the pet's hunger and happiness levels based on the time since the last update.
     */
    fun update() {
        val now = System.currentTimeMillis()
        val hungerDecayDuration = YamlFactory.getValue(config, "pets.hunger.decay_duration_seconds", 604800) * 1000
        val happinessDecayDuration = YamlFactory.getValue(config, "pets.happiness.decay_duration_seconds", 604800) * 1000

        val timeSinceEaten = now - lastEaten
        val timeSincePlayed = now - lastPlayedWith

        // Only apply decay if significant time has passed (more than 1 minute)
        // This prevents overriding random initial values for newly created pets
        if (timeSinceEaten > 60000) { // 1 minute
            val hungerLoss = (timeSinceEaten.toDouble() / hungerDecayDuration * 100).toInt()
            hunger = max(0, 100 - hungerLoss)
        }

        if (timeSincePlayed > 60000) { // 1 minute
            val happinessLoss = (timeSincePlayed.toDouble() / happinessDecayDuration * 100).toInt()
            happiness = max(0, 100 - happinessLoss)
        }

        // Happiness is also affected by hunger
        if (hunger < 50) {
            happiness = max(0, happiness - (50 - hunger) / 2)
        }
    }

    /**
     * Feeds the pet, increasing its hunger and happiness.
     */
    fun feed() {
        this.hunger = (this.hunger + 20).coerceAtMost(100)
        this.happiness = (this.happiness + 10).coerceAtMost(100)
        this.lastEaten = System.currentTimeMillis()
    }

    /**
     * Determines the current state of the pet based on its hunger and happiness levels.
     *
     * @param updateStats Whether to update stats before determining state
     * @return The current [State] of the pet.
     */
    fun getState(updateStats: Boolean = true): State {
        if (updateStats) {
            update() // Ensure stats are up-to-date before determining state
        }

        if (hunger == 0) {
            val starvationTime = System.currentTimeMillis() - lastEaten
            val deathTimer = YamlFactory.getValue(config, "pets.starvation.death_timer_seconds", 604800) * 1000
            if (starvationTime >= deathTimer) {
                return State.DEAD
            }
            return State.STARVING
        }

        val statesConfig = YamlFactory.getValue(config, "pets.states", emptyMap<String, Any>())

        fun getInt(section: Map<String, Any>, key: String, default: Int): Int {
            return (section[key] as? Int) ?: default
        }

        val excitedConfig = statesConfig["excited"] as? Map<String, Any> ?: emptyMap()
        val happyConfig = statesConfig["happy"] as? Map<String, Any> ?: emptyMap()
        val playfulConfig = statesConfig["playful"] as? Map<String, Any> ?: emptyMap()
        val calmConfig = statesConfig["calm"] as? Map<String, Any> ?: emptyMap()
        val hungryConfig = statesConfig["hungry"] as? Map<String, Any> ?: emptyMap()
        val sadConfig = statesConfig["sad"] as? Map<String, Any> ?: emptyMap()
        val madConfig = statesConfig["mad"] as? Map<String, Any> ?: emptyMap()

        if (hunger >= getInt(excitedConfig, "min_hunger", 95) && happiness >= getInt(excitedConfig, "min_happiness", 95)) return State.EXCITED
        if (hunger >= getInt(happyConfig, "min_hunger", 80) && happiness >= getInt(happyConfig, "min_happiness", 80)) return State.HAPPY
        if (happiness >= getInt(playfulConfig, "min_happiness", 60)) return State.PLAYFUL
        if (hunger >= getInt(calmConfig, "min_hunger", 50) && happiness >= getInt(calmConfig, "min_happiness", 50)) return State.CALM
        if (hunger <= getInt(hungryConfig, "max_hunger", 49)) return State.HUNGRY
        if (happiness <= getInt(sadConfig, "max_happiness", 49)) return State.SAD
        if (hunger <= getInt(madConfig, "max_hunger", 20) && happiness <= getInt(madConfig, "max_happiness", 20)) return State.MAD

        return State.CALM // Default state
    }

    /**
     * Converts this pet instance into a Minestom [ItemStack] for storage or display.
     *
     * @param updateStats Whether to update hunger/happiness based on time decay before converting to ItemStack
     * @return The [ItemStack] representation of the pet.
     */
    open fun toItemStack(updateStats: Boolean = false): ItemStack {
        if (updateStats) {
            update()
        }
        val lore = mutableListOf<Component>()
        lore.add(MiniMessage.miniMessage().deserialize("${rarity.displayColor}Level $level ${rarity.displayName}"))
        lore.add(MiniMessage.miniMessage().deserialize("<gray>Hunger         Happiness        State"))
        lore.add(MiniMessage.miniMessage().deserialize("<green>${hunger}%              <light_purple>${happiness}%             <white>${getState(updateStats)}"))
        lore.add(Component.text(""))
        lore.add(MiniMessage.miniMessage().deserialize("<gray>Experience: <aqua>$experience"))
        // Progress bar for experience
        val expCurrent = experience
        val expForCurrentLevel = getExperienceForLevel(level)
        val expForNextLevel = getExperienceForLevel(level + 1)
        val progress = if (expForNextLevel > expForCurrentLevel) {
            ((expCurrent - expForCurrentLevel).toDouble() / (expForNextLevel - expForCurrentLevel)).coerceIn(0.0, 1.0)
        } else 1.0
        val totalBars = 10
        val filledBars = (progress * totalBars).toInt()
        val bar = buildString {
            append("[")
            repeat(filledBars) { append('|') }
            repeat(totalBars - filledBars) { append(' ') }
            append("]")
        }
        lore.add(MiniMessage.miniMessage().deserialize("<gray>Progress: <green>$bar <white>${((progress * 100).toInt())}%"))
        val finalDisplayName = "$displayName ${rarity.displayColor}[Lvl $level]"

        // Create a basic builder with the material
        val itemStack = ItemStack.builder(petType.displayItem)
            .amount(level)
            .customName(MiniMessage.miniMessage().deserialize(finalDisplayName))
            .lore(lore)
            .build()
            .withTag(TAG_PET_TYPE, petType.name)
            .withTag(TAG_DISPLAY_NAME, displayName)
            .withTag(TAG_RARITY, rarity.name)
            .withTag(TAG_LEVEL, level)
            .withTag(TAG_EXPERIENCE, experience)
            .withTag(TAG_LAST_PLAYED, lastPlayedWith)
            .withTag(TAG_LAST_EATEN, lastEaten)
            .withTag(TAG_HUNGER, hunger)
            .withTag(TAG_HAPPINESS, happiness)
            .withTag(TAG_UID, uid.toString())
        return itemStack
    }

    companion object {
        // Define tags for storing item data
        val TAG_PET_TYPE = Tag.String("pet_type")
        val TAG_DISPLAY_NAME = Tag.String("pet_display_name")
        val TAG_RARITY = Tag.String("pet_rarity")
        val TAG_LEVEL = Tag.Integer("pet_level")
        val TAG_EXPERIENCE = Tag.Long("pet_experience")
        val TAG_LAST_PLAYED = Tag.Long("pet_last_played")
        val TAG_LAST_EATEN = Tag.Long("pet_last_eaten")
        val TAG_HUNGER = Tag.Integer("pet_hunger")
        val TAG_HAPPINESS = Tag.Integer("pet_happiness")
        val TAG_UID = Tag.String("pet_uid")

        /**
         * Creates a [Pet] instance from a Minestom [ItemStack].
         *
         * @param itemStack The [ItemStack] to convert.
         * @return The created [Pet] instance, or null if conversion failed.
         */
        fun fromItemStack(itemStack: ItemStack): Pet? {
            return try {
                val petTypeName = itemStack.getTag(TAG_PET_TYPE) ?: return null
                val petType = PetType.fromString(petTypeName) ?: return null

                val displayName = itemStack.getTag(TAG_DISPLAY_NAME) ?: petType.defaultName
                val rarityName = itemStack.getTag(TAG_RARITY) ?: Rarity.COMMON.name
                val rarity = Rarity.valueOf(rarityName)
                val level = itemStack.getTag(TAG_LEVEL) ?: 1
                val experience = itemStack.getTag(TAG_EXPERIENCE) ?: 0L
                val lastPlayedWith = itemStack.getTag(TAG_LAST_PLAYED) ?: System.currentTimeMillis()
                val lastEaten = itemStack.getTag(TAG_LAST_EATEN) ?: System.currentTimeMillis()
                val hunger = itemStack.getTag(TAG_HUNGER) ?: (0..100).random()
                val happiness = itemStack.getTag(TAG_HAPPINESS) ?: (60..100).random()
                val uidString = itemStack.getTag(TAG_UID)
                val uid = if (uidString != null) UUID.fromString(uidString) else UUID.randomUUID()

                // Use reflection to create an instance of the correct pet subclass
                val constructor = petType.petClass.getConstructor(
                    String::class.java,
                    Rarity::class.java,
                    Int::class.java,
                    Long::class.java,
                    Long::class.java,
                    Long::class.java,
                    Int::class.java,
                    Int::class.java,
                    UUID::class.java
                )
                return constructor.newInstance(
                    displayName,
                    rarity,
                    level,
                    experience,
                    lastPlayedWith,
                    lastEaten,
                    hunger,
                    happiness,
                    uid
                )
            } catch (e: Exception) {
                println("Error creating pet from ItemStack: ${e.message}")
                e.printStackTrace()
                null
            }
        }

        /**
         * Generates a random pet of the specified rarity.
         *
         * @param rarity The rarity of the pet to generate.
         * @return A new [Pet] instance with random attributes.
         */
        fun random(rarity: Rarity): Pet {
            val petType = PetType.entries.random()
            val levelRange = when (rarity) {
                Rarity.COMMON -> 1..5
                Rarity.UNCOMMON -> 5..10
                Rarity.RARE -> 10..15
                Rarity.EPIC -> 15..20
                Rarity.LEGENDARY -> 20..30
                Rarity.MYTHIC -> 30..50
                Rarity.ETERNAL -> 50..100
            }
            val randomLevel = levelRange.random()
            val randomHunger = (0..100).random()
            val randomHappiness = (60..100).random()
            val now = System.currentTimeMillis()
            val constructor = petType.petClass.getConstructor(
                String::class.java,
                Rarity::class.java,
                Int::class.java,
                Long::class.java,
                Long::class.java,
                Long::class.java,
                Int::class.java,
                Int::class.java,
                UUID::class.java
            )
            return constructor.newInstance(
                petType.defaultName,
                rarity,
                randomLevel,
                0L,
                now,
                now,
                randomHunger,
                randomHappiness,
                UUID.randomUUID()
            )
        }
    }

    /**
     * This method is called when the pet is activated.
     * It should be implemented by subclasses to define what happens when the pet is spawned in the world.
     *
     * @param instance The instance where the pet is being activated.
     * @param position The position where the pet should be spawned.
     * @param owner The player who owns the pet.
     * @return The spawned [Entity] representing the pet, or null if spawning failed.
     */
    abstract fun onActivate(instance: Instance, position: net.minestom.server.coordinate.Pos, owner: Player): Entity?

    /**
     * This method is called when the pet is deactivated.
     * It should be implemented by subclasses to define what happens when the pet is removed from the world.
     */
    abstract fun onDeactivate()

    /**
     * This method is called when the pet levels up.
     * It can be overridden by subclasses to add custom level-up logic.
     */
    open fun onLevelUp() {
        // Default: do nothing
    }

    /**
     * Adds experience to the pet and handles level-ups.
     *
     * @param amount The amount of experience to add.
     * @param player The player who owns the pet (for notifications).
     * @return True if the pet leveled up, false otherwise.
     */
    fun addExperience(amount: Long, player: Player? = null): Boolean {
        val oldLevel = level
        experience += amount

        var leveledUp = false
        while (experience >= getExperienceForLevel(level + 1) && level < getMaxLevel()) {
            level++
            leveledUp = true
        }

        if (leveledUp) {
            onLevelUp(player, oldLevel, level)
        }

        return leveledUp
    }

    /**
     * Called when the pet levels up. This method can be overridden by subclasses to provide custom level-up behavior.
     *
     * @param player The player who owns the pet.
     * @param oldLevel The pet's level before leveling up.
     * @param newLevel The pet's new level.
     */
    open fun onLevelUp(player: Player?, oldLevel: Int, newLevel: Int) {
        // Default: do nothing - subclasses should override this
    }

    /**
     * Gets the maximum level for pets from the configuration.
     *
     * @return The maximum pet level.
     */
    fun getMaxLevel(): Int {
        return YamlFactory.getValue(config, "pets.leveling.maxLevel", 100)
    }

    /**
     * Calculates the experience required for a given level.
     *
     * @param targetLevel The level for which to calculate the experience requirement.
     * @return The total experience required to reach the target level.
     */
    open fun getExperienceForLevel(targetLevel: Int): Long {
        if (targetLevel <= 1) return 0L
        val baseExp = YamlFactory.getValue(config, "pets.leveling.baseExperience", 100.0)
        val multiplier = YamlFactory.getValue(config, "pets.leveling.experienceMultiplier", 1.2)
        return (baseExp * multiplier.pow((targetLevel - 1).toDouble())).toLong()
    }

    /**
     * Checks if the given material is in the pet's diet.
     */
    fun canEat(material: Material): Boolean {
        return diet.contains(material)
    }

    /**
     * Feeds the pet with the given material if possible.
     * Returns true if the pet was fed, false otherwise.
     */
    fun feed(material: Material): Boolean {
        if (canEat(material)) {
            hunger = 100
            lastEaten = System.currentTimeMillis()
            return true
        }
        return false
    }

}

