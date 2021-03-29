import co.aikar.commands.InvalidCommandArgument
import co.aikar.commands.PaperCommandManager
import com.google.common.collect.ImmutableMap
import com.mojang.serialization.MapCodec
import data.PartialMatchModel
import kotlinx.coroutines.flow.MutableSharedFlow
import org.bukkit.*
import org.bukkit.block.Block
import org.bukkit.block.DoubleChest
import org.bukkit.craftbukkit.v1_16_R3.CraftWorld
import org.bukkit.craftbukkit.v1_16_R3.entity.CraftPlayer
import org.bukkit.craftbukkit.v1_16_R3.util.CraftVector
import org.bukkit.entity.Player
import org.bukkit.inventory.BlockInventoryHolder
import org.bukkit.inventory.DoubleChestInventory
import org.bukkit.inventory.Inventory
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.util.Vector
import java.util.*
import kotlin.math.*

class Hound : JavaPlugin() {
    private val staticPlayerHighlightMap = mutableMapOf<UUID, MutableList<StaticHighlightData>>()
    val liveSearchSet = mutableSetOf<UUID>()
    val liveSearchEventFlows = mutableMapOf<UUID, MutableSharedFlow<Material>>()
    private val guidePlayerMap = mutableMapOf<UUID, PlayerGuideData>()

    val searchRadius: Int
        get() {
            return (config.get("search-radius") as Int?)!!
        }
    val highlightDuration: Number
        get() {
            return (config.get("highlight-duration") as Number?)!!
        }

    private val MAX_VISIBLE_STATIC_HIGHLIGHTS = 1000
    private val GUIDE_DISTANCE = 10.0
    private val GUIDE_DISTANCE_VELOCITY_COEFF = 10.0
    private val GUIDE_RISE_DISTANCE = 8.0
    private val GUIDE_ASCENT_LENGTH = 200.0

    override fun onEnable() {
        super.onEnable()
        saveDefaultConfig()
        
        
        val manager = PaperCommandManager(this)
        // manager.enableUnstableAPI("brigadier") // ignore this deprecation, it's fine to use
        manager.usePerIssuerLocale(true, true)
        
        val materials = Material.values()

        val items = materials.filter(Material::isItem)
        val blocks = materials.filter(Material::isBlock)

        manager.commandCompletions.registerStaticCompletion("items", items.map(Material::name).map(String::toLowerCase))

        manager.commandCompletions.registerStaticCompletion("blocks", blocks.map(Material::name).map(String::toLowerCase))

        manager.commandContexts.registerIssuerAwareContext(PartialMatchModel::class.java) { context ->

            val player = context.player ?: throw InvalidCommandArgument("&4You must be an in-game player to use this command.", false)

            val input = context.popFirstArg()

            if (input == null) {

                val handItemType = player.inventory.itemInMainHand.type

                if (handItemType.isAir) {
                    throw InvalidCommandArgument("&4Couldn't find anything in selected slot.")
                }

                PartialMatchModel(handItemType.name.toLowerCase(), handItemType, emptyList())
            } else {

                var exact = null as Material?
                val fuzzy = mutableSetOf<Material>()

                for (item in items) {

                    val name = item.name

                    if (name.equals(input, true)) {
                        exact = item
                    } else if (name.contains(input, true)) {
                        fuzzy += item
                    }
                }

                PartialMatchModel(input, exact, fuzzy)
            }
        }
        
        
        manager.registerCommand(cmds.BlockSearchCommand(this))
        
        
        val chestSearchCommand = ChestSearchCommand(this)
        val liveChestSearchCommand = LiveChestSearchCommand(this)
        val targetCommand = TargetCommand(this)
        val torchGuideCommand = TorchGuideCommand(this)
        getCommand("chound")?.setExecutor(chestSearchCommand)
        getCommand("chound")?.tabCompleter = chestSearchCommand
        getCommand("lhound")?.setExecutor(liveChestSearchCommand)
        getCommand("lhound")?.tabCompleter = liveChestSearchCommand
   
        getCommand("target")?.setExecutor(targetCommand)
        getCommand("torchguide")?.setExecutor(torchGuideCommand)
        server.pluginManager.registerEvents(HoundEvents(this), this)
    }

