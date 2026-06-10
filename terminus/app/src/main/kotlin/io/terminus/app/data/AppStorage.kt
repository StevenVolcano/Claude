package io.terminus.app.data

import android.content.Context
import io.terminus.core.cityfile.CityCodec
import io.terminus.core.cityfile.CityFile
import io.terminus.core.persistence.MatchRecord
import io.terminus.core.persistence.OptionsPreset
import io.terminus.core.persistence.ReplayLog
import io.terminus.core.persistence.TerminusJson
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * All on-disk app data under `context.filesDir` (ARCHITECTURE.md §5):
 *
 * - `cities/<cityId>.city.json.gz` — imported + copied-on-first-run bundled cities;
 * - `presets.json` — `List<OptionsPreset>`;
 * - `history/match-<epochSec>.json` — `MatchRecord` per finished match;
 * - `autosave.json` — sim-mode in-progress `ReplayLog` (command-log replay rebuilds
 *   the engine runtime that `GameState` deliberately does not serialize).
 *
 * Every write is atomic (temp file + rename) and every schema is owned by
 * `core.persistence` / `core.cityfile`, serialized with the shared [TerminusJson].
 */
class AppStorage(context: Context) {

    private val appContext = context.applicationContext
    private val filesDir: File = appContext.filesDir
    private val citiesDir: File = File(filesDir, "cities")
    private val historyDir: File = File(filesDir, "history")
    private val presetsFile: File = File(filesDir, "presets.json")
    private val autosaveFile: File = File(filesDir, "autosave.json")

    // ------------------------------------------------------------------ cities

    /**
     * Copies the bundled demo cities from `assets/cities/` into `filesDir/cities/`
     * once (existing files are never overwritten). Safe to call repeatedly.
     */
    fun copyBundledCitiesIfNeeded() {
        citiesDir.mkdirs()
        val names = appContext.assets.list("cities").orEmpty()
        for (name in names) {
            if (!name.endsWith(CityCodec.FILE_EXTENSION)) continue
            val target = File(citiesDir, name)
            if (target.exists()) continue
            val temp = File(citiesDir, "$name.tmp")
            appContext.assets.open("cities/$name").use { input ->
                temp.outputStream().use { output -> input.copyTo(output) }
            }
            renameAtomic(temp, target)
        }
    }

    /** All stored cities, sorted by display name. Unreadable files are skipped. */
    fun listCities(): List<CityFile> {
        val files = citiesDir.listFiles { f -> f.name.endsWith(CityCodec.FILE_EXTENSION) }.orEmpty()
        return files.mapNotNull { file ->
            runCatching { CityCodec.read(file.toPath()) }.getOrNull()
        }.sortedBy { it.displayName }
    }

    /** The stored city with [cityId], or null. */
    fun loadCity(cityId: String): CityFile? {
        val file = File(citiesDir, CityCodec.fileNameFor(cityId))
        if (!file.exists()) return null
        return runCatching { CityCodec.read(file.toPath()) }.getOrNull()
    }

    /** Atomically writes [city] to `cities/<cityId>.city.json.gz`. */
    fun saveCity(city: CityFile) {
        citiesDir.mkdirs()
        CityCodec.writeAtomic(city, File(citiesDir, CityCodec.fileNameFor(city.cityId)).toPath())
    }

    /** Deletes the stored city with [cityId]; returns true when a file was removed. */
    fun deleteCity(cityId: String): Boolean =
        File(citiesDir, CityCodec.fileNameFor(cityId)).delete()

    // ------------------------------------------------------------------ presets

    /** All saved option presets; an absent or corrupt file reads as empty. */
    fun loadPresets(): List<OptionsPreset> {
        if (!presetsFile.exists()) return emptyList()
        return runCatching {
            TerminusJson.json.decodeFromString(
                ListSerializer(OptionsPreset.serializer()),
                presetsFile.readText(Charsets.UTF_8),
            )
        }.getOrDefault(emptyList())
    }

    /** Atomically replaces the preset list. */
    fun savePresets(presets: List<OptionsPreset>) {
        writeTextAtomic(
            presetsFile,
            TerminusJson.json.encodeToString(ListSerializer(OptionsPreset.serializer()), presets),
        )
    }

    // ------------------------------------------------------------------ history

    /** All recorded matches, newest first. Unreadable files are skipped. */
    fun listMatches(): List<MatchRecord> {
        val files = historyDir.listFiles { f -> f.name.startsWith("match-") && f.name.endsWith(".json") }.orEmpty()
        return files.mapNotNull { file ->
            runCatching {
                TerminusJson.json.decodeFromString(MatchRecord.serializer(), file.readText(Charsets.UTF_8))
            }.getOrNull()
        }.sortedByDescending { it.createdEpochSec }
    }

    /** Atomically writes [record] to `history/match-<createdEpochSec>.json`. */
    fun saveMatch(record: MatchRecord) {
        historyDir.mkdirs()
        writeTextAtomic(
            File(historyDir, "match-${record.createdEpochSec}.json"),
            TerminusJson.json.encodeToString(MatchRecord.serializer(), record),
        )
    }

    // ------------------------------------------------------------------ autosave

    /** The sim-mode autosave replay log, or null when absent/corrupt. */
    fun loadAutosave(): ReplayLog? {
        if (!autosaveFile.exists()) return null
        return runCatching {
            TerminusJson.json.decodeFromString(ReplayLog.serializer(), autosaveFile.readText(Charsets.UTF_8))
        }.getOrNull()
    }

    /** Atomically writes the sim-mode autosave replay log (every 30 s real time). */
    fun saveAutosave(log: ReplayLog) {
        writeTextAtomic(autosaveFile, TerminusJson.json.encodeToString(ReplayLog.serializer(), log))
    }

    /** Removes the autosave (round finished or abandoned). */
    fun clearAutosave() {
        autosaveFile.delete()
    }

    // ------------------------------------------------------------------ helpers

    private fun writeTextAtomic(target: File, text: String) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, target.name + ".tmp")
        temp.writeText(text, Charsets.UTF_8)
        renameAtomic(temp, target)
    }

    private fun renameAtomic(temp: File, target: File) {
        try {
            Files.move(
                temp.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
