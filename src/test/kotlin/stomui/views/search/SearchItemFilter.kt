package stomui.views.search

import eu.koboo.minestom.stomui.api.pagination.ItemFilter
import net.minestom.server.item.Material

class SearchItemFilter : ItemFilter<Material> {

    private var textInput: String? = null

    fun setTextInput(textInput: String) {
        this.textInput = textInput
    }

    override fun include(item: Material): Boolean {
        // Only blocks please. Why only blocks?
        // To show filtering based on categories or other parameters
        if (item == Material.AIR || !item.isBlock) {
            return false
        }
        // No text input → display all items.
        if (textInput.isNullOrEmpty()) {
            return true
        }
        // We got an input, so we check materials names.
        if (!item.name().lowercase().contains(textInput!!.lowercase())) {
            return false
        }
        // Got that in your name material? Good for you, have fun paginated!
        return true
    }
}
