package stomui.views.switching

import eu.koboo.minestom.stomui.api.ViewRegistry

class SwitchTwoExampleProvider(registry: ViewRegistry) : SwitchParentProvider(registry, "Second View") {

    override fun oppositeViewProvider(): SwitchParentProvider {
        return SwitchOneExampleProvider(registry)
    }
}