    override fun onDisable() {
        super.onDisable()
        clearAllStaticHighlights()
        clearAllGuides()
        clearAllLiveHighlights()
    }

    fun clearStaticHighlightsForPlayer(player: OfflinePlayer, sendPacket: Boolean = true) {
        if (!staticPlayerHighlightMap.containsKey(player.uniqueId) || staticPlayerHighlightMap[player.uniqueId]!!.isEmpty() || player !is CraftPlayer) {
            return
        }

        if (sendPacket) {
            val destroyHighlighterPacket =
                net.minecraft.server.v1_16_R3.PacketPlayOutEntityDestroy(
                    *staticPlayerHighlightMap[player.uniqueId]!!.map { it.entityId }.toTypedArray().toIntArray()
                )
            player.handle.playerConnection.sendPacket(destroyHighlighterPacket)
        }

        staticPlayerHighlightMap.remove(player.uniqueId)
    }

    fun clearGuideForPlayer(player: OfflinePlayer) {
        if (!guidePlayerMap.containsKey(player.uniqueId)) {
            return
        }

        val guideData = guidePlayerMap[player.uniqueId]!!

        if (player is Player) {
            val highlightEntity = guideData.highlightEntity
            destroyHighlight(highlightEntity.id, player)
        }

        Bukkit.getServer().scheduler.cancelTask(guideData.runnableId)

        guidePlayerMap.remove(player.uniqueId)
    }

    fun clearLiveSearchForPlayer(player: OfflinePlayer) {
        if (!liveSearchSet.contains(player.uniqueId)) {
            return
        }

        liveSearchSet.remove(player.uniqueId)
        liveSearchEventFlows.remove(player.uniqueId)
    }

    fun clearAllStaticHighlights() {
        for ((uuid, _) in staticPlayerHighlightMap) {
            val player = Bukkit.getPlayer(uuid) ?: Bukkit.getOfflinePlayer(uuid)
            clearStaticHighlightsForPlayer(player, player is Player)
        }
    }

    fun clearAllGuides() {
        for ((uuid, _) in guidePlayerMap) {
            val player = Bukkit.getPlayer(uuid) ?: Bukkit.getOfflinePlayer(uuid)
            clearGuideForPlayer(player)
        }
    }

    fun clearAllLiveHighlights() {
        for (uuid in liveSearchSet) {
            val player = Bukkit.getPlayer(uuid) ?: Bukkit.getOfflinePlayer(uuid)
            clearLiveSearchForPlayer(player)
        }
    }

    private fun destroyHighlight(entityId: Int, player: Player) {
        val destroyHighlighterPacket = net.minecraft.server.v1_16_R3.PacketPlayOutEntityDestroy(entityId)
        (player as CraftPlayer).handle.playerConnection.sendPacket(destroyHighlighterPacket)
    }

    private fun createHighlight(entity: net.minecraft.server.v1_16_R3.Entity, player: Player) {
        val highlightSpawnPacket = net.minecraft.server.v1_16_R3.PacketPlayOutSpawnEntity(entity)
        val highlightMetadataPacket =
            net.minecraft.server.v1_16_R3.PacketPlayOutEntityMetadata(entity.id, entity.dataWatcher, true)

        (player as CraftPlayer).handle.playerConnection.sendPacket(highlightSpawnPacket)
        player.handle.playerConnection.sendPacket(highlightMetadataPacket)
    }

