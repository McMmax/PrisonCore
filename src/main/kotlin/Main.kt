package twizzy.tech

import com.github.shynixn.mccoroutine.minestom.addSuspendingListener
import com.github.shynixn.mccoroutine.minestom.launch
import eu.koboo.minestom.stomui.api.ViewRegistry
import eu.koboo.minestom.stomui.core.MinestomUI
import io.github._4drian3d.signedvelocity.minestom.SignedVelocity
import io.github.togar2.pvp.MinestomPvP
import io.github.togar2.pvp.enchantment.CombatEnchantments
import io.github.togar2.pvp.feature.CombatFeatures
import io.github.togar2.pvp.feature.FeatureType
import io.github.togar2.pvp.feature.provider.DifficultyProvider
import io.github.togar2.pvp.utils.CombatVersion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.lucko.spark.minestom.SparkMinestom
import net.minestom.server.MinecraftServer
import net.minestom.server.entity.attribute.Attribute
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent
import net.minestom.server.event.player.PlayerSpawnEvent
import net.minestom.server.instance.block.rule.BlockPlacementRule
import org.everbuild.blocksandstuff.blocks.BlockBehaviorRuleRegistrations
import org.everbuild.blocksandstuff.blocks.BlockPickup
import org.everbuild.blocksandstuff.blocks.BlockPlacementRuleRegistrations
import org.everbuild.blocksandstuff.blocks.PlacedHandlerRegistration
import org.everbuild.blocksandstuff.blocks.group.VanillaPlacementRules
import org.everbuild.blocksandstuff.fluids.MinestomFluids
import revxrsal.commands.minestom.MinestomLamp
import twizzy.tech.commands.*
import twizzy.tech.game.Engine
import twizzy.tech.game.MineManager
import twizzy.tech.game.RegionManager
import twizzy.tech.game.items.pickaxe.boosters.Backpack
import twizzy.tech.listeners.ChatHandler
import twizzy.tech.listeners.ConnectionHandler
import twizzy.tech.listeners.ItemInteractions
import twizzy.tech.listeners.MapInteractions
import twizzy.tech.player.PlayerData
import twizzy.tech.player.Ranks
import twizzy.tech.util.*
import java.nio.file.Path

var isServerLoaded = false
var version = "0.21"
var author = "TwizzyTech"
lateinit var gameEngine: Engine
lateinit var serverInstance: MinecraftServer
lateinit var viewRegistry: ViewRegistry

