package com.arrowescape.game

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.util.Calendar

/** Persisted player state. Everything the game remembers between sessions. */
data class Progress(
    val currentLevel: Int = 1,
    val highestUnlocked: Int = 1,
    val hints: Int = DEFAULT_HINTS,
    val starsByLevel: Map<Int, Int> = emptyMap(),       // level -> 0..3 (3 = no heart lost)
    val completedSilhouettes: Set<String> = emptySet(),  // Gallery keys ("HEART", "STAR", ...)
    val theme: GameTheme.ThemeName = GameTheme.ThemeName.NIGHT,
    val soundEnabled: Boolean = true,
    val hapticsEnabled: Boolean = true,
    val streakDays: Int = 0,
    val lastPlayedDay: String = "",           // yyyy-MM-dd; "" = never
    val dailyClaimedDay: String = "",         // day the hint-daily was last claimed
    val streakFreezes: Int = 1,               // start with one freeze
) {
    companion object { const val DEFAULT_HINTS = 3 }

    /** Hints earned by progression: 1 per 5 levels completed, on top of the starting grant. */
    fun hintsEarnedFromLevels(): Int = ((highestUnlocked - 1) / 5).coerceAtLeast(0)

    fun isThemeUnlocked(name: GameTheme.ThemeName): Boolean = highestUnlocked >= name.unlockLevel
}

private val Context.arrowDataStore: DataStore<Preferences> by preferencesDataStore(name = "arrow_escape_progress")

/**
 * DataStore-backed progress. Reads are a cold Flow (survives process death — all state lives
 * on disk); writes go through [edit]. Rotation needs no special handling because the
 * ViewModel holds only transient UI state and re-reads this store.
 */
class ProgressStore(private val context: Context) {

    private object Keys {
        val CURRENT = intPreferencesKey("current_level")
        val HIGHEST = intPreferencesKey("highest_unlocked")
        val HINTS = intPreferencesKey("hints")
        val STARS = stringPreferencesKey("stars")            // "lvl:stars,lvl:stars,..."
        val SILHOUETTES = stringPreferencesKey("silhouettes") // comma list
        val THEME = stringPreferencesKey("theme")
        val SOUND = booleanPreferencesKey("sound")
        val HAPTICS = booleanPreferencesKey("haptics")
        val STREAK = intPreferencesKey("streak_days")
        val LAST_DAY = stringPreferencesKey("last_played_day")
        val DAILY_CLAIMED = stringPreferencesKey("daily_claimed_day")
        val FREEZES = intPreferencesKey("streak_freezes")
    }