    private fun registerTemporaryStaticHighlightForPlayer(
        entity: net.minecraft.server.v1_16_R3.Entity,
        player: Player,
        duration: Double?
    ) {
        if (!staticPlayerHighlightMap.containsKey(player.uniqueId)) {
            staticPlayerHighlightMap[player.uniqueId] = mutableListOf()
        }

        staticPlayerHighlightMap[player.uniqueId]?.add(
            StaticHighlightData(
                entity.id,
                CraftVector.toBukkit(entity.positionVector).toLocation(player.world)
            )
        )

        if (duration == null) {
            return
        }

        Bukkit.getServer().scheduler.scheduleSyncDelayedTask(this, {
            destroyHighlight(entity.id, player)
            staticPlayerHighlightMap[player.uniqueId]?.removeIf { it.entityId == entity.id }
        }, (duration * 20).toLong())
    }

    fun destroyStaticHighlightsInBlock(player: Player, location: Location) {
        staticPlayerHighlightMap[player.uniqueId]?.removeIf {
            if (it.location.block.location == location.block.location) {
                destroyHighlight(it.entityId, player)
                return@removeIf true
            }
            return@removeIf false
        }
    }

    fun cubeHighlightEntity(
        player: Player,
        location: Location,
    ): net.minecraft.server.v1_16_R3.Entity {
        val highlightLocation = Vector(location.x + 0.5, location.y + 0.25, location.z + 0.5)
        val highlightEntity = net.minecraft.server.v1_16_R3.EntityWitherSkull(
            net.minecraft.server.v1_16_R3.EntityTypes.WITHER_SKULL,
            (player.world as CraftWorld).handle
        )
        highlightEntity.isInvulnerable = true
        highlightEntity.setPositionRaw(highlightLocation.x, highlightLocation.y, highlightLocation.z)
        highlightEntity.isNoGravity = true
        // i -> setGlowing
        highlightEntity.i(true)

        return highlightEntity
    }

    fun circleHighlightEntity(
        player: Player,
        location: Location,
    ): net.minecraft.server.v1_16_R3.Entity {
        val highlightLocation = Vector(location.x + 0.5, location.y + 0.25, location.z + 0.5)
        val highlightEntity =
            net.minecraft.server.v1_16_R3.EntityEnderSignal(
                (player.world as CraftWorld).handle,
                highlightLocation.x,
                highlightLocation.y,
                highlightLocation.z,
            )
        highlightEntity.isNoGravity = true
        // i -> setGlowing
        highlightEntity.i(true)

        return highlightEntity
    }

    fun shulkerBulletHighlightEntity(
        player: Player,
        location: Location,
    ): net.minecraft.server.v1_16_R3.Entity {
        val highlightLocation = Vector(location.x + 0.5, location.y + 0.25, location.z + 0.5)
        val highlightEntity =
            net.minecraft.server.v1_16_R3.EntityShulkerBullet(
                net.minecraft.server.v1_16_R3.EntityTypes.SHULKER_BULLET,
                (player.world as CraftWorld).handle
            )
        highlightEntity.setPosition(highlightLocation.x, highlightLocation.y, highlightLocation.z)
        highlightEntity.isNoGravity = true
        highlightEntity.isInvulnerable = true
        // i -> setGlowing
        highlightEntity.i(true)

        return highlightEntity
    }

    private fun searchContainersForExactMatches(
        material: Material,
        containers: List<Inventory>
    ): ExactMatchingContainersSearchResult {
        var itemCount = 0
        val matches: MutableList<Inventory> = mutableListOf()
        containers.forEach { container ->
            var match = false
            for (item in container) {
                if (item == null || item.type != material) {
                    continue
                }

                itemCount += item.amount

                if (match) {
                    continue
                }

                matches += container
                match = true
            }
        }
        return ExactMatchingContainersSearchResult(matches, itemCount)
    }

