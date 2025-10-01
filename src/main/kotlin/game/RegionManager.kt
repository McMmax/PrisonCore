package twizzy.tech.game

import com.github.shynixn.mccoroutine.minestom.addSuspendingListener
import com.github.shynixn.mccoroutine.minestom.scope
import io.github.togar2.pvp.events.PrepareAttackEvent
import kotlinx.coroutines.launch
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.Point
import net.minestom.server.coordinate.Pos
import net.minestom.server.coordinate.Vec
import net.minestom.server.entity.Player
import net.minestom.server.event.EventFilter
import net.minestom.server.event.EventNode
import net.minestom.server.event.item.ItemDropEvent
import net.minestom.server.event.player.*
import net.minestom.server.event.trait.PlayerEvent
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import net.minestom.server.network.packet.server.play.ParticlePacket
import net.minestom.server.particle.Particle
import net.minestom.server.tag.Tag
import org.slf4j.LoggerFactory
import twizzy.tech.util.Worlds
import java.io.File
import java.util.*
import java.util.function.Predicate

class RegionManager(private val worlds: Worlds, private val minecraftServer: MinecraftServer, private var mineManager: MineManager? = null) {

    // Method to set RegionManager after construction (to resolve circular dependency)
    fun setMineManager(mineManager: MineManager) {
        this.mineManager = mineManager
    }


    private val logger = LoggerFactory.getLogger(RegionManager::class.java)


    // Store regions by world name and then by region ID
    private val regions = mutableMapOf<String, MutableMap<UUID, Region>>()

    // Track active visualization tasks
    private val visualizationJobs = mutableMapOf<UUID, kotlinx.coroutines.Job>()
    private val MAX_POINTS = 75 // Maximum points to show per edge to avoid too many particles

    // Track players currently in claiming mode
    private val claimingPlayerSet = Collections.synchronizedSet(mutableSetOf<UUID>())


    // Track recently processed block positions to prevent duplicate messages
    private val recentInteractions = mutableMapOf<UUID, Long>()
    private val INTERACTION_COOLDOWN = 500L // 500ms cooldown

    // Tags for the claiming process
    companion object {
        val CLAIMING_PROCESS = Tag.Boolean("claiming_process")
        val CLAIMING_NAME = Tag.String("claiming_name")
        val POSITION1 = Tag.String("position1")
        val POSITION2 = Tag.String("position2")

        // The wand item (golden hoe)
        val REGION_WAND = ItemStack.of(Material.GOLDEN_HOE)
            .withCustomName(
                Component.text("Region Selection Wand")
                    .color(NamedTextColor.GOLD)
                    .decoration(TextDecoration.ITALIC, false)
            )
            .withLore(listOf(
                Component.text("Left click: Set position 1").color(NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false),
                Component.text("Right click: Set position 2").color(NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false)
            ))
    }

    /**
     * Data class representing a region in a world
     */
    data class Region(
        val id: UUID,
        var name: String,
        var pos1: Point,
        var pos2: Point,
        val flags: MutableSet<String> = mutableSetOf(),
    ) {
        /**
         * Checks if a position is within this region
         */
        fun isInRegion(pos: Pos): Boolean {
            val minX = Math.min(pos1.x(), pos2.x())
            val maxX = Math.max(pos1.x(), pos2.x())
            val minZ = Math.min(pos1.z(), pos2.z())
            val maxZ = Math.max(pos1.z(), pos2.z())

            // Check X and Z coordinates first
            val inXZ = pos.x() in minX..maxX && pos.z() in minZ..maxZ

            if (!inXZ) return false

            // If "vertical" flag is set, check Y coordinates as provided in pos1 and pos2
            // Otherwise, default to full vertical range (-100 to 255)
            if (hasFlag("vertical")) {
                val minY = Math.min(pos1.y(), pos2.y())
                val maxY = Math.max(pos1.y(), pos2.y())
                return pos.y() in minY..maxY
            } else {
                return pos.y() in -100.0..255.0
            }
        }

        /**
         * Gets the volume of this region in blocks
         */
        suspend fun getVolume(): Long {
            val width = Math.abs(pos1.x() - pos2.x()) + 1
            val depth = Math.abs(pos1.z() - pos2.z()) + 1
            val height = if (hasFlag("vertical")) {
                Math.abs(pos1.y() - pos2.y()) + 1
            } else {
                356 // Height from -100 to 255 inclusive
            }

            // Use BigInteger for calculation to avoid potential overflow
            return java.math.BigInteger.valueOf(width.toLong())
                .multiply(java.math.BigInteger.valueOf(height.toLong()))
                .multiply(java.math.BigInteger.valueOf(depth.toLong()))
                .min(java.math.BigInteger.valueOf(Long.MAX_VALUE))
                .longValueExact()
        }

        /**
         * Gets the minimum and maximum corner positions of the region
         */
        suspend fun getMinMaxPositions(): Pair<Pos, Pos> {
            val minX = Math.min(pos1.x(), pos2.x())
            val maxX = Math.max(pos1.x(), pos2.x())
            val minZ = Math.min(pos1.z(), pos2.z())
            val maxZ = Math.max(pos1.z(), pos2.z())

            val minY = if (hasFlag("vertical")) Math.min(pos1.y(), pos2.y()) else -100.0
            val maxY = if (hasFlag("vertical")) Math.max(pos1.y(), pos2.y()) else 255.0

            return Pair(
                Pos(minX, minY, minZ),
                Pos(maxX, maxY, maxZ)
            )
        }

        /**
         * Toggles a flag on this region
         * @param flag The flag name
         * @return True if the flag was added, false if it was removed
         */
        suspend fun toggleFlag(flag: String): Boolean {
            val normalizedFlag = flag.lowercase()
            return if (flags.contains(normalizedFlag)) {
                flags.remove(normalizedFlag)
                false // Flag was removed
            } else {
                flags.add(normalizedFlag)
                true // Flag was added
            }
        }

        /**
         * Checks if this region has the specified flag
         * @param flag The flag name
         * @return True if the flag is set, false otherwise
         */
        fun hasFlag(flag: String): Boolean {
            return flags.contains(flag.lowercase())
        }

        /**
         * Gets the number of non-air blocks in this region
         */
        suspend fun getNonAirBlockCount(instance: net.minestom.server.instance.Instance): Long {
            val (min, max) = getMinMaxPositions()
            var count = 0L
            for (y in min.y().toInt()..max.y().toInt()) {
                for (x in min.x().toInt()..max.x().toInt()) {
                    for (z in min.z().toInt()..max.z().toInt()) {
                        val chunk = instance.getChunkAt(x.toDouble(), z.toDouble())
                        if (chunk == null || !chunk.isLoaded) {
                            continue // Skip unloaded chunks
                        }
                        val block = instance.getBlock(x, y, z)
                        if (!block.isAir) count++
                    }
                }
            }
            return count
        }
    }

