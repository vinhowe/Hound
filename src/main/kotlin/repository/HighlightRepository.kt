package repository

import org.bukkit.Location
import org.bukkit.World
import org.bukkit.craftbukkit.v1_16_R3.CraftWorld
import org.bukkit.craftbukkit.v1_16_R3.entity.CraftPlayer
import org.bukkit.craftbukkit.v1_16_R3.util.CraftVector
import org.bukkit.entity.Player
import org.bukkit.util.Vector
import kotlin.math.abs

class HighlightRepository {
    fun createWitherSkull(
        location: Location,
    ): Int {
        val entity = net.minecraft.server.v1_16_R3.EntityWitherSkull(
            net.minecraft.server.v1_16_R3.EntityTypes.WITHER_SKULL,
            (location.world as CraftWorld).handle
        )
        entity.setPositionRaw(location.blockX + 0.5, location.blockY + 0.25, location.blockZ + 0.5)
        setupEntityAsHighlight(entity)

        return entity.id
    }

    fun createEnderPearl(
        location: Location,
    ): Int {
        val entity =
            net.minecraft.server.v1_16_R3.EntityEnderSignal(
                (location.world as CraftWorld).handle,
                location.blockX + 0.5,
                location.blockY + 0.25,
                location.blockZ + 0.5,
            )
        setupEntityAsHighlight(entity)

        return entity.id
    }

    fun createShulkerBullet(
        location: Location,
    ): Int {
        val entity =
            net.minecraft.server.v1_16_R3.EntityShulkerBullet(
                net.minecraft.server.v1_16_R3.EntityTypes.SHULKER_BULLET,
                (location.world as CraftWorld).handle
            )
        entity.setPosition(location.blockX + 0.5, location.blockY + 0.25, location.blockZ + 0.5)
        setupEntityAsHighlight(entity)

        return entity.id
    }

    fun destroyEntities(ids: List<Int>, visibleTo: Set<Player>) {
        val destroyHighlighterPacket = net.minecraft.server.v1_16_R3.PacketPlayOutEntityDestroy(
            *ids.toTypedArray().toIntArray()
        )

        for (player in visibleTo) {
            (player as CraftPlayer).handle.playerConnection.sendPacket(destroyHighlighterPacket)
        }
    }

    fun getEntityVelocity(id: Int, world: World): Vector? {
        return CraftVector.toBukkit(((world as CraftWorld).handle.getEntity(id) ?: return null).mot)
    }

    fun setEntityVelocity(id: Int, world: World, velocity: Vector) {
        ((world as CraftWorld).handle.getEntity(id) ?: return).mot = CraftVector.toNMS(velocity)
    }

    fun broadcastEntityMove(id: Int, location: Location, visibleTo: Set<Player>, forceTeleport: Boolean = false) {
        val entity = (location.world as CraftWorld).handle.getEntity(id) ?: return
        val lastLocation = entity.positionVector

        entity.setPosition(location.x, location.y, location.z)

        val teleport = forceTeleport || listOf(
            location.x - lastLocation.x,
            location.y - lastLocation.y,
            location.z - lastLocation.z
        ).map { abs(it) }.any { it >= 8 }

        val velocityPacket = net.minecraft.server.v1_16_R3.PacketPlayOutEntityVelocity(
            entity.id,
            CraftVector.toNMS(location.toVector().clone().subtract(CraftVector.toBukkit(lastLocation)))
        )

        val positionPacket: net.minecraft.server.v1_16_R3.Packet<net.minecraft.server.v1_16_R3.PacketListenerPlayOut> =
            if (teleport) {
                net.minecraft.server.v1_16_R3.PacketPlayOutEntityTeleport(entity)
            } else {
                val packetX = ((location.x * 32 - lastLocation.x * 32) * 128).toInt().toShort()
                val packetY = ((location.y * 32 - lastLocation.y * 32) * 128).toInt().toShort()
                val packetZ = ((location.z * 32 - lastLocation.z * 32) * 128).toInt().toShort()

                net.minecraft.server.v1_16_R3.PacketPlayOutEntity.PacketPlayOutRelEntityMove(
                    entity.id,
                    packetX,
                    packetY,
                    packetZ,
                    false
                )
            }

        visibleTo.map { it as CraftPlayer }
            .forEach {
                it.handle.playerConnection.sendPacket(velocityPacket)
                it.handle.playerConnection.sendPacket(positionPacket)
            }
    }

    fun showEntity(id: Int, world: World, visibleTo: Set<Player>) {
        val entity = (world as CraftWorld).handle.getEntity(id) ?: return

        val highlightSpawnPacket = net.minecraft.server.v1_16_R3.PacketPlayOutSpawnEntity(entity)
        val highlightMetadataPacket =
            net.minecraft.server.v1_16_R3.PacketPlayOutEntityMetadata(entity.id, entity.dataWatcher, true)

        for (player in visibleTo) {
            (player as CraftPlayer).handle.playerConnection.sendPacket(highlightSpawnPacket)
            player.handle.playerConnection.sendPacket(highlightMetadataPacket)
        }
    }

    private fun setupEntityAsHighlight(
        entity: net.minecraft.server.v1_16_R3.Entity,
    ) {
        entity.isInvulnerable = true
        entity.isNoGravity = true
        // i -> setGlowing
        entity.i(true)
    }
}