    private fun searchContainersForPartialMatches(
        exactMatchMaterial: Material?,
        partialMatchMaterials: List<Material>,
        containers: List<Inventory>,
    ): PartialMatchingContainersSearchResult {
        var exactMatchItemCount = 0
        var partialMatchItemCount = 0
        val exactMatches: MutableList<Inventory> = mutableListOf()
        val partialMatches: MutableList<Inventory> = mutableListOf()

        containers.forEach { container ->
            // Each matching container is either an exact match or a partial match, but not both
            var containerExactMatch = false
            var containerPartialMatch = false
            for (item in container) {
                if (item == null) {
                    continue
                }

                if (item.type == exactMatchMaterial) {
                    exactMatchItemCount += item.amount
                    if (!containerExactMatch) {
                        exactMatches += container
                        containerExactMatch = true
                        if (containerPartialMatch) {
                            partialMatches -= container
                        }
                    }
                    continue
                }

                if (item.type in partialMatchMaterials) {
                    partialMatchItemCount += item.amount
                    if (!containerPartialMatch && !containerExactMatch) {
                        containerPartialMatch = true
                        partialMatches += container
                    }
                }
            }
        }

        return PartialMatchingContainersSearchResult(
            exactMatches,
            partialMatches,
            exactMatchItemCount,
            partialMatchItemCount
        )
    }

    private fun blocksOfTypeInRadius(material: Material, start: Location, radius: Int): List<Block> {
        val blocks = mutableListOf<Block>()

        for (x in -radius..radius) {
            for (y in -radius..radius) {
                for (z in -radius..radius) {
                    val block =
                        Location(start.world, start.x + x, start.y + y, start.z + z).block
                    if (block.type != material) {
                        continue
                    }

                    blocks.add(block)
                }
            }
        }

        return blocks
    }

    private fun containersInRadius(start: Location, radius: Int): List<Inventory> {
        val containers = mutableListOf<Inventory>()

        for (x in -radius..radius) {
            for (y in -radius..radius) {
                for (z in -radius..radius) {
                    val blockLocation =
                        Location(start.world, start.x + x, start.y + y, start.z + z)
                    val blockState = blockLocation.block.state

                    if (blockState !is BlockInventoryHolder) {
                        continue
                    }

                    val blockData = blockState.blockData
                    if (blockData is org.bukkit.block.data.type.Chest && blockData.type != org.bukkit.block.data.type.Chest.Type.SINGLE) {
                        if (blockState.inventory !is DoubleChestInventory || blockState.inventory.holder !is DoubleChest) {
                            continue
                        }
                        containers.add(
                            when (blockData.type) {
                                org.bukkit.block.data.type.Chest.Type.LEFT -> (blockState.inventory as DoubleChestInventory).leftSide
                                org.bukkit.block.data.type.Chest.Type.RIGHT -> (blockState.inventory as DoubleChestInventory).rightSide
                                else -> continue
                            }
                        )
                        continue
                    }

                    containers.add(blockState.inventory)
                }
            }
        }

        return containers
    }

    fun highlightPartialMatchingItemTypesForPlayer(
        exactMatchMaterial: Material?,
        partialMatchMaterials: List<Material>,
        player: Player,
        radius: Int = searchRadius,
        duration: Double? = highlightDuration.toDouble()
    ): PartialMatchingContainersSearchResult? {
        val containers = containersInRadius(player.location, radius)
        val searchResult = searchContainersForPartialMatches(
            exactMatchMaterial,
            partialMatchMaterials,
            containers
        )

        if (searchResult.exactMatches.isEmpty() && searchResult.partialMatches.isEmpty()) {
            return null
        }

        clearStaticHighlightsForPlayer(player)

        // Limit highlighted matches to 1000 to avoid lagging out client
        val highlightedExactMatchCount = min(MAX_VISIBLE_STATIC_HIGHLIGHTS, searchResult.exactMatches.size)
        searchResult.exactMatches.subList(0, highlightedExactMatchCount).forEach {
            val highlightEntity = shulkerBulletHighlightEntity(player, it.location!!)
            createHighlight(highlightEntity, player)
            registerTemporaryStaticHighlightForPlayer(highlightEntity, player, duration)
        }
        searchResult.partialMatches.subList(
            0,
            min(MAX_VISIBLE_STATIC_HIGHLIGHTS - highlightedExactMatchCount, searchResult.partialMatches.size)
        ).forEach {
            val highlightEntity = cubeHighlightEntity(player, it.location!!)
            createHighlight(highlightEntity, player)
            registerTemporaryStaticHighlightForPlayer(highlightEntity, player, duration)
        }

        return searchResult
    }