    /**
     * Starts the region claiming process for a player
     * @param player The player who is claiming a region
     * @param regionName The name of the region being claimed
     * @return True if claiming process started successfully, false if region already exists
     */
    suspend fun startRegionClaiming(player: Player, regionName: String): Boolean {

        // Check if player is already claiming a region
        if (player.getTag(CLAIMING_PROCESS) == true) {
            // Cancel previous claim first
            cancelRegionClaiming(player)
        }

        // Validate region name
        if (regionName.length < 3 || regionName.length > 32) {
            return false
        }

        // Only allow alphanumeric characters and underscores
        if (!regionName.matches(Regex("^[a-zA-Z0-9_]+$"))) {
            return false
        }

        // Set the tags to mark the player as claiming
        player.setTag(CLAIMING_PROCESS, true)
        player.setTag(CLAIMING_NAME, regionName)

        // Track the player as claiming
        claimingPlayerSet.add(player.uuid)

        // Launch a coroutine to give the wand to the player
        minecraftServer.scope.launch {
            // Give the player the region selection wand
            player.inventory.addItemStack(REGION_WAND)

            // Start visualization of current selection if any
            visualizeSelection(player)
        }

        return true
    }

    /**
     * Cancels the region claiming process for a player
     * @param player The player who is canceling the claim
     * @return The name of the canceled region, or null if player wasn't claiming
     */
    suspend fun cancelRegionClaiming(player: Player): String? {
        // Quick check if player is in the claiming player set
        if (!claimingPlayerSet.contains(player.uuid)) {
            return null
        }

        val regionName = player.getTag(CLAIMING_NAME) ?: return null

        // Clean up player state asynchronously
        minecraftServer.scope.launch {
            // Clear the claiming tags
            player.removeTag(CLAIMING_PROCESS)
            player.removeTag(CLAIMING_NAME)
            player.removeTag(POSITION1)
            player.removeTag(POSITION2)

            // Remove from claiming player set
            claimingPlayerSet.remove(player.uuid)

            // Cancel any active visualization
            cancelVisualization(player)
        }

        return regionName
    }



    // Maps for tracking player region state
    private val playerCurrentRegions = mutableMapOf<UUID, MutableSet<Region>>()
    private val lastPositionChecks = mutableMapOf<UUID, Pos>()

    /**
     * Gets all regions that contain the given position
     */
    fun getRegionsAt(worldName: String?, position: Pos): List<Region> {
        return regions[worldName]?.values?.filter { it.isInRegion(position) } ?: emptyList()
    }

