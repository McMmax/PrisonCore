package twizzy.tech.listeners

import com.github.shynixn.mccoroutine.minestom.addSuspendingListener
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.minestom.server.MinecraftServer
import net.minestom.server.entity.Player
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent
import net.minestom.server.event.player.PlayerDisconnectEvent
import net.minestom.server.event.player.PlayerLoadedEvent
import net.minestom.server.event.player.PlayerSpawnEvent
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import twizzy.tech.isServerLoaded
import twizzy.tech.player.PlayerData
import twizzy.tech.util.InstanceMap
import twizzy.tech.util.LettuceCache
import twizzy.tech.util.MongoStream
import twizzy.tech.gameEngine
import java.util.*
import kotlinx.coroutines.*
import twizzy.tech.commands.Pickaxe
import twizzy.tech.game.items.backpack.Backpack
import twizzy.tech.game.items.containers.PetPouch
import kotlin.collections.mutableListOf

/**
 * Handles player connections and disconnections with proper resource management
 */
class ConnectionHandler(minecraftServer: MinecraftServer, instanceMap: InstanceMap) {

    companion object {
        private val cleanupScope = CoroutineScope(Dispatchers.IO + SupervisorJob() + CoroutineName("PlayerCleanup"))
    }

    init {
        val lettuce = LettuceCache.getInstance()
        val mongoStream = MongoStream.getInstance()

        MinecraftServer.getGlobalEventHandler().addSuspendingListener(minecraftServer,
            AsyncPlayerConfigurationEvent::class.java) { event ->

            if (!isServerLoaded) {
                event.player.kick(Component.text("Server is still loading, please wait...", NamedTextColor.RED))
                return@addSuspendingListener
            }

            val player = event.player
            val profileResult = lettuce.getProfile(player.uuid)

            // Load player data from MongoDB or create new if not exists
            loadPlayerData(player.uuid, player.username)

            setPlayerInventory(player)

            // Load blockpack from YAML
            PlayerData.loadBlockpack(player.uuid)

            instanceMap.createInstance(player, "GTA")

            // Give player a wooden pickaxe only if they don't have any pickaxe in their inventory
            val hasPickaxe = (0 until player.inventory.size).any { slot ->
                val item = player.inventory.getItemStack(slot)
                !item.isAir && item.material().name().contains("pickaxe")
            }

            if (!hasPickaxe) {
                Pickaxe().getPickaxe(player)
            }

            val hasBlockpack = (0 until player.inventory.size).any { slot ->
                val item = player.inventory.getItemStack(slot)
                !item.isAir && item.material().name().contains("nether_star")
            }

            if (!hasBlockpack) {

                val backpack = Backpack(
                    owner = player.uuid.toString(),
                )

                player.inventory.setItemStack(8, backpack.toItemStack())
            }

            val hasPetPouch = (0 until player.inventory.size).any { slot ->
                val item = player.inventory.getItemStack(slot)
                !item.isAir && item.material().name().contains("bundle")
            }

            if (!hasPetPouch) {
                val petPouch = twizzy.tech.game.items.containers.PetPouch(
                    contents = mutableMapOf(),
                    selectedPets = MutableList(PetPouch.MAX_SELECTED_PETS) { null },
                    -1, player.uuid)


                player.inventory.setItemStack(7, petPouch.toItemStack())
            }
        }

        MinecraftServer.getGlobalEventHandler().addSuspendingListener(minecraftServer, PlayerLoadedEvent::class.java) { event ->
            val player = event.player

            // Get scoreboard title from config
            val scoreboardConfig = try {
                twizzy.tech.util.YamlFactory.loadConfig(java.io.File("game/scoreboard.yml"))
            } catch (e: Exception) {
                null
            }

            val titleText = scoreboardConfig?.let { config ->
                val scoreboard = config["scoreboard"] as? Map<*, *>
                val title = scoreboard?.get("title") as? String
                title?.replace("&", "§") ?: "Mythic Prison"
            } ?: "Mythic Prison"

            // Create a sidebar for the player with config title
            val sidebarTitle = Component.text(titleText ?: "", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD)
            val sidebar = twizzy.tech.util.ComponentSidebar(sidebarTitle)
            sidebar.addViewer(player)

            // Store the sidebar for this player
            twizzy.tech.util.ComponentSidebar.setSidebar(player, sidebar)

            // Get player data and update using config
            val playerData = PlayerData.getFromCache(player.uuid)
            if (playerData != null) {
                sidebar.updateFromConfig(playerData)
            } else {
                // Fallback if player data isn't loaded yet - use config or defaults
                try {
                    val fallbackData = PlayerData(player.uuid)
                    sidebar.updateFromConfig(fallbackData)
                } catch (e: Exception) {
                    // Ultimate fallback to hardcoded values
                    sidebar.update(
                        Component.text("Balance: $0", NamedTextColor.GREEN),
                        Component.text("Tokens: 0", NamedTextColor.GOLD),
                        Component.text("Souls: 0", NamedTextColor.LIGHT_PURPLE),
                        Component.text("Blockpack: 0", NamedTextColor.AQUA)
                    )
                }
            }
        }

        MinecraftServer.getGlobalEventHandler().addSuspendingListener(minecraftServer, PlayerSpawnEvent::class.java) { event ->
            val player = event.player

            // Reactivate pet if player had an active pet when they left
            reactivatePlayerPet(player)
        }

        MinecraftServer.getGlobalEventHandler().addSuspendingListener(minecraftServer, PlayerDisconnectEvent::class.java) { event ->
            if (!isServerLoaded) {
                return@addSuspendingListener
            }
            val player = event.player

            // Deactivate any active pets before disconnect
            deactivatePlayerPet(player)

            // Clean up the batching system for this player
            gameEngine.cleanupPlayerBatching(player.uuid)

            // Remove sidebar for this player
            twizzy.tech.util.ComponentSidebar.removeSidebar(player)

            // Save inventory to YAML
            PlayerData.saveInventory(player.uuid, player.inventory)

            // Save blockpack to YAML
            PlayerData.saveBlockpack(player.uuid)

            // Save player data to MongoDB when they disconnect
            savePlayerData(player.uuid, player.username)
        }
    }