suspend fun main() {

    var server = MinecraftServer.init()
    val logger = MinecraftServer.LOGGER


    System.setProperty("minestom.chunk-view-distance", "16")
    System.setProperty("minestom.use-new-chunk-sending", "true")

    System.setProperty("minestom.new-chunk-sending-count-per-interval", "50")
    System.setProperty("minestom.new-chunk-sending-send-interval", "1")

    System.setProperty("minestom.keep-alive-delay", "7") // Keep-alive interval in seconds (10 default)

    println("[PrisonCore/main] Initializing startup on ${Thread.currentThread().name}/${Thread.currentThread().id}")

    // Switches into suspendable scope on startup.
    server.launch {
        try {
            // Create the database configuration file before initializing connections
            val configFile = YamlFactory.createConfigIfNotExists("database.yml", "database.yml")
            YamlFactory.initializeLanguage()
            println("[PrisonCore/main] Successfully retrieved database credentials at: ${configFile.absolutePath}")

            // Now initialize the connections
            val lettuce = LettuceCache.getInstance()
            lettuce.init()
            val mongoStream = MongoStream.getInstance()
            mongoStream.init()

            val jaSync = JaSync.getInstance()
            jaSync.init()

            val ranks = Ranks.getInstance()
            ranks.init()

            // Initialize the Cell Manager
            val cellManager = twizzy.tech.game.cells.CellManager.getInstance()
            cellManager.initialize()
            println("[PrisonCore/main] Cell system initialized successfully")

            // Mark server as loaded after all initialization is complete
            isServerLoaded = true
            println("[PrisonCore/main] Server initialization complete - players can now join!")

            // Continue with the periodic save loop
            while (true) {
                delay(10000 * 60)
                val mongoStream = MongoStream.getInstance()
                val cachedPlayerData = PlayerData.getAllCachedData()

                var savedCount = 0
                cachedPlayerData.forEach { playerData ->
                    try {
                        // Save player data to MongoDB
                        mongoStream.savePlayerData(playerData)

                        savedCount++
                    } catch (e: Exception) {
                        println("[PrisonCore/main] Error saving player data for ${playerData.uuid}: ${e.message}")
                    }
                }

                val onlinePlayers = MinecraftServer.getConnectionManager().onlinePlayers
                for (player in onlinePlayers) {
                    PlayerData.saveInventory(player.uuid, player.inventory)
                    PlayerData.saveBlockpack(player.uuid)
                }


                if (savedCount > 0) {
                    println("[PrisonCore/main] Successfully saved $savedCount/${cachedPlayerData.size} player data records")
                }
            }
        } catch (e: Exception) {
            println("[PrisonCore/main] Error during server initialization: ${e.message}")
            e.printStackTrace()
            // Don't set isServerLoaded to true if initialization fails
        }
    }

    MinestomPvP.init()

    // Create the instance using our Worlds system
    val worldsManager = Worlds()
    val regionManager = RegionManager(worldsManager, server)
    val mineManager = MineManager(worldsManager, server, regionManager)
    regionManager.setMineManager(mineManager)

    gameEngine = Engine(server, regionManager, worldsManager)
    serverInstance = server

    val instanceMap = InstanceMap(worldsManager, regionManager, mineManager)
    worldsManager.setInstanceMap(instanceMap)

    // Set the instanceMap in Engine for global access
    Engine.instanceMap = instanceMap

    val instanceContainer = worldsManager.getSpawnWorld()

    // Add an event callback to specify the spawning instance (and the spawn position)
    val globalEventHandler = MinecraftServer.getGlobalEventHandler()


    globalEventHandler.addListener(AsyncPlayerConfigurationEvent::class.java) { event ->
        val player = event.getPlayer()
        event.spawningInstance = instanceContainer
        player.respawnPoint = worldsManager.getSpawnPoint()
    }

    globalEventHandler.addChild(
        CombatFeatures.getVanilla(CombatVersion.LEGACY, DifficultyProvider.DEFAULT)
            .remove(FeatureType.FALL)
            .remove(FeatureType.ENCHANTMENT)
            .remove(FeatureType.EXHAUSTION)
            .add(CombatFeatures.FAIR_RISING_FALLING_KNOCKBACK)
            .build().createNode());

    globalEventHandler.addSuspendingListener(server, PlayerSpawnEvent::class.java) { event ->
        val player = event.getPlayer()
        MinestomPvP.setLegacyAttack(player, true)

        player.isAllowFlying = true

        player.getAttribute(Attribute.MINING_EFFICIENCY).setBaseValue(100.0) // Set mining speed to 10
        player.getAttribute(Attribute.BLOCK_BREAK_SPEED).setBaseValue(10.0) // Set breaking speed to 10
        player.getAttribute(Attribute.FALL_DAMAGE_MULTIPLIER).setBaseValue(0.0) // Disable fall damage
    }

    val directory = Path.of("spark")
    val spark: SparkMinestom? = SparkMinestom.builder(directory)
        .commands(true) // enables registration of Spark commands
        .permissionHandler({ sender, permission -> true }) // allows all command senders to execute all commands
        .enable()

    // Initialize stomui ViewRegistry
    viewRegistry = MinestomUI.create()
    viewRegistry.enable()
    println("[PrisonCore/main] Stomui ViewRegistry initialized and enabled")

    val lamp = MinestomLamp.builder()
        .build()

    lamp.register(Version())
    lamp.register(Shutdown())
    lamp.register(Gamemode())
    lamp.register(Clear())
    lamp.register(Teleport())
    lamp.register(World(worldsManager))
    lamp.register(Claim(regionManager, worldsManager))
    lamp.register(Mines(regionManager, worldsManager, mineManager, instanceMap))
    lamp.register(Mine(instanceMap))
    lamp.register(Balance())
    lamp.register(Economy())
    lamp.register(Warps(worldsManager))
    lamp.register(Give())
    lamp.register(Withdraw())
    lamp.register(Pay())
    lamp.register(Tokens())
    lamp.register(Souls())
    lamp.register(Fix())
    lamp.register(Activity())
    lamp.register(Help())
    lamp.register(Pickaxe())
    lamp.register(Rename())
    lamp.register(Backpack())
    lamp.register(Rankup())
    lamp.register(Leaderboard())
    lamp.register(Cell())
    lamp.register(Performance())
    lamp.register(Blockshop())

    MapInteractions(server, regionManager, worldsManager)
    ItemInteractions(server, regionManager, worldsManager)

    ConnectionHandler(server, instanceMap)
    ChatHandler(server)


    BlockPlacementRuleRegistrations.registerDefault()
    BlockBehaviorRuleRegistrations.registerDefault()
    PlacedHandlerRegistration.registerDefault()
    BlockPickup.enable()
    MinestomFluids.enableFluids()
    MinestomFluids.enableVanillaFluids()



    server.start("0.0.0.0", 25567)

    SignedVelocity.initialize()



    // Register shutdown task to run code when the server stops
    MinecraftServer.getSchedulerManager().buildShutdownTask {
        println("[PrisonCore/main] Server is shutting down, saving data and closing connections...")

        // Save all cell vaults
        println("[PrisonCore/main] Saving all cell vaults...")
        runBlocking {
            instanceMap.saveAllCellVaults()
        }

        println("[PrisonCore/main] Saving all cached player data to MongoDB...")
        val cachedPlayerData = PlayerData.getAllCachedData()
        val mongoStream = MongoStream.getInstance()

        runBlocking {
            var savedCount = 0
            cachedPlayerData.forEach { playerData ->
                try {
                    // Save player data to MongoDB
                    mongoStream.savePlayerData(playerData)

                    savedCount++
                } catch (e: Exception) {
                    println("[PrisonCore/main] Error saving player data for ${playerData.uuid}: ${e.message}")
                }
            }

            val onlinePlayers = MinecraftServer.getConnectionManager().onlinePlayers
            for (player in onlinePlayers) {
                PlayerData.saveInventory(player.uuid, player.inventory)
                PlayerData.saveBlockpack(player.uuid)
            }


            println("[PrisonCore/main] Successfully saved $savedCount/${cachedPlayerData.size} player data records")
        }

        // Close connections
        val lettuce = LettuceCache.getInstance()
        println("[PrisonCore/main] Closing Redis connection...")
        lettuce.shutdown()

        println("[PrisonCore/main] Closing MongoDB connection...")
        mongoStream.close()

        // Disable stomui ViewRegistry
        viewRegistry.disable()
        println("[PrisonCore/main] Stomui ViewRegistry disabled")

        println("[PrisonCore/main] Graceful shutdown has completed!")
    }
}