    init {
        loadAllRegions()

        val POSITION_CHECK_THRESHOLD = 1.0 // Skip movements smaller than 0.5 blocks squared


        MinecraftServer.getGlobalEventHandler().addListener(PrepareAttackEvent::class.java) { event ->
            if (event.entity is Player && event.target is Player) {
                val attacker = event.entity as Player
                val victim = event.target

                // Get the current world name
                val worldName = worlds.getWorldNameFromInstance(attacker.instance)

                // Get regions from this world
                val worldRegions = regions[worldName]

                // Check if either player is in a safezone
                val attackerPos = attacker.position
                val victimPos = victim.position

                // Check if any region with safezone flag contains either player
                val attackerInSafezone = worldRegions?.values?.any {
                    it.hasFlag("safezone") && it.isInRegion(attackerPos)
                }

                val victimInSafezone = worldRegions?.values?.any {
                    it.hasFlag("safezone") && it.isInRegion(victimPos)
                }

                // Cancel the attack if either player is in a safezone
                if (attackerInSafezone == true || victimInSafezone == true) {
                    event.isCancelled = true

                    // Notify attacker (only if they're the one in safezone)
                    if (attackerInSafezone == true) {
                        attacker.sendMessage(
                            Component.text("You cannot attack players in a safezone.")
                                .color(NamedTextColor.RED)
                        )
                    } else {
                        attacker.sendMessage(
                            Component.text("That player is in a safezone.")
                                .color(NamedTextColor.RED)
                        )
                    }
                }
            }
        }

        MinecraftServer.getGlobalEventHandler().addSuspendingListener(minecraftServer, PlayerMoveEvent::class.java) { event ->
            val player = event.player
            val newPosition = event.newPosition

            // Skip processing for very small movements (optimization)
            val lastPos = lastPositionChecks[player.uuid]
            if (lastPos != null) {
                val distSquared = lastPos.distanceSquared(newPosition)
                // Skip if movement is too small to matter
                if (distSquared < POSITION_CHECK_THRESHOLD) {
                    return@addSuspendingListener
                }
            }

            // Update last checked position
            lastPositionChecks[player.uuid] = newPosition

            // Get the world name
            val worldName = worlds.getWorldNameFromInstance(player.instance) ?: return@addSuspendingListener

            // Get regions at the player's new position
            val regionsAtNewPosition = getRegionsAt(worldName, newPosition)

            // Get or initialize the set of regions the player is currently in
            val currentRegions = playerCurrentRegions.getOrPut(player.uuid) { mutableSetOf() }

            // Find regions player entered
            val enteredRegions = regionsAtNewPosition.filter { it !in currentRegions }

            // Find regions player left
            val leftRegions = currentRegions.filter { it !in regionsAtNewPosition }

            // Skip if no region changes detected
            if (enteredRegions.isEmpty() && leftRegions.isEmpty()) {
                return@addSuspendingListener
            }

            // Check if we should show messages (throttle region message spam)
            val currentTime = System.currentTimeMillis()

            // Track region changes regardless of message display
            var regionsChanged = false

            // Handle entered regions
            for (region in enteredRegions) {
                // Add to current regions set
                currentRegions.add(region)
                regionsChanged = true

                // Only show messages if not in cooldown and region has notify flag
                if (region.hasFlag("notify")) {
                    // Create message with appropriate color based on safezone status
                    val color = if (region.hasFlag("safezone")) NamedTextColor.GREEN else NamedTextColor.GOLD

                    player.sendMessage(
                        Component.text("Entering: ")
                            .color(NamedTextColor.YELLOW)
                            .append(Component.text(region.name).color(color))
                    )
                }
            }

            // Handle left regions
            for (region in leftRegions) {
                // Remove from current regions set
                currentRegions.remove(region)
                regionsChanged = true

                // Only show messages if region has notify flag
                if (region.hasFlag("notify")) {
                    // Create message with appropriate color based on safezone status
                    val color = if (region.hasFlag("safezone")) NamedTextColor.GREEN else NamedTextColor.GOLD

                    player.sendMessage(
                        Component.text("Leaving: ")
                            .color(NamedTextColor.YELLOW)
                            .append(Component.text(region.name).color(color))
                    )
                }
            }

            // Update the player's current regions in the tracking map
            if (currentRegions.isEmpty()) {
                // Remove the entry if no regions (to save memory)
                playerCurrentRegions.remove(player.uuid)
            } else if (regionsChanged) {
                // Only update if the set actually changed
                playerCurrentRegions[player.uuid] = currentRegions
            }
        }


        // Event node for players in claiming mode
        val claimingPlayers = EventNode.value<PlayerEvent?, Player?>(
            "claiming-players",
            EventFilter.PLAYER,
            Predicate { player ->
                player != null && player.getTag(CLAIMING_PROCESS) ?: false
            }
        ).setPriority(20)

        // Listen for item drop events to cancel claiming when the wand is dropped
        claimingPlayers.addSuspendingListener(minecraftServer, ItemDropEvent::class.java) { event ->
            val player = event.player
            val itemStack = event.itemStack

            // Only handle golden hoe wand drop
            if (itemStack.material() != Material.GOLDEN_HOE) {
                return@addSuspendingListener
            }

            // Cancel the claiming process
            player.removeTag(CLAIMING_PROCESS)
            player.removeTag(CLAIMING_NAME)
            player.removeTag(POSITION1)
            player.removeTag(POSITION2)

            // Cancel any active visualization
            cancelVisualization(player)

            // Cancel the event to prevent the item from dropping
            event.isCancelled = true
            player.itemInMainHand = ItemStack.of(Material.AIR)

            player.sendMessage(
                Component.text("Region claiming cancelled.")
                    .color(NamedTextColor.YELLOW)
            )
        }

        // Listen for left-click on block with wand to set position 1
        claimingPlayers.addSuspendingListener(minecraftServer, PlayerStartDiggingEvent::class.java) { event ->
            val player = event.player
            val itemInMainHand = player.itemInMainHand

            // Only handle golden hoe wand
            if (itemInMainHand.material() != Material.GOLDEN_HOE) {
                return@addSuspendingListener
            }

            val blockPos = event.blockPosition
            val posString = "${blockPos.x()},${blockPos.y()},${blockPos.z()}"

            player.setTag(POSITION1, posString)

            player.sendMessage(
                Component.text("Position 1 set at ")
                    .color(NamedTextColor.YELLOW)
                    .append(Component.text("(${blockPos.x()}, ${blockPos.y()}, ${blockPos.z()})").color(NamedTextColor.GREEN))
            )

            // Update visualization after setting position
            visualizeSelection(player)
        }

        // Listen for shift + left-click on air with wand to confirm selection
        claimingPlayers.addSuspendingListener(minecraftServer, PlayerHandAnimationEvent::class.java) { event ->
            val player = event.player
            val itemInMainHand = player.itemInMainHand

            // Only handle golden hoe wand
            if (itemInMainHand.material() != Material.GOLDEN_HOE) {
                return@addSuspendingListener
            }

            // Only handle confirming when player is sneaking
            if (!player.isSneaking) {
                return@addSuspendingListener
            }

            val regionName = player.getTag(CLAIMING_NAME) ?: return@addSuspendingListener
            val pos1String = player.getTag(POSITION1)
            val pos2String = player.getTag(POSITION2)

            if (pos1String == null || pos2String == null) {
                player.sendMessage(
                    Component.text("You need to set both positions first.")
                        .color(NamedTextColor.RED)
                )
                return@addSuspendingListener
            }

            val pos1 = parsePosition(pos1String)
            val pos2 = parsePosition(pos2String)

            if (pos1 == null || pos2 == null) {
                player.sendMessage(
                    Component.text("Invalid position data. Please re-select positions.")
                        .color(NamedTextColor.RED)
                )
                return@addSuspendingListener
            }

            // Get current world name from the player's instance
            val instance = player.instance
            val worldName = worlds.getWorldNameFromInstance(instance)
            if (worldName == null) {
                player.sendMessage(
                    Component.text("Cannot create region: Unknown world.")
                        .color(NamedTextColor.RED)
                )
                return@addSuspendingListener
            }

            // Create region with the proper parameters
            val region = createRegion(worldName, regionName, pos1, pos2)

            // Cancel visualization now that creation is complete
            cancelVisualization(player)


            // Clear tags
            player.removeTag(CLAIMING_PROCESS)
            player.removeTag(CLAIMING_NAME)
            player.removeTag(POSITION1)
            player.removeTag(POSITION2)


            // Calculate region size
            val volume = region.getVolume()
            val (min, max) = region.getMinMaxPositions()

            removeWandFromInventory(player)
            player.sendMessage(
                Component.text("Region ").color(NamedTextColor.GREEN)
                    .append(Component.text(regionName).color(NamedTextColor.GOLD))
                    .append(Component.text(" has been created!").color(NamedTextColor.GREEN))
            )


            player.sendMessage(
                Component.text("Size: ").color(NamedTextColor.YELLOW)
                    .append(Component.text("${volume} blocks").color(NamedTextColor.AQUA))
            )

            player.sendMessage(
                Component.text("Bounds: ").color(NamedTextColor.YELLOW)
                    .append(Component.text("(${min.x().toInt()}, ${min.y().toInt()}, ${min.z().toInt()}) to (${max.x().toInt()}, ${max.y().toInt()}, ${max.z().toInt()})").color(NamedTextColor.AQUA))
            )
        }

        // Listen for right-click on block with wand to set position 2
        claimingPlayers.addSuspendingListener(minecraftServer, PlayerBlockInteractEvent::class.java) { event ->
            val player = event.player
            val itemInMainHand = player.itemInMainHand

            // Only handle golden hoe wand
            if (itemInMainHand.material() != Material.GOLDEN_HOE) {
                return@addSuspendingListener
            }

            // Prevent duplicate events with cooldown
            val currentTime = System.currentTimeMillis()
            val lastTime = recentInteractions[player.uuid] ?: 0L

            if (currentTime - lastTime < INTERACTION_COOLDOWN) {
                // Too soon after last interaction
                return@addSuspendingListener
            }

            // Update the interaction time
            recentInteractions[player.uuid] = currentTime

            // Set position 2 at the clicked block position
            val blockPos = event.blockPosition
            val posString = "${blockPos.x()},${blockPos.y()},${blockPos.z()}"

            player.setTag(POSITION2, posString)

            player.sendMessage(
                Component.text("Position 2 set at ")
                    .color(NamedTextColor.YELLOW)
                    .append(Component.text("(${blockPos.x()}, ${blockPos.y()}, ${blockPos.z()})").color(NamedTextColor.GREEN))
            )

            // Update visualization after setting position 2
            visualizeSelection(player)

            // Cancel the event to prevent normal block interaction
            event.isCancelled = true
        }

        // Listen for right-click in air with wand to clear selection
        claimingPlayers.addSuspendingListener(minecraftServer, PlayerUseItemEvent::class.java) { event ->
            val player = event.player
            val itemInMainHand = player.itemInMainHand

            // Only handle golden hoe wand
            if (itemInMainHand.material() != Material.GOLDEN_HOE) {
                return@addSuspendingListener
            }

            // Prevent duplicate events with cooldown
            val currentTime = System.currentTimeMillis()
            val lastTime = recentInteractions[player.uuid] ?: 0L

            if (currentTime - lastTime < INTERACTION_COOLDOWN) {
                // Too soon after last interaction
                return@addSuspendingListener
            }

            // Update the interaction time
            recentInteractions[player.uuid] = currentTime

            // Check if the player is not targeting a block
            val targetPos = player.getTargetBlockPosition(6)

            if (targetPos == null) {
                // Not targeting a block - clear selection
                player.removeTag(POSITION1)
                player.removeTag(POSITION2)

                // Cancel any active visualization
                cancelVisualization(player)

                player.sendMessage(
                    Component.text("Selection cleared!")
                        .color(NamedTextColor.YELLOW)
                )
            }

            // Cancel the event to prevent normal item usage
            event.isCancelled = true
        }

        // Add the claiming event node to the global handler
        MinecraftServer.getGlobalEventHandler().addChild(claimingPlayers)
    }

