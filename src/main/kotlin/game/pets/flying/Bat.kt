package twizzy.tech.game.pets.flying

import com.github.shynixn.mccoroutine.minestom.addSuspendingListener
import com.github.shynixn.mccoroutine.minestom.launch
import io.github.togar2.pvp.events.PrepareAttackEvent
import kotlinx.coroutines.Job
import net.kyori.adventure.key.Key
import net.kyori.adventure.sound.Sound
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.minestom.server.MinecraftServer
import net.minestom.server.component.DataComponents
import net.minestom.server.coordinate.Pos
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.Entity
import net.minestom.server.entity.EntityCreature
import net.minestom.server.entity.EntityType
import net.minestom.server.entity.Player
import net.minestom.server.entity.attribute.Attribute
import net.minestom.server.event.EventNode
import net.minestom.server.event.player.PlayerEntityInteractEvent
import net.minestom.server.instance.Instance
import net.minestom.server.item.ItemStack
import net.minestom.server.network.packet.server.play.ParticlePacket
import net.minestom.server.particle.Particle
import net.minestom.server.tag.Tag
import twizzy.tech.game.items.containers.PetPouch
import twizzy.tech.game.pets.Pet
import twizzy.tech.game.pets.PetType
import twizzy.tech.game.pets.Rarity
import twizzy.tech.game.pets.controller.FlightController
import twizzy.tech.game.pets.menu.PetInformation
import twizzy.tech.player.PlayerData
import twizzy.tech.serverInstance
import twizzy.tech.util.DurationParser
import twizzy.tech.util.YamlFactory
import twizzy.tech.viewRegistry
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.*
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min
import kotlin.math.pow

