package stomui.views.switching

import eu.koboo.minestom.stomui.api.ViewRegistry

class SwitchOneExampleProvider(registry: ViewRegistry) : SwitchParentProvider(registry, "First View") {

    override fun oppositeViewProvider(): SwitchParentProvider {
        return SwitchTwoExampleProvider(registry)
    }
}