    /**
     * Visualizes a player's current selection using particles
     * @param player The player whose selection to visualize
     */
    suspend fun visualizeSelection(player: Player) {
        val pos1String = player.getTag(POSITION1)
        val pos2String = player.getTag(POSITION2)

        // Get the positions from tags, skip if one is missing
        val pos1 = pos1String?.let { parsePosition(it) }
        val pos2 = pos2String?.let { parsePosition(it) }

        // If no positions are set, don't try to visualize
        if (pos1 == null && pos2 == null) {
            return
        }

        // Prepare regions to visualize
        val regions = mutableListOf<Pair<Pos, Pos>>()

        // If both positions are set, show the box outline
        if (pos1 != null && pos2 != null) {
            val minX = Math.min(pos1.x(), pos2.x())
            val minY = Math.min(pos1.y(), pos2.y())
            val minZ = Math.min(pos1.z(), pos2.z())
            val maxX = Math.max(pos1.x(), pos2.x())
            val maxY = Math.max(pos1.y(), pos2.y())
            val maxZ = Math.max(pos1.z(), pos2.z())

            regions.add(Pair(Pos(minX, minY, minZ), Pos(maxX, maxY, maxZ)))
        }

        // Visualize the selection boxes
        visualizeBoxes(player, regions)
    }

