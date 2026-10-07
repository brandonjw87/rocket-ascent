package com.brandonwallace.rocketlaunch.game

import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

enum class EntityKind(val hazard: Boolean, val radius: Float, val damage: Double) {
    COIN(false, 1.3f, 0.0),
    FUEL(false, 1.5f, 0.0),
    REPAIR(false, 1.5f, 0.0),
    BIRD(true, 0.9f, 12.0),
    PLANE(true, 3.6f, 38.0),
    BALLOON(true, 2.0f, 18.0),
    SATELLITE(true, 2.8f, 42.0),
    DEBRIS(true, 1.5f, 26.0),
    METEOR(true, 2.2f, 48.0),
}

class Entity(val kind: EntityKind, var x: Float, var y: Float, var z: Float, var vx: Float, var vy: Float, val value: Int, val seed: Float) {
    var rot = 0f
    var spin = 0f
    var age = 0f
    var alive = true
}

class Cloud(var x: Float, var y: Float, val z: Float, val size: Float, val seed: Float)

sealed class WorldEvent {
    data class Collected(val kind: EntityKind, val value: Int, val x: Float, val y: Float) : WorldEvent()
    data class Hit(val kind: EntityKind, val damage: Double, val x: Float, val y: Float) : WorldEvent()
}

/**
 * The gameplay plane around the rocket. Positions are in "visual metres" relative to the rocket base;
 * everything scrolls down at a compressed visual speed so pickups stay readable at km/s.
 */
class World(seed: Int = 1) {
    val entities = ArrayList<Entity>()
    val clouds = ArrayList<Cloud>()
    var rng = Random(seed)
    private var spawnAcc = 0f
    private var cloudAcc = 0f

    companion object {
        const val LANE = 10f
        const val SPAWN_Y = 95f
        const val DESPAWN_Y = -95f

        fun cloudDensity(alt: Double): Float = when {
            alt < 900 || alt > 12_000 -> 0f
            alt < 2_000 -> ((alt - 900) / 1100).toFloat()
            alt < 7_000 -> 1f
            else -> (1 - (alt - 7_000) / 5_000).toFloat()
        }

        fun spawnTable(alt: Double): List<Pair<EntityKind, Float>> = when {
            alt < 2_500 -> listOf(EntityKind.COIN to 5f, EntityKind.FUEL to 1f, EntityKind.BIRD to 3f)
            alt < 12_000 -> listOf(EntityKind.COIN to 5f, EntityKind.FUEL to 1.2f, EntityKind.PLANE to 1.6f, EntityKind.BIRD to (if (alt < 4_000) 1f else 0f))
            alt < 50_000 -> listOf(EntityKind.COIN to 5f, EntityKind.FUEL to 1.2f, EntityKind.BALLOON to 2.2f, EntityKind.REPAIR to 0.3f)
            alt < 100_000 -> listOf(EntityKind.COIN to 5f, EntityKind.FUEL to 1f, EntityKind.REPAIR to 0.4f, EntityKind.METEOR to 0.6f)
            alt < 2_000_000 -> listOf(EntityKind.COIN to 5f, EntityKind.FUEL to 1f, EntityKind.REPAIR to 0.4f, EntityKind.SATELLITE to 1.6f, EntityKind.DEBRIS to 2.2f)
            else -> listOf(EntityKind.COIN to 5f, EntityKind.FUEL to 1f, EntityKind.REPAIR to 0.5f, EntityKind.DEBRIS to 0.8f, EntityKind.METEOR to 1.6f)
        }
    }

    fun reset(seed: Int) {
        entities.clear(); clouds.clear(); rng = Random(seed); spawnAcc = 0f; cloudAcc = 0f
    }

    private fun rf(a: Float, b: Float) = a + rng.nextFloat() * (b - a)

