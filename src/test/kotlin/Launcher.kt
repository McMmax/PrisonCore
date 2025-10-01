import dev.rollczi.litecommands.LiteCommands
import dev.rollczi.litecommands.minestom.LiteMinestomFactory
import eu.koboo.minestom.stomui.api.ViewRegistry
import eu.koboo.minestom.stomui.core.MinestomUI
import net.minestom.server.MinecraftServer
import net.minestom.server.command.CommandSender
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.GameMode
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent
import net.minestom.server.extras.MojangAuth
import net.minestom.server.instance.LightingChunk
import net.minestom.server.instance.block.Block
import org.slf4j.LoggerFactory
import stomui.command.CommandComponentForTwoPlayer
import stomui.command.CommandView

object Launcher {
    private val log = LoggerFactory.getLogger(Launcher::class.java)

    @JvmStatic
    fun main(args: Array<String>) {
        val minecraftServer = MinecraftServer.init()
        minecraftServer.start("0.0.0.0", 25567)
        log.info("Server-Version: ${MinecraftServer.VERSION_NAME}")

        val instanceManager = MinecraftServer.getInstanceManager()
        val defaultInstance = instanceManager.createInstanceContainer()
        defaultInstance.setGenerator { unit ->
            unit.modifier().fillHeight(0, 1, Block.GRASS_BLOCK)
        }
        defaultInstance.setChunkSupplier(::LightingChunk)

        val eventHandler = MinecraftServer.getGlobalEventHandler()
        eventHandler.addListener(AsyncPlayerConfigurationEvent::class.java) { event ->
            event.spawningInstance = defaultInstance
            event.player.respawnPoint = Pos(0.0, 1.0, 0.0)
            event.player.gameMode = GameMode.CREATIVE
        }

        // Create a new instance of the ViewRegistry.
        val viewRegistry = MinestomUI.create()

        // Register the listener of stomui
        viewRegistry.enable()

        // If your server shuts down, you can disable view registries by:
        //viewRegistry.disable();
        MinecraftServer.getSchedulerManager().buildShutdownTask { viewRegistry.disable() }

        val liteCommands = LiteMinestomFactory.builder()
            .commands(
                CommandView(viewRegistry),
                CommandComponentForTwoPlayer(viewRegistry)
            )
            .build()
        liteCommands.register()
    }
}