    fun highlightItemTypeForPlayer(
        material: Material,
        player: Player,
        radius: Int = searchRadius,
        duration: Double? = highlightDuration.toDouble()
    ): ExactMatchingContainersSearchResult? {
        val containers = containersInRadius(player.location, radius)
        val searchResult = searchContainersForExactMatches(material, containers)

        if (searchResult.matches.isEmpty()) {
            return null
        }

        clearStaticHighlightsForPlayer(player)

        searchResult.matches.subList(0, min(MAX_VISIBLE_STATIC_HIGHLIGHTS, searchResult.matches.size)).forEach {
            val highlightEntity = shulkerBulletHighlightEntity(player, it.location!!)
            createHighlight(highlightEntity, player)
            registerTemporaryStaticHighlightForPlayer(highlightEntity, player, duration)
        }

        return searchResult
    }

    fun highlightBlockTypeForPlayer(
        material: Material,
        player: Player,
        radius: Int = searchRadius,
        duration: Double? = highlightDuration.toDouble()
    ): List<Block> {
        val matches = blocksOfTypeInRadius(material, player.location, radius)

        clearStaticHighlightsForPlayer(player)

        matches.subList(0, min(MAX_VISIBLE_STATIC_HIGHLIGHTS, matches.size)).forEach {
            val highlightEntity = shulkerBulletHighlightEntity(player, it.location)
            createHighlight(highlightEntity, player)
            registerTemporaryStaticHighlightForPlayer(highlightEntity, player, duration)
        }

        return matches
    }

    fun createTorchHighlightsForPlayer(player: Player, spacing: Int) {
        clearStaticHighlightsForPlayer(player)
        val distance = 5
        val rayTraceHeight = 10
        for (x in -distance..distance) {
            for (z in -distance..distance) {
                val rayTracePosition = Location(
                    player.world,
                    player.location.blockX + (x * spacing).toDouble(),
                    player.location.blockY.toDouble() + rayTraceHeight / 2,
                    player.location.blockZ + (z * spacing).toDouble()
                )
                val rayTraceResult = player.world.rayTraceBlocks(
                    rayTracePosition,
                    Vector(0, -1, 0),
                    rayTraceHeight.toDouble(),
                    FluidCollisionMode.NEVER,
                    true
                )
                val y: Double =
                    rayTraceResult?.hitPosition?.blockY?.toDouble() ?: continue
                var highlightLocation = Location(player.world, rayTracePosition.x, y, rayTracePosition.z)

                if (highlightLocation.block.isLiquid) {
                    continue
                }
                highlightLocation = highlightLocation.add(Vector(0, -1, 0))
                if (highlightLocation.block.isLiquid) {
                    continue
                }

                val highlightEntity = shulkerBulletHighlightEntity(
                    player, highlightLocation
                )
                createHighlight(highlightEntity, player)
                registerTemporaryStaticHighlightForPlayer(highlightEntity, player, 1200.0)
            }
        }
    }

    private fun guideRestingY(player: Player, targetX: Double, targetZ: Double, riseDistance: Double): Double {
        return if (player.world.name.endsWith("_nether")) {
            player.height + player.eyeHeight
        } else {
            player.world.getHighestBlockYAt(
                Location(
                    player.world,
                    targetX,
                    0.0,
                    targetZ
                )
            ) + player.height + riseDistance
        }
    }

