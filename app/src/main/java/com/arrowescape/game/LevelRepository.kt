package com.arrowescape.game

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException

/** Result of a level lookup. There is no "unsolvable" case: shipped packs are verified and the
 *  fallback generator builds solvable-by-construction levels (greedy-checked before caching). */
sealed interface LevelLoadResult {
    data class Level(val level: com.arrowescape.game.Level) : LevelLoadResult
    /** Requested level number is beyond the last shipped pack -> UI shows "More levels soon". */
    data object NotShipped : LevelLoadResult
}

/**
 * Loads stored level packs (assets/levels/pack_NNNN.bin, 500 levels each, gzip + CRC32).
 * The app contains ONLY the decoder — packs are produced offline by tools/LevelPackBuilder.kt.
 *
 *  - one pack decoded in memory at a time (LRU of size 1),
 *  - the next pack is prefetched on a background scope right after a load,
 *  - CRC32 validated before use; a corrupt asset falls back to the deterministic on-device
 *    generator, whose output is written to internal storage ONCE and never regenerated.
 */
class LevelRepository(private val context: Context, private val scope: CoroutineScope) {

    private val mutex = Mutex()
    private var cachedPackId = -1
    private var cachedLevels: List<com.arrowescape.game.Level>? = null

    suspend fun load(levelNumber: Int): LevelLoadResult {
        if (levelNumber < 1) return LevelLoadResult.NotShipped
        val packId = (levelNumber - 1) / LevelPack.LEVELS_PER_PACK + 1
        if (packId > LAST_SHIPPED_PACK) return LevelLoadResult.NotShipped
        val indexInPack = (levelNumber - 1) % LevelPack.LEVELS_PER_PACK
        return try {
            val levels = ensurePack(packId)
            levels.getOrNull(indexInPack)?.let { LevelLoadResult.Level(it) } ?: LevelLoadResult.NotShipped
        } catch (e: IOException) {
            // Asset missing/unreadable: deterministic fallback, generated once then cached forever.
            LevelLoadResult.Level(fallbackLevel(packId, levelNumber))
        } finally {
            prefetchNext(packId)
        }
    }

    private suspend fun ensurePack(packId: Int): List<com.arrowescape.game.Level> =
        mutex.withLock {
            if (cachedPackId == packId && cachedLevels != null) return@withLock cachedLevels!!
            val bytes = readPackBytes(packId)
                ?: throw IOException("pack_$packId not found")
            val levels = try {
                LevelPack.decodeFile(bytes) // validates CRC32 internally
            } catch (e: LevelPack.PackCorruptException) {
                throw IOException("pack $packId corrupt", e)
            }
            cachedPackId = packId
            cachedLevels = levels
            levels
        }

    /** Reads pack bytes from internal cache first, then assets. */
    private fun readPackBytes(packId: Int): ByteArray? {
        val name = "pack_%04d.bin".format(packId)
        val cached = File(context.filesDir, "levels/$name")
        if (cached.exists()) return cached.readBytes()
        return try {
            context.assets.open("levels/$name").use { it.readBytes() }
        } catch (e: IOException) {
            null
        }
    }

    private fun prefetchNext(packId: Int) {
        val next = packId + 1
        if (next > LAST_SHIPPED_PACK) return
        scope.launchIo {
            runCatching { mutex.withLock { if (cachedPackId != next) readPackBytes(next) } }
        }
    }

    /**
     * Fallback path: generate the whole pack deterministically (mulberry32 seeded only by
     * (packId, levelNumber)), encode with the SAME binary format, save to internal storage and
     * never regenerate. Because generation is device-entropy-free, every user gets identical
     * levels even through this path.
     */
    private suspend fun fallbackLevel(packId: Int, levelNumber: Int): com.arrowescape.game.Level {
        val name = "pack_%04d.bin".format(packId)
        val file = File(context.filesDir, "levels/$name")
        if (!file.exists()) {
            val first = (packId - 1) * LevelPack.LEVELS_PER_PACK + 1
            val levels = (first until first + LevelPack.LEVELS_PER_PACK).map { n ->
                FallbackLevelGenerator.generate(packId, n)
            }
            val tmp = File(file.parentFile, "$name.tmp")
            file.parentFile?.mkdirs()
            tmp.writeBytes(LevelPack.encodeFile(first, levels))
            tmp.renameTo(file) // atomic-ish; concurrent processes just re-decode the asset path
        }
        val bytes = file.readBytes()
        val decoded = LevelPack.decodeFile(bytes)
        val idx = (levelNumber - 1) % LevelPack.LEVELS_PER_PACK
        return decoded[idx]
    }

    companion object {
        /** Shipped content: 6 packs x 500 = 3,000+ levels ("at least 3,000"; never renumbered). */
        const val LAST_SHIPPED_PACK = 6
        const val SHIPPED_LEVEL_COUNT = LAST_SHIPPED_PACK * LevelPack.LEVELS_PER_PACK
    }
}

private fun kotlinx.coroutines.CoroutineScope.launchIo(block: suspend () -> Unit) {
    kotlinx.coroutines.launch(Dispatchers.IO) { block() }
}
