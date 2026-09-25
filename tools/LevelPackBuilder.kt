/*
 * Arrow Escape — OFFLINE level pack builder (plain JVM tool; NOT part of the app).
 *
 * Generates 3,000+ deterministic levels and writes assets/levels/pack_NNNN.bin files
 * (500 levels per pack, gzip container + CRC32 checksum, format defined in LevelPack.kt).
 *
 * Build & run (from repo root):
 *   kotlinc -include-runtime -d /tmp/builder.jar tools/LevelPackBuilder.kt \
 *       app/src/main/java/com/arrowescape/game/{ArrowModel,GameEngine,LevelPack,LevelShapes,FallbackLevelGenerator}.kt
 *   java -jar /tmp/builder.jar --out app/src/main/assets/levels --packs 6 [--verify]
 *
 * DETERMINISM CONTRACT (mirrors FallbackLevelGenerator exactly):
 *  - mulberry32 PRNG seeded ONLY from (packId, levelNumber); never device time/id/entropy,
 *  - integer-only state math, fixed iteration order everywhere,
 *  - difficulty rises with the level number only.
 * This is what lets the on-device fallback produce byte-identical levels for every user.
 *
 * GENERATION ALGORITHM (solvable by construction), per spec:
 *  1. Rasterize the shape mask (rectangle early; heart/dog/cat/anchor/trophy/star/tree/fish
 *     silhouettes on milestone levels) from normalized polygons (LevelShapes).
 *  2. Grow a self-avoiding path through free mask cells: length L >= 2, at most T turns.
 *     The LAST cell is the head. Pieces are placed in REVERSE removal order — i.e. the first
 *     piece placed is the LAST one removed — so "already-placed" at insertion time exactly
 *     matches "still remaining on the board" at that piece's removal moment in the eventual
 *     solve. That is why the acceptance test below is correct as written.
 *  3. Accept the candidate only if the ray from its head to the board edge crosses no
 *     already-placed cell (plus an acyclicity guard inside the generator).
 *  4. Keep growing until 92-98% of the mask is filled (onboarding levels fill less).
 *  5. Verify with the greedy solver (repeatedly remove any free arrow). Reject + retry on
 *     failure. Because removing a piece only frees cells and never re-occupies them,
 *     removability is monotone in the removed set => if the board is solvable at all, ANY
 *     greedy run clears it completely. The check is therefore a real safety net, not a
 *     formality — and it doubles as proof there is no hidden deadlock.
 *  6. Score difficulty from the blocking graph (longest dependency chain, count of initially
 *     free arrows, average blockers per ray — GameEngine.difficultyScore). Build 6 candidates
 *     per level and keep the one closest to the target score for that level number.
 *
 * Single-cell arrows are impossible here by construction: growth always starts with the head
 * plus one backward cell (L >= 2), and a would-be length-1 path is a generation-time reject
 * (`cells.size < 2 -> null`), never a runtime special case. The encoder additionally refuses
 * to write len < 2.
 *
 * The actual generation loop lives in FallbackLevelGenerator (shared verbatim with the app's
 * on-device fallback so both paths are guaranteed identical); this tool wraps it with pack
 * encoding, verification, and golden-hash emission.
 */

import com.arrowescape.game.FallbackLevelGenerator
import com.arrowescape.game.GameEngine
import com.arrowescape.game.Level
import com.arrowescape.game.LevelPack
import java.io.File

private data class Options(
    val outDir: File = File("app/src/main/assets/levels"),
    val packs: Int = 6,
    val verify: Boolean = false,
    val sampleToStdout: Boolean = false,
)

private fun parseArgs(args: Array<String>): Options {
    var o = Options()
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--out" -> { o = o.copy(outDir = File(args[++i])) }
            "--packs" -> { o = o.copy(packs = args[++i].toInt()) }
            "--verify" -> { o = o.copy(verify = true) }
            "--sample" -> { o = o.copy(sampleToStdout = true) }
            else -> error("unknown arg ${args[i]}")
        }
        i++
    }
    return o
}

fun main(args: Array<String>) {
    val opts = parseArgs(args)
    println("Arrow Escape LevelPackBuilder — packs=${opts.packs} levels/pack=${LevelPack.LEVELS_PER_PACK} out=${opts.outDir}")
    opts.outDir.mkdirs()

    // Deterministic golden hashes for levels 1..200 (content hash of the DECODED level, i.e.
    // the arrow list — deliberately separate from the pack CRC32 which guards file corruption).
    val golden = StringBuilder()

    for (packId in 1..opts.packs) {
        val first = (packId - 1) * LevelPack.LEVELS_PER_PACK + 1
        val last = first + LevelPack.LEVELS_PER_PACK - 1
        val t0 = System.currentTimeMillis()
        val levels = ArrayList<Level>(LevelPack.LEVELS_PER_PACK)
        for (n in first..last) {
            val lv = FallbackLevelGenerator.generate(packId, n)
            // Safety net #2 (belt and braces): every stored level must be greedy-solvable.
            check(GameEngine.solveGreedy(lv) != null) { "level $n failed greedy verification" }
            check(lv.arrows.all { it.cells.size >= 2 }) { "level $n emitted a single-cell arrow" }
            levels += lv
            if (n <= 200) {
                golden.append("$n ${lv.width}x${lv.height} ${lv.totalArrows} ${LevelPack.contentHash(lv)}\n")
            }
        }
        val bytes = LevelPack.encodeFile(first, levels)
        val file = File(opts.outDir, "pack_%04d.bin".format(packId))
        file.writeBytes(bytes)
        println("wrote %s (%d levels, %.1f KB, %d ms)".format(file.name, levels.size, bytes.size / 1024.0, System.currentTimeMillis() - t0))

        if (opts.verify) {
            val decoded = LevelPack.decodeFile(file.readBytes())
            check(decoded.size == levels.size) { "round-trip size mismatch pack $packId" }
            for (k in decoded.indices) {
                check(LevelPack.contentHash(decoded[k]) == LevelPack.contentHash(levels[k])) {
                    "round-trip content mismatch at level ${first + k}"
                }
            }
            println("  verified: CRC32 ok, bit-exact round-trip for all ${decoded.size} levels")
        }
    }

    File(opts.outDir, "goldens_levels_1_200.txt").writeText(golden.toString())
    println("wrote goldens_levels_1_200.txt (${golden.lines().filter { it.isNotBlank() }.size} entries)")
    if (opts.sampleToStdout) print(golden)
    println("done.")
}