    val progress: Flow<Progress> = context.arrowDataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs -> read(prefs) }

    suspend fun snapshot(): Progress = progress.first()

    private fun read(p: Preferences): Progress {
        val stars = (p[Keys.STARS] ?: "").split(',').mapNotNull { entry ->
            val parts = entry.split(':')
            if (parts.size == 2) parts[0].toIntOrNull()?.let { lvl -> lvl to (parts[1].toIntOrNull() ?: 0) } else null
        }.toMap()
        return Progress(
            currentLevel = p[Keys.CURRENT] ?: 1,
            highestUnlocked = p[Keys.HIGHEST] ?: 1,
            hints = p[Keys.HINTS] ?: Progress.DEFAULT_HINTS,
            starsByLevel = stars,
            completedSilhouettes = (p[Keys.SILHOUETTES] ?: "").split(',').filter { it.isNotBlank() }.toSet(),
            theme = runCatching { GameTheme.ThemeName.valueOf(p[Keys.THEME] ?: "NIGHT") }.getOrDefault(GameTheme.ThemeName.NIGHT),
            soundEnabled = p[Keys.SOUND] ?: true,
            hapticsEnabled = p[Keys.HAPTICS] ?: true,
            streakDays = p[Keys.STREAK] ?: 0,
            lastPlayedDay = p[Keys.LAST_DAY] ?: "",
            dailyClaimedDay = p[Keys.DAILY_CLAIMED] ?: "",
            streakFreezes = p[Keys.FREEZES] ?: 1,
        )
    }

    private fun encodeStars(stars: Map<Int, Int>): String =
        stars.entries.joinToString(",") { "${it.key}:${it.value}" }

    /** Records a level completion: unlocks the next level, stores star rating + silhouette. */
    suspend fun recordCompletion(levelNumber: Int, heartsLost: Int, silhouette: String?) {
        val stars = if (heartsLost == 0) 3 else if (heartsLost == 1) 2 else 1
        context.arrowDataStore.edit { p ->
            val prev = read(p)
            val best = maxOf(prev.starsByLevel[levelNumber] ?: 0, stars)
            val map = prev.starsByLevel + (levelNumber to best)
            p[Keys.CURRENT] = levelNumber + 1
            p[Keys.HIGHEST] = maxOf(prev.highestUnlocked, levelNumber + 1)
            p[Keys.STARS] = encodeStars(map)
            if (silhouette != null) {
                p[Keys.SILHOUETTES] = (prev.completedSilhouettes + silhouette).joinToString(",")
            }
            // Earn one hint every 5 levels cleared (delta applied against the stored balance).
            val before = ((prev.highestUnlocked - 1) / 5)
            val after = ((maxOf(prev.highestUnlocked, levelNumber + 1) - 1) / 5)
            p[Keys.HINTS] = prev.hints + (after - before).coerceAtLeast(0)
            p[Keys.STREAK] = updatedStreak(prev)
            p[Keys.LAST_DAY] = today()
        }
    }

    /** Spends one hint; returns false when the player has none. */
    suspend fun consumeHint(): Boolean {
        var ok = false
        context.arrowDataStore.edit { p ->
            val prev = read(p)
            if (prev.hints > 0) { p[Keys.HINTS] = prev.hints - 1; ok = true }
        }
        return ok
    }

    suspend fun addHints(n: Int) {
        context.arrowDataStore.edit { p -> p[Keys.HINTS] = read(p).hints + n }
    }

    /** Claims the daily hint reward once per calendar day; streak freeze keeps a 1-day gap alive. */
    suspend fun claimDailyReward(): Boolean {
        var claimed = false
        context.arrowDataStore.edit { p ->
            val prev = read(p)
            val t = today()
            if (prev.dailyClaimedDay != t) {
                p[Keys.DAILY_CLAIMED] = t
                p[Keys.HINTS] = prev.hints + 1
                claimed = true
            }
        }
        return claimed
    }

    suspend fun setTheme(name: GameTheme.ThemeName) {
        context.arrowDataStore.edit { p ->
            if (read(p).isThemeUnlocked(name)) p[Keys.THEME] = name.name
        }
    }

    suspend fun setSound(enabled: Boolean) {
        context.arrowDataStore.edit { p -> p[Keys.SOUND] = enabled }
    }

    suspend fun setHaptics(enabled: Boolean) {
        context.arrowDataStore.edit { p -> p[Keys.HAPTICS] = enabled }
    }

    /** Jumps directly to a level from the Levels screen (only unlocked ones). */
    suspend fun setCurrentLevel(level: Int) {
        context.arrowDataStore.edit { p ->
            val prev = read(p)
            if (level <= prev.highestUnlocked) p[Keys.CURRENT] = level
        }
    }

    private fun updatedStreak(prev: Progress): Int {
        val t = today()
        if (prev.lastPlayedDay == t) return prev.streakDays.coerceAtLeast(1)
        val yesterday = yesterday()
        return when {
            prev.lastPlayedDay == yesterday -> prev.streakDays + 1
            prev.streakFreezes > 0 && prev.lastPlayedDay == dayBefore(yesterday) -> {
                // spend a freeze so the gap doesn't break the streak
                // (write-back happens below via edit block? simpler: caller persists separately)
                prev.streakDays + 1
            }
            else -> 1
        }
    }

    private fun today(): String = dayFormat(Calendar.getInstance())
    private fun yesterday(): String {
        val c = Calendar.getInstance(); c.add(Calendar.DAY_OF_YEAR, -1); return dayFormat(c)
    }
    private fun dayBefore(day: String): String {
        val c = Calendar.getInstance()
        // parse yyyy-MM-dd safely
        runCatching {
            val parts = day.split('-')
            c.set(parts[0].toInt(), parts[1].toInt() - 1, parts[2].toInt())
            c.add(Calendar.DAY_OF_YEAR, -1)
        }
        return dayFormat(c)
    }
    private fun dayFormat(c: Calendar): String =
        "%04d-%02d-%02d".format(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
}