    /**
     * Cancels the visualization task for a player
     */
    suspend fun cancelVisualization(player: Player) {
        val job = visualizationJobs.remove(player.uuid) ?: return
        job.cancel() // Cancel the coroutine job

        // Send empty particles to clear existing ones - all jobs are now canceled in visualizationJobs
    }

    /**
     * Common visualization function for regions and selections
     * @param player The player to show visualization to
     * @param regions List of regions to visualize
     * @param duration Duration in milliseconds to show visualization (0 for indefinite)
     * @param particleType The particle type to use for region outlines
     */
    private suspend fun visualizeBoxes(
        player: Player,
        regions: List<Pair<Pos, Pos>>,
        duration: Long = 0,
        particleType: Particle = Particle.END_ROD
    ) {
        // Cancel any existing visualization for this player
        cancelVisualization(player)

        if (regions.isEmpty()) {
            return
        }

        // Store the task ID for later cancellation
        val job = minecraftServer.scope.launch {
            try {
                // Calculate end time if duration is specified
                val endTime = if (duration > 0) System.currentTimeMillis() + duration else Long.MAX_VALUE

                // Keep running until cancelled or duration expires
                while (System.currentTimeMillis() < endTime) {
                    // Visualize each region
                    for ((min, max) in regions) {
                        val minX = min.x()
                        val minY = min.y()
                        val minZ = min.z()
                        val maxX = max.x()
                        val maxY = max.y()
                        val maxZ = max.z()

                        // Calculate step sizes for edges based on box size
                        val xSize = maxX - minX
                        val ySize = maxY - minY
                        val zSize = maxZ - minZ
                        val xStep = Math.max(0.5, xSize / MAX_POINTS)
                        val yStep = Math.max(0.5, ySize / MAX_POINTS)
                        val zStep = Math.max(0.5, zSize / MAX_POINTS)

                        // Draw horizontal edges (parallel to X-axis)
                        for (x in generateSequence(minX) { it + xStep }.takeWhile { it <= maxX }) {
                            // Bottom edges
                            player.sendPacket(ParticlePacket(
                                particleType,
                                Pos(x, minY, minZ),
                                Vec(0.0, 0.0, 0.0),
                                0.0f, 1
                            ))
                            player.sendPacket(ParticlePacket(
                                particleType,
                                Pos(x, minY, maxZ),
                                Vec(0.0, 0.0, 0.0),
                                0.0f, 1
                            ))

                            // Top edges
                            player.sendPacket(ParticlePacket(
                                particleType,
                                Pos(x, maxY, minZ),
                                Vec(0.0, 0.0, 0.0),
                                0.0f, 1
                            ))
                            player.sendPacket(ParticlePacket(
                                particleType,
                                Pos(x, maxY, maxZ),
                                Vec(0.0, 0.0, 0.0),
                                0.0f, 1
                            ))
                        }

                        // Draw horizontal edges (parallel to Z-axis)
                        for (z in generateSequence(minZ) { it + zStep }.takeWhile { it <= maxZ }) {
                            // Bottom edges
                            player.sendPacket(ParticlePacket(
                                particleType,
                                Pos(minX, minY, z),
                                Vec(0.0, 0.0, 0.0),
                                0.0f, 1
                            ))
                            player.sendPacket(ParticlePacket(
                                particleType,
                                Pos(maxX, minY, z),
                                Vec(0.0, 0.0, 0.0),
                                0.0f, 1
                            ))

                            // Top edges
                            player.sendPacket(ParticlePacket(
                                particleType,
                                Pos(minX, maxY, z),
                                Vec(0.0, 0.0, 0.0),
                                0.0f, 1
                            ))
                            player.sendPacket(ParticlePacket(
                                particleType,
                                Pos(maxX, maxY, z),
                                Vec(0.0, 0.0, 0.0),
                                0.0f, 1
                            ))
                        }

                        // Draw vertical edges
                        for (y in generateSequence(minY) { it + yStep }.takeWhile { it <= maxY }) {
                            player.sendPacket(ParticlePacket(
                                particleType,
                                Pos(minX, y, minZ),
                                Vec(0.0, 0.0, 0.0),
                                0.0f, 1
                            ))
                            player.sendPacket(ParticlePacket(
                                particleType,
                                Pos(maxX, y, minZ),
                                Vec(0.0, 0.0, 0.0),
                                0.0f, 1
                            ))
                            player.sendPacket(ParticlePacket(
                                particleType,
                                Pos(minX, y, maxZ),
                                Vec(0.0, 0.0, 0.0),
                                0.0f, 1
                            ))
                            player.sendPacket(ParticlePacket(
                                particleType,
                                Pos(maxX, y, maxZ),
                                Vec(0.0, 0.0, 0.0),
                                0.0f, 1
                            ))
                        }
                    }

                    // Wait before the next update
                    kotlinx.coroutines.delay(200)
                }
            } catch (e: Exception) {
                // Handle any exceptions that might occur during visualization
                println("Error in visualization: ${e.message}")
            }
        }

        visualizationJobs[player.uuid] = job
    }