    fun update(
        dt: Float, scroll: Float, alt: Double, rocketX: Float, rocketLen: Float, rocketRadius: Float,
        magnet: Float, active: Boolean, out: MutableList<WorldEvent>,
    ) {
        for (e in entities) {
            e.age += dt
            e.x += e.vx * dt
            e.y += (e.vy - scroll) * dt
            e.rot += e.spin * dt
            if (e.kind == EntityKind.BALLOON) e.x += sin(e.age * 0.8f + e.seed * 6f) * 0.6f * dt
            val cy = e.y.coerceIn(0f, rocketLen)
            val dx = e.x - rocketX
            val dy = e.y - cy
            val d2 = dx * dx + dy * dy
            if (active && !e.kind.hazard && magnet > 0f) {
                val reach = magnet + e.kind.radius
                if (d2 < reach * reach) {
                    val d = sqrt(d2).coerceAtLeast(0.01f)
                    val pull = 40f * dt
                    e.x -= dx / d * pull
                    e.y -= dy / d * pull
                }
            }
            val rr = rocketRadius + e.kind.radius
            if (active && d2 < rr * rr) {
                e.alive = false
                out.add(
                    if (e.kind.hazard) WorldEvent.Hit(e.kind, e.kind.damage, e.x, e.y)
                    else WorldEvent.Collected(e.kind, e.value, e.x, e.y)
                )
            }
            if (e.y < DESPAWN_Y || e.y > SPAWN_Y + 140f || e.x > 140f || e.x < -140f) e.alive = false
        }
        entities.removeAll { !it.alive }

        if (active && alt > 120 && scroll > 2f) {
            spawnAcc += dt * 1.45f
            while (spawnAcc >= 1f) { spawnAcc -= 1f; spawnOne(alt, rocketX) }
        }

        // clouds
        val cd = cloudDensity(alt)
        if (cd > 0f && scroll > 1f) {
            cloudAcc += dt * cd * (1.2f + scroll / 60f)
            while (cloudAcc >= 1f) {
                cloudAcc -= 1f
                clouds.add(Cloud(rocketX + rf(-75f, 75f), SPAWN_Y + rf(10f, 60f), rf(-170f, 25f), rf(16f, 40f), rng.nextFloat()))
            }
        }
        for (c in clouds) c.y -= scroll * dt
        clouds.removeAll { it.y < DESPAWN_Y - 80f || it.y > SPAWN_Y + 200f }
    }

    private fun pick(alt: Double): EntityKind {
        val table = spawnTable(alt)
        var r = rng.nextFloat() * table.sumOf { it.second.toDouble() }.toFloat()
        for ((k, w) in table) { r -= w; if (r <= 0f) return k }
        return table.first().first
    }

    private fun spawnOne(alt: Double, rocketX: Float) {
        val kind = pick(alt)
        val y = SPAWN_Y + rf(0f, 25f)
        val x = rf(-LANE, LANE)
        val seed = rng.nextFloat()
        when (kind) {
            EntityKind.COIN -> {
                val v = Economy.coinValueAt(alt)
                if (rng.nextFloat() < 0.5f) {
                    val n = 5 + rng.nextInt(4)
                    val x0 = rf(-LANE, LANE)
                    val x1 = (x0 + rf(-9f, 9f)).coerceIn(-LANE, LANE)
                    val wave = rng.nextBoolean()
                    for (i in 0 until n) {
                        val t = i / (n - 1f)
                        val cx = if (wave) x0 + sin(t * 6.28f) * 5f else x0 + (x1 - x0) * t
                        entities.add(Entity(kind, cx.coerceIn(-LANE, LANE), y + i * 6f, 0f, 0f, 0f, v, seed))
                    }
                } else entities.add(Entity(kind, x, y, 0f, 0f, 0f, v, seed))
            }
            EntityKind.BIRD -> {
                val dir = if (rng.nextBoolean()) 1f else -1f
                val n = 1 + rng.nextInt(3)
                for (i in 0 until n) entities.add(Entity(kind, x - dir * i * 2.2f, y - i * 1.6f, 0f, dir * rf(4f, 8f), 0f, 0, rng.nextFloat()))
            }
            EntityKind.PLANE -> {
                val side = if (rng.nextBoolean()) 1f else -1f
                entities.add(Entity(kind, side * 48f, y - 15f, 0f, -side * rf(30f, 42f), 0f, 0, seed))
            }
            EntityKind.BALLOON -> entities.add(Entity(kind, x, y, 0f, 0f, 2.5f, 0, seed))
            EntityKind.SATELLITE -> entities.add(Entity(kind, x, y, 0f, rf(-3f, 3f), 0f, 0, seed).also { it.spin = rf(-0.6f, 0.6f) })
            EntityKind.DEBRIS -> entities.add(Entity(kind, x, y, 0f, rf(-9f, 9f), 0f, 0, seed).also { it.spin = rf(-4f, 4f) })
            EntityKind.METEOR -> {
                val side = if (rng.nextBoolean()) 1f else -1f
                entities.add(Entity(kind, rocketX + side * 28f, y + 10f, 0f, -side * 13f, -22f, 0, seed).also { it.spin = rf(-2f, 2f) })
            }
            EntityKind.FUEL, EntityKind.REPAIR -> entities.add(Entity(kind, x, y, 0f, 0f, 0f, 0, seed).also { it.spin = 1.5f })
        }
    }
}