    private fun targetGuidePosition(player: Player, targetX: Double, targetZ: Double, guidePosition: Vector?): Vector {
        val distance =
            sqrt((player.location.x - targetX).pow(2) + (player.location.z - targetZ).pow(2))
        val angleToTarget = atan2(targetX - player.location.x, targetZ - player.location.z)
        val idealX: Double
        val baseEyeHeightPosition = player.location.y + player.eyeHeight
        var idealY = baseEyeHeightPosition
        val idealZ: Double

        val horizontalPlayerVelocity = player.velocity.clone().multiply(Vector(1, 0, 1))
        val velocityAdjustedGuideDistance =
            GUIDE_DISTANCE + (horizontalPlayerVelocity.length() * GUIDE_DISTANCE_VELOCITY_COEFF)

        if (distance > velocityAdjustedGuideDistance) {
            idealX = player.location.x + (sin(angleToTarget) * velocityAdjustedGuideDistance)
            idealZ = player.location.z + (cos(angleToTarget) * velocityAdjustedGuideDistance)
        } else {
            idealX = targetX
            idealZ = targetZ
        }

        val adjustedAscentLength = GUIDE_ASCENT_LENGTH
        if (distance < adjustedAscentLength) {
            val lerpAmount = max(easeInOutQuart((adjustedAscentLength - distance) / adjustedAscentLength), 0.0)

            val restingY: Double
            if (guidePlayerMap.containsKey(player.uniqueId)) {
                val guideData = guidePlayerMap[player.uniqueId]!!
                if (guideData.restingY != null) {
                    restingY = guideData.restingY!!
                } else {
                    restingY = guideRestingY(player, targetX, targetZ, GUIDE_RISE_DISTANCE)
                    guidePlayerMap[player.uniqueId]!!.restingY = restingY
                }
            } else {
                restingY = guideRestingY(player, targetX, targetZ, GUIDE_RISE_DISTANCE)
            }

            idealY += (restingY - idealY) * lerpAmount
        }
        if (guidePosition != null) {
            val rayTraceResult = player.world.rayTrace(
                Location(player.world, idealX, guidePosition.y.roundToInt().toDouble(), idealZ),
                Vector(0, -1, 0),
                5.0,
                FluidCollisionMode.NEVER,
                true,
                1.0,
                null
            )
            val minY = rayTraceResult?.hitPosition?.y

            if (minY != null) {
                idealY = max(
                    idealY, minY + player.eyeHeight
                )
            }
        }
        return Vector(idealX, idealY, idealZ)
    }