    /**
     * Utility method to remove the selection wand from a player's inventory
     */
    suspend fun removeWandFromInventory(player: Player) {
        // Check all inventory slots for the wand
        val inventory = player.inventory
        for (i in 0 until inventory.size) {
            val item = inventory.getItemStack(i)
            if (item.material() == Material.GOLDEN_HOE) {
                inventory.setItemStack(i, ItemStack.AIR)
                break
            }
        }

        // Also check the main and off hands
        if (player.itemInMainHand.material() == Material.GOLDEN_HOE) {
            player.setItemInMainHand(ItemStack.AIR)
        }

        if (player.itemInOffHand.material() == Material.GOLDEN_HOE) {
            player.setItemInOffHand(ItemStack.AIR)
        }
    }

    /**
     * Visualize multiple regions for a player
     * @param player The player to show the visualization to
     * @param regions The regions to visualize
     */
    suspend fun visualizeRegions(player: Player, regions: List<Region>) {
        // Transform regions to pairs of min/max positions and set different particles based on safezone status
        val boxesToVisualize = regions.map { region ->
            val (min, max) = region.getMinMaxPositions()
            val particle = Particle.FLAME

            Triple(min, max, particle)
        }

        // Group regions by particle type to visualize them efficiently
        val groupedBoxes = boxesToVisualize.groupBy { it.third }

        // Visualize each group with its respective particle type
        groupedBoxes.forEach { (particleType, boxes) ->
            val positionPairs = boxes.map { Pair(it.first, it.second) }
            visualizeBoxes(player, positionPairs, 30000, particleType)  // Show for 30 seconds
        }
    }