    /**
     * Sets a player's inventory from saved YAML data
     * @param player The player whose inventory needs to be set
     */
    suspend fun setPlayerInventory(player: Player) {
        try {
            // Load the inventory from YAML
            val loadedInventory = PlayerData.loadInventory(player.uuid)

            // Clear the player's current inventory first
            player.inventory.clear()

            // Set each item in the player's inventory
            for (i in 0 until loadedInventory.size) {
                val item = loadedInventory.getItemStack(i)
                if (!item.isAir) {
                    player.inventory.setItemStack(i, item)
                }
            }

        } catch (e: Exception) {
            println("Error setting inventory for player ${player.username}: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * Loads player data from MongoDB and stores it in cache
     * @param uuid The player's UUID
     * @param username The player's username (for logging)
     */
    private suspend fun loadPlayerData(uuid: UUID, username: String) {
        try {
            val mongoStream = MongoStream.getInstance()
            val document = mongoStream.getPlayerData(uuid)

            // Convert document to PlayerData
            val playerData = PlayerData.fromDocument(document)

            // Save to in-memory cache
            PlayerData.saveToCache(playerData)
        } catch (e: Exception) {
            println("Failed to load player data for $username: ${e.message}")
            e.printStackTrace()

            // Create default player data if loading fails
            val playerData = PlayerData(uuid)
            PlayerData.saveToCache(playerData)
        }
    }

    /**
     * Saves player data from cache to MongoDB
     * @param uuid The player's UUID
     * @param username The player's username (for logging)
     */
    private suspend fun savePlayerData(uuid: UUID, username: String) {
        try {
            // Get data from cache
            val playerData = PlayerData.getFromCache(uuid)

            if (playerData != null) {
                // Save to MongoDB
                val mongoStream = MongoStream.getInstance()
                mongoStream.savePlayerData(playerData)

                // Remove from cache
                PlayerData.removeFromCache(uuid)
            }
        } catch (e: Exception) {
            println("Failed to save player data for $username: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * Deactivates any active pet for the player
     */
    private fun deactivatePlayerPet(player: Player) {
        try {
            // Get the pet pouch from player inventory
            val pouchItem = player.inventory.getItemStack(7)
            val petPouch = PetPouch.fromItemStack(pouchItem)

            if (petPouch != null && petPouch.activePetSlot != -1) {
                // Only despawn the entity, but keep the activePetSlot so it can be restored on rejoin
                val playerId = player.uuid
                PetPouch.ACTIVE_PET_INSTANCES[playerId]?.onDeactivate()
                PetPouch.ACTIVE_PET_INSTANCES.remove(playerId)
                // Note: We don't call petPouch.deactivatePet() because that clears activePetSlot
                // The activePetSlot should remain so it can be restored when player rejoins

                // IMPORTANT: Save the pouch back to inventory to persist the activePetSlot
                player.inventory.setItemStack(7, petPouch.toItemStack())
            }
        } catch (e: Exception) {
            println("Error deactivating pet for player ${player.username}: ${e.message}")
        }
    }

    /**
     * Reactivates the player's active pet if they had one
     */
    private fun reactivatePlayerPet(player: Player) {
        try {
            // Get the pet pouch from player inventory
            val pouchItem = player.inventory.getItemStack(7)
            val petPouch = PetPouch.fromItemStack(pouchItem)

            if (petPouch != null && petPouch.activePetSlot != -1) {
                val instance = player.instance
                val position = player.position
                petPouch.activatePet(player, petPouch.activePetSlot, instance, position)
            }
        } catch (e: Exception) {
            println("Error reactivating pet for player ${player.username}: ${e.message}")
        }
    }
}