    fun createGuideForPlayer(player: Player, x: Double, z: Double) {
        clearGuideForPlayer(player)

        val highlightEntity = shulkerBulletHighlightEntity(
            player,
            targetGuidePosition(player, x, z, null).toLocation(player.world)
        )
        createHighlight(highlightEntity, player)

        var teleportTickCounter = 0
        var finished = false

        val runnableId = Bukkit.getServer().scheduler.scheduleSyncRepeatingTask(
            this,
            {
                val lastPosition = CraftVector.toBukkit(highlightEntity.positionVector)
                val idealPosition = targetGuidePosition(player, x, z, lastPosition)

                val acceleration =
                    idealPosition.clone().subtract(lastPosition).multiply(0.2)

                // multiply by some number less than 1 for a decay value, then add accel
                val velocity = CraftVector.toBukkit(highlightEntity.mot).multiply(0.8).add(acceleration)
                highlightEntity.mot = CraftVector.toNMS(velocity)

                val position = lastPosition.clone().add(velocity)
                highlightEntity.setPosition(position.x, position.y, position.z)

                // Having the guide teleport every 100th tick (5 seconds) is a hacky way to keep it from drifting
                if (teleportTickCounter >= 100) {
                    teleportTickCounter = 0
                }

                val mustTeleport = listOf(
                    position.x - lastPosition.x,
                    position.y - lastPosition.y,
                    position.z - lastPosition.z
                ).map { abs(it) }.any { it >= 8 } || teleportTickCounter == 0

                teleportTickCounter++

                if (position.distance(idealPosition) >= 5) {
                    highlightEntity.setPosition(idealPosition.x, idealPosition.y, idealPosition.z)
                }

                val velocityPacket = net.minecraft.server.v1_16_R3.PacketPlayOutEntityVelocity(
                    highlightEntity.id,
                    highlightEntity.mot
                )
                (player as CraftPlayer).handle.playerConnection.sendPacket(velocityPacket)
                if (mustTeleport) {
                    val teleportPacket = net.minecraft.server.v1_16_R3.PacketPlayOutEntityTeleport(highlightEntity)
                    player.handle.playerConnection.sendPacket(teleportPacket)
                } else {
                    val packetX = ((position.x * 32 - lastPosition.x * 32) * 128).toInt().toShort()
                    val packetY = ((position.y * 32 - lastPosition.y * 32) * 128).toInt().toShort()
                    val packetZ = ((position.z * 32 - lastPosition.z * 32) * 128).toInt().toShort()

                    val movePacket = net.minecraft.server.v1_16_R3.PacketPlayOutEntity.PacketPlayOutRelEntityMove(
                        highlightEntity.id,
                        packetX,
                        packetY,
                        packetZ,
                        false
                    )
                    player.handle.playerConnection.sendPacket(movePacket)
                }

                if (position.clone().multiply(Vector(1, 0, 1)).distance(Vector(x, 0.0, z)) < 0.01 && !finished) {
                    finished = true
                    val scheduler = Bukkit.getServer().scheduler
                    val particlesTask = scheduler.scheduleSyncRepeatingTask(this, {
                        val particlePacket = net.minecraft.server.v1_16_R3.PacketPlayOutWorldParticles(
                            net.minecraft.server.v1_16_R3.Particles.SOUL_FIRE_FLAME,
                            false,
                            position.x,
                            position.y,
                            position.z,
                            0f,
                            50f,
                            0f,
                            0f,
                            50
                        )
                        player.handle.playerConnection.sendPacket(particlePacket)
                    }, 0, 2)
                    scheduler.scheduleSyncDelayedTask(this, {
                        scheduler.cancelTask(particlesTask)
                        clearGuideForPlayer(player)
                        val deathParticlesPacket = net.minecraft.server.v1_16_R3.PacketPlayOutWorldParticles(
                            net.minecraft.server.v1_16_R3.Particles.SOUL_FIRE_FLAME,
                            false,
                            highlightEntity.positionVector.x,
                            highlightEntity.positionVector.y,
                            highlightEntity.positionVector.z,
                            0.2f,
                            0.2f,
                            0.2f,
                            0.008f,
                            40
                        )
                        player.handle.playerConnection.sendPacket(deathParticlesPacket)
                    }, 100)
                }
            }, 1, 1
        )
        guidePlayerMap[player.uniqueId] = PlayerGuideData(x, z, highlightEntity, runnableId, null)
    }

    // https://easings.net/#easeInOutQuart
    fun easeInOutQuart(x: Double): Double {
        return if (x < 0.5) {
            8 * x * x * x * x
        } else {
            1 - (-2 * x + 2).pow(4) / 2
        }
    }
}

data class StaticHighlightData(
    val entityId: Int,
    val location: Location,
)

data class PlayerGuideData(
    val targetX: Double,
    val targetZ: Double,
    val highlightEntity: net.minecraft.server.v1_16_R3.Entity,
    val runnableId: Int,
    var restingY: Double?
)


sealed class ContainersSearchResult

data class ExactMatchingContainersSearchResult(
    val matches: List<Inventory>,
    val itemCount: Int
) : ContainersSearchResult()

data class PartialMatchingContainersSearchResult(
    val exactMatches: List<Inventory>,
    val partialMatches: List<Inventory>,
    val exactMatchItemCount: Int,
    val partialMatchItemCount: Int
) : ContainersSearchResult()