class Bat(
    displayName: String,
    rarity: Rarity,
    level: Int,
    experience: Long,
    lastPlayedWith: Long,
    lastEaten: Long,
    hunger: Int = (0..100).random(),
    happiness: Int = (60..100).random(),
    uid: UUID = UUID.randomUUID(),
) : Pet(
    petType = PetType.BAT,
    displayName = displayName,
    rarity = rarity,
    level = level,
    experience = experience,
    lastPlayedWith = lastPlayedWith,
    lastEaten = lastEaten,
    hunger = hunger,
    happiness = happiness,
    uid = uid
) {

    private val passiveEffectLore: String = "Collected souls earn interest over time."
    private val activeEffectLore: String = "Collect souls while you mine."

    private var spawnedEntity: EntityCreature? = null
    private var flightController: FlightController? = null
    private val soulsCollected = AtomicReference(BigDecimal.ZERO)
    private var soulCollectionJob: Job? = null
    private var interestJob: Job? = null

    // Interest and streak tracking
    private var lastPlayed: LocalDateTime = LocalDateTime.now()
    private var lastDailyMining: LocalDate? = null
    private var miningStreak: Int = 0
    private var totalMiningTime: Long = 0 // in seconds
    private var lastInterestCalculation: LocalDateTime = LocalDateTime.now()
    private var lastSoulCollectionTime: LocalDateTime = LocalDateTime.now()
    private var lastPettingTime: LocalDateTime = LocalDateTime.MIN
    private val pettingCooldownSeconds = 60 * 3L // 3 minutes

    // Configuration cache
    private val config by lazy { YamlFactory.loadConfig("game/config.yml") }

    companion object {
        val TAG_PLAYER_UUID = Tag.UUID("player_uuid")
    }

    constructor(rarity: Rarity, level: Int) : this(
        displayName = PetType.BAT.defaultName,
        rarity = rarity,
        level = level,
        experience = 0L,
        lastPlayedWith = System.currentTimeMillis(),
        lastEaten = System.currentTimeMillis(),
        hunger = (0..100).random(),
        happiness = (60..100).random(),
        uid = UUID.randomUUID()
    )

    override fun passiveEffect(player: Player) {
        // Bat-specific passive effect, e.g., chance to drop extra loot
    }

    override fun onActivate(instance: Instance, position: Pos, owner: Player): Entity? {
        spawnedEntity?.remove()
        flightController?.stop()
        soulCollectionJob?.cancel()
        interestJob?.cancel()

        val batEntity = EntityCreature(EntityType.BAT)
        batEntity.setInstance(instance, position)
        batEntity.setNoGravity(true)
        batEntity.isInvulnerable = true
        val name = MiniMessage.miniMessage().deserialize(displayName + " " + rarity.displayColor + "[Lvl $level]")
        batEntity.customName = name
        batEntity.setTag(TAG_PLAYER_UUID, owner.uuid)

        // Set initial scale based on level (level 1 = 1.0, level 50 = 2.5, capped at 50)
        val scale = 1.0 + ((min(level, 50) - 1) * (1.5 / 49))
        batEntity.getAttribute(Attribute.SCALE).setBaseValue(scale)

        flightController = FlightController(batEntity, owner)
        flightController?.start()
        spawnedEntity = batEntity

        // Calculate offline interest upon activation
        calculateInterest(true)

        val node = EventNode.all("bat-pet-${owner.uuid}-${batEntity.uuid}")
        node.addSuspendingListener(serverInstance, PlayerEntityInteractEvent::class.java) { event ->
            if (event.target == batEntity && event.player == owner) {
                val pet = PetPouch.ACTIVE_PET_INSTANCES[owner.uuid]
                if (pet != null) {
                    pet.update() // Ensure stats are up-to-date
                    PetInformation(viewRegistry, pet).open(owner)
                }
            }
        }

        node.addSuspendingListener(serverInstance, PrepareAttackEvent::class.java) { event ->
            if (event.target == batEntity && event.entity == owner) {
                val now = LocalDateTime.now()
                val secondsSinceLastPet = ChronoUnit.SECONDS.between(lastPettingTime, now)
                if (secondsSinceLastPet >= pettingCooldownSeconds) {
                    happiness = min(100, (happiness + (0.05 * 100)).toInt())
                    lastPettingTime = now
                    val message = YamlFactory.getMessage("pets.petting.success", mapOf("happiness" to happiness.toString()))
                    owner.sendMessage(Component.text(message))

                    // Show "can't break" particles since action is denied
                    val particlePacket = ParticlePacket(
                        Particle.HEART,
                        Pos(batEntity.position),
                        Vec(0.2, 0.2, 0.2),
                        0.05f,
                        2
                    )
                    owner.sendPacket(particlePacket)
                } else {
                    val remaining = pettingCooldownSeconds - secondsSinceLastPet
                    val message = YamlFactory.getMessage("pets.petting.cooldown", mapOf("cooldown" to DurationParser.format(remaining, true)))
                    owner.sendMessage(Component.text(message))
                }
                event.isCancelled = true // Prevent damage
            }
        }

        MinecraftServer.getGlobalEventHandler().addChild(node)

        // Start async soul collection loop
        soulCollectionJob = serverInstance.launch {
            while (spawnedEntity != null) {
                if (flightController?.isPursuingOwner() == true) {
                    updateLastPlayed()
                    collectSoul()
                    updateDailyMining()

                    // Route experience gain through PetPouch
                    val petPouch = PetPouch.fromItemStack(owner.inventory.getItemStack(7))
                    if (petPouch != null) {
                        // Update the experience in the pet pouch's selected pets
                        val activePetItem = petPouch.getActivePet()
                        if (activePetItem != null) {
                            val activePet = fromItemStack(activePetItem)
                            if (activePet != null && activePet.uid == this@Bat.uid) {
                                // Store the old level before adding experience
                                val oldLevel = this@Bat.level

                                val expGain = 100L // Placeholder for experience gain
                                addExperience(expGain, owner)

                                // Update the selected pet in the pouch
                                val updatedPetItem = toItemStack()
                                petPouch.selectedPets[petPouch.activePetSlot] = updatedPetItem

                                // CRITICAL FIX: Also update the pet in contents to prevent duplication
                                // Find the corresponding slot in contents and update it too
                                val contentsSlot = petPouch.getContentsSlotForSelectedPet(petPouch.activePetSlot)
                                if (contentsSlot != null) {
                                    petPouch.contents[contentsSlot] = updatedPetItem
                                }

                                owner.inventory.setItemStack(7, petPouch.toItemStack())

                                // Update the active instance in ACTIVE_PET_INSTANCES
                                PetPouch.ACTIVE_PET_INSTANCES[owner.uuid] = this@Bat

                                // IMPORTANT: Only update entity name tag when level actually changes
                                if (this@Bat.level != oldLevel) {
                                    spawnedEntity?.let { entity ->
                                        val updatedName = MiniMessage.miniMessage()
                                            .deserialize(displayName)
                                        entity.customName = updatedName
                                    }
                                }
                            }
                        }
                    } else {
                        // Fallback if pouch not found
                        val expGain = 100L // Placeholder for experience gain
                        addExperience(expGain, owner)
                        // Update active instance for fallback case too
                        PetPouch.ACTIVE_PET_INSTANCES[owner.uuid] = this@Bat
                    }
                    println("xp: $experience, level: $level")
                }
                kotlinx.coroutines.delay(5000L) // 5 seconds
            }
        }

        // Start interest calculation loop
        interestJob = serverInstance.launch {
            while (spawnedEntity != null) {
                calculateInterest()
                applyInactivityPenalty()
                kotlinx.coroutines.delay(getInterestIntervalMs()) // Based on config
            }
        }

        return batEntity
    }

     fun withdrawSouls(player: Player) {
        // Calculate any pending interest before withdrawing
        calculateInterest()

        val souls = soulsCollected.getAndSet(BigDecimal.ZERO)
        if (souls <= BigDecimal.ZERO) {
            val message = YamlFactory.getMessage("pets.bat.no_souls_to_deposit", emptyMap())
            player.sendMessage(Component.text(message))
            return
        }

         serverInstance.launch { PlayerData.addSouls(player.uuid, souls) }

        val message = YamlFactory.getMessage("pets.bat.souls_deposited", mapOf("souls" to souls.toPlainString()))
        player.sendMessage(Component.text(message))
    }

    override fun onDeactivate() {
        spawnedEntity?.remove()
        flightController?.stop()
        soulCollectionJob?.cancel()
        interestJob?.cancel()
        soulCollectionJob = null
        interestJob = null
        spawnedEntity = null
        flightController = null
    }

    private fun updateLastPlayed() {
        lastPlayed = LocalDateTime.now()
    }

    private fun updateDailyMining() {
        val today = LocalDate.now()
        val minMiningTime = YamlFactory.getValue(config, "pets.bat.soulCollection.minMiningTime", 300)

        totalMiningTime += 5 // 5 seconds per collection cycle

        if (totalMiningTime >= minMiningTime) {
            if (lastDailyMining == null || !lastDailyMining!!.isEqual(today)) {
                // Check if streak should continue
                if (lastDailyMining != null && ChronoUnit.DAYS.between(lastDailyMining!!, today) == 1L) {
                    miningStreak++
                } else if (lastDailyMining == null || ChronoUnit.DAYS.between(lastDailyMining!!, today) > 1L) {
                    miningStreak = 1 // Reset or start streak
                }
                lastDailyMining = today
                totalMiningTime = 0 // Reset daily timer
            }
        }
    }

    private fun collectSoul() {
        val baseSoulGain = YamlFactory.getValue(config, "pets.bat.baseSoulGain", 2.0)

        // Use rarity-based soul gain multiplier instead of level-based
        val rarityMultiplier = when (rarity) {
            Rarity.COMMON -> YamlFactory.getValue(config, "pets.bat.soulGainMultipliers.common", 1.0)
            Rarity.UNCOMMON -> YamlFactory.getValue(config, "pets.bat.soulGainMultipliers.uncommon", 1.5)
            Rarity.RARE -> YamlFactory.getValue(config, "pets.bat.soulGainMultipliers.rare", 2.0)
            Rarity.EPIC -> YamlFactory.getValue(config, "pets.bat.soulGainMultipliers.epic", 2.5)
            Rarity.LEGENDARY -> YamlFactory.getValue(config, "pets.bat.soulGainMultipliers.legendary", 3.0)
            Rarity.MYTHIC -> YamlFactory.getValue(config, "pets.bat.soulGainMultipliers.mythic", 4.0)
            Rarity.ETERNAL -> YamlFactory.getValue(config, "pets.bat.soulGainMultipliers.eternal", 5.0)
        }

        // Calculate souls per collection based on rarity (not level)
        val soulsPerCollection = baseSoulGain * rarityMultiplier

        // Apply daily mining bonuses (same as before)
        val dailyBonus = getDailySoulBonus()
        val finalSouls = soulsPerCollection * (1.0 + dailyBonus / 100.0)

        soulsCollected.updateAndGet { current ->
            current.add(BigDecimal.valueOf(finalSouls).setScale(2, RoundingMode.HALF_UP))
        }
    }

    private fun getDailySoulBonus(): Double {
        if (lastDailyMining == null || !lastDailyMining!!.isEqual(LocalDate.now())) return 0.0

        val baseBonusPercent = YamlFactory.getValue(config, "pets.bat.interest.dailyMining.dailySoulBonus.baseBonusPercent", 10.0)
        val streakBonusPercent = YamlFactory.getValue(config, "pets.bat.interest.dailyMining.dailySoulBonus.streakBonusPercent", 2.0)
        val maxDailyBonus = YamlFactory.getValue(config, "pets.bat.interest.dailyMining.dailySoulBonus.maxDailyBonus", 250.0)

        val streakBonus = miningStreak * streakBonusPercent
        return min(baseBonusPercent + streakBonus, maxDailyBonus)
    }

    private fun calculateInterest(isActivation: Boolean = false) {
        val now = LocalDateTime.now()
        val timeSinceLastCalculation = if (isActivation) lastSoulCollectionTime else lastInterestCalculation
        val minutesPassed = ChronoUnit.MINUTES.between(timeSinceLastCalculation, now)
        val interestIntervalMinutes = getInterestIntervalMs() / 60000

        if (minutesPassed < interestIntervalMinutes) return

        val intervals = minutesPassed / interestIntervalMinutes
        if (intervals <= 0) return

        val interestRate = calculateInterestRate()
        if (interestRate <= 0.0) return

        val compound = YamlFactory.getValue(config, "pets.bat.interest.compound", true)
        val minSouls = YamlFactory.getValue(config, "pets.bat.interest.minSouls", 50.0)

        var currentSouls = soulsCollected.get()
        if (currentSouls.toDouble() < minSouls) return

        if (compound) {
            val multiplier = BigDecimal.valueOf(1.0 + interestRate / 100.0).pow(intervals.toInt())
            currentSouls = currentSouls.multiply(multiplier)
        } else {
            val interestPerInterval = currentSouls.multiply(BigDecimal.valueOf(interestRate / 100.0))
            val totalSimpleInterest = interestPerInterval.multiply(BigDecimal.valueOf(intervals))
            currentSouls = currentSouls.add(totalSimpleInterest)
        }

        soulsCollected.set(currentSouls.setScale(2, RoundingMode.HALF_UP))
        lastInterestCalculation = now
    }

    // Public accessor methods for PetInformation menu
    fun getSoulsCollected(): String {
        return soulsCollected.get().setScale(2, RoundingMode.HALF_UP).toString()
    }

    fun getMiningStreak(): Int {
        return miningStreak
    }

    // Make calculateInterestRate public
    fun calculateInterestRate(): Double {
        val baseRate = YamlFactory.getValue(config, "pets.bat.interest.baseRate", 0.000015)
        val levelMultiplier = YamlFactory.getValue(config, "pets.bat.interest.levelMultiplier", 0.0001)
        val exponentialBonus = YamlFactory.getValue(config, "pets.bat.interest.exponentialBonus", 0.0000002)
        val maxRate = YamlFactory.getValue(config, "pets.bat.interest.maxRate", 0.012)

        // Base calculation: baseRate + (level * levelMultiplier) + (level^2 * exponentialBonus)
        var interestRate = baseRate + (level * levelMultiplier) + (level.toDouble().pow(2.0) * exponentialBonus)

        // Add milestone bonuses
        interestRate += getMilestoneBonus()

        // Apply active miner bonus
        val activeMinerBonus = YamlFactory.getValue(config, "pets.bat.interest.dailyMining.activeMinerBonus", 2.5)
        if (isActiveMiner()) {
            interestRate *= activeMinerBonus
        }

        // Add streak bonus
        interestRate += getStreakBonus()

        // Cap the rate
        return min(interestRate, maxRate)
    }

    // Make isActiveMiner public
    fun isActiveMiner(): Boolean {
        return lastDailyMining?.isEqual(LocalDate.now()) == true
    }

    fun getLastPlayedFormatted(): String {
        val now = LocalDateTime.now()
        val duration = java.time.Duration.between(lastPlayed, now)

        return when {
            duration.toDays() > 0 -> "${duration.toDays()} days ago"
            duration.toHours() > 0 -> "${duration.toHours()} hours ago"
            duration.toMinutes() > 0 -> "${duration.toMinutes()} minutes ago"
            else -> "Just now"
        }
    }

    private fun getMilestoneBonus(): Double {
        val milestones = mapOf(
            10 to YamlFactory.getValue(config, "pets.bat.interest.milestones.level10", 0.0005),
            25 to YamlFactory.getValue(config, "pets.bat.interest.milestones.level25", 0.001),
            40 to YamlFactory.getValue(config, "pets.bat.interest.milestones.level40", 0.002),
            55 to YamlFactory.getValue(config, "pets.bat.interest.milestones.level55", 0.003),
            67 to YamlFactory.getValue(config, "pets.bat.interest.milestones.level67", 0.005),
            80 to YamlFactory.getValue(config, "pets.bat.interest.milestones.level80", 0.008),
            100 to YamlFactory.getValue(config, "pets.bat.interest.milestones.level100", 0.015)
        )

        return milestones.filterKeys { level >= it }.values.sum()
    }

    private fun getStreakBonus(): Double {
        if (miningStreak <= 0) return 0.0

        val baseStreakBonus = YamlFactory.getValue(config, "pets.bat.interest.dailyMining.streakBonus.baseStreakBonus", 0.003)
        val maxStreakBonus = YamlFactory.getValue(config, "pets.bat.interest.dailyMining.streakBonus.maxStreakBonus", 0.15)
        val levelStreakMultiplier = YamlFactory.getValue(config, "pets.bat.interest.dailyMining.streakBonus.levelStreakMultiplier", 0.00006)

        val streakBonus = miningStreak * baseStreakBonus
        val levelBonus = miningStreak * level * levelStreakMultiplier

        return min(streakBonus + levelBonus, maxStreakBonus)
    }

    private fun applyInactivityPenalty() {
        val gracePeriod = YamlFactory.getValue(config, "pets.bat.interest.dailyMining.inactivityPenalty.gracePeriod", 3)
        val decayRatePerDay = YamlFactory.getValue(config, "pets.bat.interest.dailyMining.inactivityPenalty.decayRatePerDay", 0.02)
        val maxDecayPercent = YamlFactory.getValue(config, "pets.bat.interest.dailyMining.inactivityPenalty.maxDecayPercent", 50.0)

        val daysSinceLastPlayed = ChronoUnit.DAYS.between(lastPlayed.toLocalDate(), LocalDate.now())

        if (daysSinceLastPlayed > gracePeriod) {
            val decayDays = daysSinceLastPlayed - gracePeriod
            val totalDecayRate = min(decayDays * decayRatePerDay, maxDecayPercent / 100.0)

            if (totalDecayRate > 0) {
                soulsCollected.updateAndGet { current ->
                    val decay = current.multiply(BigDecimal.valueOf(totalDecayRate))
                    current.subtract(decay).max(BigDecimal.ZERO)
                }
            }
        }
    }

    private fun getInterestIntervalMs(): Long {
        val intervalSeconds = YamlFactory.getValue(config, "pets.bat.interest.intervalSeconds", 300)
        return intervalSeconds * 1000L
    }

    /**
     * Override onLevelUp to send congratulatory message and effects when bat levels up
     */
    override fun onLevelUp(player: Player?, oldLevel: Int, newLevel: Int) {
        super.onLevelUp(player, oldLevel, newLevel)
        if (player != null) {
            // Send level up message using correct YamlFactory key
            val levelUpMessage = YamlFactory.getMessage(
                "pets.leveling.level_up", mapOf(
                    "pet_name" to "Bat",
                    "level" to newLevel.toString()
                )
            )
            player.sendMessage(Component.text(levelUpMessage))


            val rarityColor = rarity.displayName.substring(0, rarity.displayName.indexOf('>') + 1)
            val name = MiniMessage.miniMessage().deserialize(displayName + " " + rarityColor + "[Lvl ${newLevel}]")
            spawnedEntity?.customName = name
            // Update scale on level up
            spawnedEntity?.let { entity ->
                val scale = 1.0 + ((min(newLevel, 50) - 1) * (1.5 / 49))
                entity.getAttribute(Attribute.SCALE).setBaseValue(scale)
            }

            // Play success sound
            player.playSound(
                Sound.sound(
                    Key.key("minecraft:entity.firework_rocket.large_blast"),
                    Sound.Source.PLAYER,
                    1.0f,
                    1.0f
                )
            )
        }
    }

    override fun toItemStack(updateStats: Boolean): ItemStack {
        // Always update hunger/happiness before displaying
        update()
        val lore = mutableListOf<Component>()
        lore.add(MiniMessage.miniMessage().deserialize("${rarity.displayColor}Level $level ${rarity.displayName}"))
        lore.add(MiniMessage.miniMessage().deserialize("<gray>Hunger         Happiness        State"))
        lore.add(MiniMessage.miniMessage().deserialize("<green>${hunger}%              <light_purple>${happiness}%             <white>${getState(true)}"))
        lore.add(Component.text(""))
        lore.add(MiniMessage.miniMessage().deserialize("<gray>Experience: <aqua>$experience"))
        // Updated lore format for effects
        lore.add(MiniMessage.miniMessage().deserialize("<gold>Active Effect"))
        lore.add(MiniMessage.miniMessage().deserialize("<white>$activeEffectLore"))
        lore.add(Component.text(""))
        lore.add(MiniMessage.miniMessage().deserialize("<yellow>Passive Effect"))
        lore.add(MiniMessage.miniMessage().deserialize("<white>$passiveEffectLore"))
        lore.add(Component.text(""))
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
        lore.add(MiniMessage.miniMessage().deserialize("<gray>Experience: <aqua>$experience"))
        lore.add(MiniMessage.miniMessage().deserialize("<gray>Progress: <green>$bar <white>${((progress * 100).toInt())}%"))
        lore.add(MiniMessage.miniMessage().deserialize("<gray>Diet: Seeds"))
        val finalDisplayName = "$displayName ${rarity.displayColor}[Lvl $level]"
        return ItemStack.builder(petType.displayItem)
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
    }
}
