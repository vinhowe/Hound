import co.aikar.commands.InvalidCommandArgument
import co.aikar.commands.PaperCommandManager
import data.LiveSearchState
import data.PartialMatchModel
import kotlinx.coroutines.flow.MutableSharedFlow
import org.bukkit.*
import org.bukkit.block.Block
import org.bukkit.block.Container
import org.bukkit.block.DoubleChest
import org.bukkit.block.data.type.Chest
import org.bukkit.craftbukkit.v1_16_R3.CraftWorld
import org.bukkit.craftbukkit.v1_16_R3.entity.CraftPlayer
import org.bukkit.craftbukkit.v1_16_R3.util.CraftVector
import org.bukkit.entity.Player
import org.bukkit.inventory.BlockInventoryHolder
import org.bukkit.inventory.DoubleChestInventory
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.BlockStateMeta
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.util.Vector
import java.math.RoundingMode
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
        manager.commandCompletions.registerStaticCompletion(
            "blocks",
            blocks.map(Material::name).map(String::toLowerCase)
        )
        manager.commandCompletions.registerStaticCompletion("states", listOf("on", "off"))

        manager.commandCompletions.registerCompletion("coords") { context ->
            val suggestions = mutableListOf("~")

            context.player?.let {
                val origin = when {
                    context.hasConfig("x") -> it.location.x
                    context.hasConfig("y") -> it.location.y
                    context.hasConfig("z") -> it.location.z
                    else -> Double.NaN
                }

                if (!origin.isNaN()) {
                    suggestions += origin.toBigDecimal().setScale(2, RoundingMode.HALF_EVEN).toPlainString()
                }
            }

            suggestions
        }

        manager.commandContexts.registerIssuerAwareContext(PartialMatchModel::class.java) { context ->
            val player = context.player ?: throw InvalidCommandArgument(
                "&4You must be an in-game player to use this command.",
                false
            )
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
                    if (item.name.equals(input, true)) {
                        exact = item
                    } else if (item.name.contains(input, true)) {
                        fuzzy += item
                    }
                }

                PartialMatchModel(input, exact, fuzzy)
            }
        }

        manager.commandContexts.registerIssuerAwareContext(LiveSearchState::class.java) { context ->
            val player = context.player ?: throw InvalidCommandArgument(
                "&4You must be an in-game player to use this command.",
                false
            )
            val input = context.popFirstArg()

            val currentState = player.uniqueId in liveSearchSet
            val desiredState = if (input == null) {
                !currentState
            } else when (input.toLowerCase()) {
                "on" -> true
                "off" -> false
                else -> {
                    throw InvalidCommandArgument("&4Invalid state")
                }
            }

            LiveSearchState(currentState, desiredState)
        }

        manager.commandContexts.registerIssuerAwareContext(Double::class.javaObjectType) { context ->
            val player = context.player ?: throw InvalidCommandArgument(
                "&4You must be an in-game player to use this command.",
                false
            )
            val input = context.popFirstArg() ?: return@registerIssuerAwareContext null

            if (!input.startsWith("~")) {
                input.toDoubleOrNull() ?: throw InvalidCommandArgument("&4You must supply a valid coordinate.")
            } else {
                val origin = when {
                    context.hasFlag("x") -> player.location.x
                    context.hasFlag("y") -> player.location.y
                    context.hasFlag("z") -> player.location.z
                    else -> {
                        throw InvalidCommandArgument("&4Coordinate must be absolute.")
                    }
                }

                origin.toBigDecimal().setScale(2, RoundingMode.HALF_EVEN).toDouble() + (input.drop(1).toDoubleOrNull()
                    ?: 0.0)
            }
        }

        manager.registerCommand(cmds.BlockSearchCommand(this))
        manager.registerCommand(cmds.ChestSearchCommand(this))
        manager.registerCommand(cmds.LiveChestSearchCommand(this))
        manager.registerCommand(cmds.TargetCommand(this))
        manager.registerCommand(cmds.TorchGuideCommand(this))

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

    private fun getItemStackInnerInventory(itemStack: ItemStack): Inventory? {
        val itemMeta = itemStack.itemMeta
        if (itemMeta !is BlockStateMeta) {
            return null
        }

        return when (val blockState = itemMeta.blockState) {
            is Container -> blockState.inventory
            else -> null
        }
    }

    private fun searchContainersForExactMatches(
        material: Material,
        containers: List<InventoryWithLocation>
    ): ExactMatchingContainersSearchResult {
        var itemCount = 0
        val matches: MutableSet<InventoryWithLocation> = mutableSetOf()
        var foundNested = false
        containers.forEach { container ->
            var match = false
            for (item in container.inventory) {
                if (item == null) {
                    continue
                }

                val innerItems =
                    getItemStackInnerInventory(item)?.contents?.filter { it != null && it.type == material }

                var itemOrContentsMatch = false
                if (!innerItems.isNullOrEmpty()) {
                    itemCount += innerItems.sumBy { it.amount }
                    foundNested = true
                    itemOrContentsMatch = true
                }

                if (item.type == material) {
                    itemOrContentsMatch = true
                    itemCount++
                }

                if (!itemOrContentsMatch || match) {
                    continue
                }

                matches += container
                match = true
            }
        }
        return ExactMatchingContainersSearchResult(matches, itemCount, foundNested)
    }

    private fun searchContainersForPartialMatches(
        exactMatchMaterial: Material?,
        partialMatchMaterials: List<Material>,
        containers: List<InventoryWithLocation>,
    ): PartialMatchingContainersSearchResult {
        var exactMatchItemCount = 0
        var partialMatchItemCount = 0
        val exactMatches: MutableSet<InventoryWithLocation> = mutableSetOf()
        val partialMatches: MutableSet<InventoryWithLocation> = mutableSetOf()

        var foundNested = false
        // it, parent (if nested)
        val containersStack =
            containers.map { Pair<InventoryWithLocation, InventoryWithLocation?>(it, null) }.toMutableList()
        while (containersStack.isNotEmpty()) {
            val (container, parent) = containersStack.removeLast()
            // Each matching container is either an exact match or a partial match, but not both
            var containerExactMatch = false
            var containerPartialMatch = false
            for (item in container.inventory) {
                if (item == null) {
                    continue
                }

                val innerInventory =
                    getItemStackInnerInventory(item)

                if (innerInventory != null) {
                    containersStack += Pair(InventoryWithLocation(innerInventory, container.location), container)
                }

                val targetInventory = parent ?: container

                if (item.type == exactMatchMaterial) {
                    exactMatchItemCount += item.amount
                    if (!containerExactMatch) {
                        exactMatches += targetInventory
                        if (parent != null) {
                            foundNested = true
                        }
                        containerExactMatch = true
                        if (containerPartialMatch) {
                            partialMatches -= targetInventory
                        }
                    }
                    continue
                }

                if (item.type in partialMatchMaterials) {
                    partialMatchItemCount += item.amount
                    if (parent != null) {
                        foundNested = true
                    }
                    if (!containerPartialMatch && !containerExactMatch) {
                        containerPartialMatch = true
                        partialMatches += targetInventory
                    }
                }
            }
        }

        return PartialMatchingContainersSearchResult(
            exactMatches,
            partialMatches,
            exactMatchItemCount,
            partialMatchItemCount,
            foundNested
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

    private fun containersInRadius(start: Location, radius: Int, player: Player? = null): List<InventoryWithLocation> {
        val containers = mutableListOf<InventoryWithLocation>()
        var closestEnderChestDistance = Double.MAX_VALUE
        var enderChestMatch: InventoryWithLocation? = null

        for (x in -radius..radius) {
            for (y in -radius..radius) {
                for (z in -radius..radius) {
                    val blockLocation =
                        Location(start.world, start.x + x, start.y + y, start.z + z)
                    val blockState = blockLocation.block.state

                    if (
                        player != null &&
                        blockState.type == Material.ENDER_CHEST
                    ) {
                        // TODO: Figure out how not to do this distance calculation so much and keep this logic clean
                        val distance = blockLocation.distance(player.location)
                        if (enderChestMatch == null || distance < closestEnderChestDistance) {
                            enderChestMatch = InventoryWithLocation(player.enderChest, blockLocation.block.location)
                            closestEnderChestDistance = distance
                            continue
                        }
                    }

                    if (blockState !is BlockInventoryHolder) {
                        continue
                    }
                    val storedLocation = blockState.inventory.location ?: blockLocation

                    val blockData = blockState.blockData
                    if (blockData is Chest && blockData.type != Chest.Type.SINGLE) {
                        if (blockState.inventory !is DoubleChestInventory || blockState.inventory.holder !is DoubleChest) {
                            continue
                        }
                        containers.add(
                            InventoryWithLocation(
                                when (blockData.type) {
                                    Chest.Type.LEFT -> (blockState.inventory as DoubleChestInventory).leftSide
                                    Chest.Type.RIGHT -> (blockState.inventory as DoubleChestInventory).rightSide
                                    else -> continue
                                }, storedLocation
                            )
                        )
                        continue
                    }

                    containers.add(InventoryWithLocation(blockState.inventory, storedLocation))
                }
            }
        }

        if (enderChestMatch != null) {
            containers.add(enderChestMatch)
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
        val containers = containersInRadius(player.location, radius, player)
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
        searchResult.exactMatches.toList().subList(0, highlightedExactMatchCount).map { it.location }.forEach {
            val highlightEntity = shulkerBulletHighlightEntity(player, it)
            createHighlight(highlightEntity, player)
            registerTemporaryStaticHighlightForPlayer(highlightEntity, player, duration)
        }
        searchResult.partialMatches.toList().subList(
            0,
            min(MAX_VISIBLE_STATIC_HIGHLIGHTS - highlightedExactMatchCount, searchResult.partialMatches.size)
        ).map { it.location }.forEach {
            val highlightEntity = cubeHighlightEntity(player, it)
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
        val containers = containersInRadius(player.location, radius, player)
        val searchResult = searchContainersForExactMatches(material, containers)

        if (searchResult.matches.isEmpty()) {
            return null
        }

        clearStaticHighlightsForPlayer(player)

        searchResult.matches.toList().subList(0, min(MAX_VISIBLE_STATIC_HIGHLIGHTS, searchResult.matches.size))
            .forEach {
                val highlightEntity = shulkerBulletHighlightEntity(player, it.location)
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
    val matches: Set<InventoryWithLocation>,
    val itemCount: Int,
    val foundNested: Boolean
) : ContainersSearchResult()

data class PartialMatchingContainersSearchResult(
    val exactMatches: Set<InventoryWithLocation>,
    val partialMatches: Set<InventoryWithLocation>,
    val exactMatchItemCount: Int,
    val partialMatchItemCount: Int,
    val foundNested: Boolean
) : ContainersSearchResult()

// While Inventory does sometimes have location, there's no way to set it without reflection as far as I can tell
data class InventoryWithLocation(
    val inventory: Inventory,
    val location: Location
)