    /**
     * Creates a new region in the specified world
     * @param worldName The name of the world
     * @param name The name of the region
     * @param pos1 The first position of the region
     * @param pos2 The second position of the region
     * @return The created region
     */
    suspend fun createRegion(worldName: String, name: String, pos1: Point, pos2: Point): Region {
        // Ensure the world exists in our map
        if (worldName !in regions) {
            regions[worldName] = mutableMapOf()
        }

        // Create a new region with a unique ID
        val id = UUID.randomUUID()
        val region = Region(id, name, pos1, pos2)

        // Add it to our map
        regions[worldName]!![id] = region

        // Save all regions to storage
        saveRegions(worldName)

        if (name.contains("_mine")) {
            mineManager?.finalizeMineCreation(worldName, name, region.id)
        }

        logger.info("Created region '$name' in world '$worldName'")
        return region
    }

    /**
     * Deletes a region from a world
     * @param worldName The name of the world
     * @param regionId The ID of the region to delete
     * @return true if the region was deleted, false if it wasn't found
     */
    suspend fun deleteRegion(worldName: String?, regionId: UUID): Boolean {
        // Check if the world and region exist
        if (worldName !in regions || regionId !in regions[worldName]!!) {
            return false
        }

        // Remove the region from our map
        val region = regions[worldName]!!.remove(regionId)

        // Save all regions to storage
        saveRegions(worldName)

        logger.info("Deleted region '${region?.name}' from world '$worldName'")
        return true
    }

    /**
     * Gets all regions in a world
     * @param worldName The name of the world
     * @return List of regions in the world
     */
    suspend fun getRegions(worldName: String?): List<Region> {
        return regions[worldName]?.values?.toList() ?: emptyList()
    }

    /**
     * Gets a region by its ID
     * @param worldName The name of the world
     * @param regionId The ID of the region
     * @return The region, or null if not found
     */
    suspend fun getRegion(worldName: String?, regionId: UUID): Region? {
        return regions[worldName]?.get(regionId)
    }

    /**
     * Updates an existing region
     * @param worldName The name of the world
     * @param region The region to update
     * @return true if the update was successful
     */
    suspend fun updateRegion(worldName: String, region: Region): Boolean {
        // Check if the world and region exist
        if (worldName !in regions || region.id !in regions[worldName]!!) {
            return false
        }

        // Update the region in our map
        regions[worldName]!![region.id] = region

        // Save all regions to storage
        saveRegions(worldName)

        return true
    }

    suspend fun getNearbyRegions(player: Player, distance: Double): List<Region> {
        val worldName = worlds.getWorldNameFromInstance(player.instance)
        val position = player.position

        val result = mutableListOf<Region>()
        val distanceSquared = distance * distance

        // Get regions for this world
        val worldRegions = regions[worldName] ?: return emptyList()

        for (region in worldRegions.values) {
            val (min, max) = region.getMinMaxPositions()

            // Calculate the closest point on the region's bounding box to the given position
            val closestX = position.x().coerceIn(min.x(), max.x())
            val closestY = position.y().coerceIn(min.y(), max.y())
            val closestZ = position.z().coerceIn(min.z(), max.z())

            // Calculate distance from the position to the closest point on the region
            val dx = position.x() - closestX
            val dy = position.y() - closestY
            val dz = position.z() - closestZ

            val distSq = dx * dx + dy * dy + dz * dz

            // If this region is within the specified distance, add it to the result
            if (distSq <= distanceSquared) {
                result.add(region)
            }
        }

        return result
    }

    /**
     * Gets the file where regions are stored for a world
     * @param worldName The name of the world
     * @return The regions.yml File
     */
    private suspend fun getRegionsFile(worldName: String): File {
        val worldDir = File(worlds.worldsDir, worldName)
        if (!worldDir.exists()) {
            worldDir.mkdir()
        }
        return File(worldDir, "regions.yml")
    }

    /**
     * Saves all regions for a world to storage
     * @param worldName The name of the world
     */
    private suspend fun saveRegions(worldName: String?) {
        val worldRegions = regions[worldName] ?: return
        val regionsFile = getRegionsFile(worldName as String)

        // Convert regions to a map for YAML storage
        val regionsData = mutableMapOf<String, Any>()
        val regionsList = mutableListOf<Map<String, Any>>()

        worldRegions.values.forEach { region ->
            val regionMap = mutableMapOf<String, Any>(
                "id" to region.id.toString(),
                "name" to region.name,
                "pos1" to mapOf(
                    "x" to region.pos1.x(),
                    "y" to region.pos1.y(),
                    "z" to region.pos1.z()
                ),
                "pos2" to mapOf(
                    "x" to region.pos2.x(),
                    "y" to region.pos2.y(),
                    "z" to region.pos2.z()
                ),
                "flags" to region.flags.toList()
            )

            regionsList.add(regionMap)
        }

        regionsData["regions"] = regionsList

        // Save to YAML
        twizzy.tech.util.YamlFactory.saveConfig(regionsData, regionsFile)
        logger.debug("Saved ${worldRegions.size} regions for world $worldName")
    }

