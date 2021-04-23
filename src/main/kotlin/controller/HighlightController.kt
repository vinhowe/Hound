package controller

import org.bukkit.Location
import org.bukkit.entity.Player
import repository.HighlightRepository

// TODO: Break an interface out from this
class HighlightController(private val repository: HighlightRepository) {
    private val highlights = mutableMapOf<Int, Highlight>()

    fun destroy(id: Int) {
        val highlight = highlights.remove(id) ?: return
        repository.destroyEntities(listOf(id), highlight.visibleTo)
    }

    fun destroyMultiple(ids: List<Int>) {
        // This method exists because Minecraft allows sending an array of entity IDs in an entry destroy packet
        val highlights = ids.mapNotNull { highlights.remove(it) }
        if (highlights.isEmpty()) {
            return
        }

        // Just use visibility of first element. destroyMultiple shouldn't be used in cases where visibleTo is different
        // between highlights.
        val visibleTo = highlights[0].visibleTo

        repository.destroyEntities(highlights.map { it.id }, visibleTo)
    }

    fun create(location: Location, type: HighlightType, visibleTo: Set<Player>): Int {
        val id = when (type) {
            HighlightType.SHULKER_BULLET -> repository.createShulkerBullet(location)
            HighlightType.ENDER_PEARL -> repository.createEnderPearl(location)
            HighlightType.WITHER_SKULL -> repository.createWitherSkull(location)
        }
        highlights[id] = Highlight(id, location, visibleTo, type, 0)

        // Passing in a location without a world is a runtime error
        repository.showEntity(id, location.world!!, visibleTo)

        return id
    }

    fun move(id: Int, location: Location) {
        val highlight = highlights[id] ?: return
        val world = location.world ?: return

        // Having the guide teleport every 100th tick (5 seconds) is a hacky way to keep it from drifting
        // TODO: Figure out the right way to handle relative move desync problem (does Minecraft just send a teleport
        //  packet every so often?)
        val teleport = world.gameTime - highlight.lastTeleportTick > 100

        repository.broadcastEntityMove(
            id,
            location,
            highlight.visibleTo,
            teleport
        )

        if (teleport) {
            highlight.lastTeleportTick = world.gameTime
        }
    }
}

private data class Highlight(
    val id: Int,
    // Don't rely on the entity implementation to handle location state because it could be modified internally
    val location: Location,
    val visibleTo: Set<Player>,
    val highlightType: HighlightType,
    var lastTeleportTick: Long
)

// TODO: This doesn't allow passing any extra data to the highlight constructor. It might be a good idea to have a
//  builder pattern of some sort once we support falling blocks, item frames, etc.
enum class HighlightType {
    SHULKER_BULLET, ENDER_PEARL, WITHER_SKULL
}