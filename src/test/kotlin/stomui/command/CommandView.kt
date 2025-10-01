package stomui.command

import dev.rollczi.litecommands.annotations.argument.Arg
import dev.rollczi.litecommands.annotations.command.Command
import dev.rollczi.litecommands.annotations.context.Context
import dev.rollczi.litecommands.annotations.execute.Execute
import dev.rollczi.litecommands.annotations.execute.ExecuteDefault
import eu.koboo.minestom.stomui.api.ViewRegistry
import eu.koboo.minestom.stomui.api.component.ViewProvider
import net.minestom.server.entity.Player
import stomui.views.CounterExampleProvider
import stomui.views.CurrentTimeExampleProvider
import stomui.views.SimpleExampleProvider
import stomui.views.other.AllowInteractionExampleProvider
import stomui.views.other.AnnotatedTabExampleProvider
import stomui.views.other.AnvilInputExampleProvider
import stomui.views.pagination.AsyncPageableExampleProvider
import stomui.views.pagination.PageableExampleProvider
import stomui.views.pagination.ScrollableHorizontalExampleProvider
import stomui.views.pagination.ScrollableVerticalExampleProvider
import stomui.views.search.SearchExampleProvider
import stomui.views.switching.SwitchOneExampleProvider
import stomui.views.switching.ViewHistoryExampleProvider

@Command(name = "view")
class CommandView(private val registry: ViewRegistry) {

    companion object {
        private val VIEWS = mapOf<String, (ViewRegistry) -> ViewProvider>(
            "annotated" to ::AnnotatedTabExampleProvider,
            "page" to ::PageableExampleProvider,
            "scrollhorizontal" to ::ScrollableHorizontalExampleProvider,
            "scrollvertical" to ::ScrollableVerticalExampleProvider,
            "switch" to ::SwitchOneExampleProvider,
            "allow" to ::AllowInteractionExampleProvider,
            "anvil" to ::AnvilInputExampleProvider,
            "search" to ::SearchExampleProvider,
            "simple" to ::SimpleExampleProvider,
            "history" to { registry -> ViewHistoryExampleProvider(registry, 1) },
            "counter" to ::CounterExampleProvider,
            "time" to ::CurrentTimeExampleProvider,
            "asyncpage" to ::AsyncPageableExampleProvider
        )
    }

    @ExecuteDefault
    fun onExecuteDefault(@Context player: Player) {
        printUsage(player)
    }

    @Execute
    fun onExecute(@Context player: Player, @Arg("ViewName") viewName: String) {
        val viewSupplier = VIEWS[viewName]
        if (viewSupplier == null) {
            printUsage(player)
            player.sendMessage("View with name $viewName not found.")
            return
        }

        val viewProvider = viewSupplier(registry)
        viewProvider.open(player)
    }

    private fun printUsage(player: Player) {
        player.sendMessage("Usage: /view <name>")
        player.sendMessage("Available views:")
        player.sendMessage(VIEWS.keys.joinToString(", "))
    }
}