    /**
     * Loads all regions from storage
     */
    private fun loadAllRegions() {
        // Clear existing regions
        regions.clear()

        // For each world directory
        worlds.worldsDir.listFiles()?.filter { it.isDirectory }?.forEach { worldDir ->
            val worldName = worldDir.name
            val regionsFile = File(worldDir, "regions.yml")

            // Skip if the regions file doesn't exist
            if (!regionsFile.exists()) {
                return@forEach
            }

            try {
                // Load YAML
                val regionsData = twizzy.tech.util.YamlFactory.loadConfig(regionsFile)

                // Initialize the regions map for this world
                regions[worldName] = mutableMapOf()

                // Get the list of regions
                @Suppress("UNCHECKED_CAST")
                val regionsList = regionsData.getOrDefault("regions", emptyList<Map<String, Any>>()) as List<Map<String, Any>>

                // Parse each region
                regionsList.forEach { regionData ->
                    try {
                        val id = UUID.fromString(regionData["id"] as String)
                        val name = regionData["name"] as String

                        // Parse positions
                        val pos1Map = regionData["pos1"] as Map<*, *>
                        val pos2Map = regionData["pos2"] as Map<*, *>

                        val pos1 = Pos(
                            (pos1Map["x"] as Number).toDouble(),
                            (pos1Map["y"] as Number).toDouble(),
                            (pos1Map["z"] as Number).toDouble()
                        )

                        val pos2 = Pos(
                            (pos2Map["x"] as Number).toDouble(),
                            (pos2Map["y"] as Number).toDouble(),
                            (pos2Map["z"] as Number).toDouble()
                        )

                        // Parse flags
                        @Suppress("UNCHECKED_CAST")
                        val flagsData = regionData["flags"]
                        val flags = mutableSetOf<String>()

                        // Handle different ways flags might be stored in the YAML
                        when (flagsData) {
                            is List<*> -> {
                                // List of strings
                                flagsData.forEach { flag ->
                                    if (flag is String) {
                                        flags.add(flag.lowercase())
                                    }
                                }
                            }
                            is Map<*, *> -> {
                                // Convert from old format: Map<String, Boolean>
                                flagsData.forEach { (key, value) ->
                                    if (key is String && (value == null || value == true)) {
                                        flags.add(key.lowercase())
                                    }
                                }
                            }
                        }

                        // Create region object
                        val region = Region(id, name, pos1, pos2, flags)

                        // Add to our map
                        regions[worldName]!![id] = region

                    } catch (e: Exception) {
                        logger.error("Failed to load region from regions.yml: ${e.message}")
                    }
                }

                logger.info("Loaded ${regions[worldName]?.size ?: 0} regions from world '$worldName'")

            } catch (e: Exception) {
                logger.error("Failed to load regions from file ${regionsFile.name}", e)
            }
        }

        logger.info("Loaded regions for ${regions.size} worlds")
    }

    /**
     * Parse position from string format "x,y,z"
     */
    private suspend fun parsePosition(posString: String): Pos? {
        return try {
            val parts = posString.split(",")
            if (parts.size != 3) return null

            val x = parts[0].toDouble()
            val y = parts[1].toDouble()
            val z = parts[2].toDouble()

            Pos(x, y, z)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Toggles a flag on a region in a world
     * @param worldName The name of the world
     * @param regionId The ID of the region
     * @param flag The flag to toggle
     * @return true if the flag was added, false if it was removed, null if the region wasn't found
     */
    suspend fun toggleRegionFlag(worldName: String, regionId: UUID, flag: String): Boolean? {
        // Check if the world and region exist
        if (worldName !in regions || regionId !in regions[worldName]!!) {
            return null
        }

        // Get the region
        val region = regions[worldName]!![regionId]!!

        // Toggle the flag
        val flagAdded = region.toggleFlag(flag)

        // Save all regions to storage
        saveRegions(worldName)

        return flagAdded
    }

    /**
     * Finds a region by name in a world
     * @param worldName The name of the world
     * @param regionName The name of the region (case insensitive)
     * @return The region, or null if not found
     */
    suspend fun findRegionByName(worldName: String, regionName: String): Region? {
        return regions[worldName]?.values?.find { it.name.equals(regionName, ignoreCase = true) }
    }

    /**
     * Sets a region in memory for a world (does not persist to disk)
     * @param worldName The name of the world
     * @param region The region to set
     */
    fun setRegion(worldName: String, region: Region) {
        if (worldName !in regions) {
            regions[worldName] = mutableMapOf()
        }
        regions[worldName]!![region.id] = region
    }
}
