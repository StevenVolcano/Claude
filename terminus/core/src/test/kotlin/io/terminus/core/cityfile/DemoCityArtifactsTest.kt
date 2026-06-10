package io.terminus.core.cityfile

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Generator-vs-checked-in-artifact equality (ARCHITECTURE.md §4, §6 item 3).
 *
 * The comparison is on the decoded [CityFile]s, not on the gz bytes, so GZIP/JSON
 * encoding details can never produce false negatives.
 *
 * To (re)generate all four artifacts after changing [DemoCities], run:
 * `TERMINUS_CITY_ARTIFACTS_ROOT=/path/to/terminus ./gradlew :core:test --tests 'io.terminus.core.cityfile.*'`
 */
class DemoCityArtifactsTest {

    private fun decodeResource(name: String): CityFile {
        val stream = assertNotNull(
            javaClass.getResourceAsStream("/cities/$name"),
            "checked-in resource /cities/$name is missing; see the class KDoc for how to regenerate",
        )
        return CityCodec.decode(stream)
    }

    @Test
    fun `demoville generator matches the checked-in test resource`() {
        assertEquals(DemoCities.demoville(), decodeResource(CityCodec.fileNameFor(DemoCities.DEMOVILLE_ID)))
    }

    @Test
    fun `port saltmarsh generator matches the checked-in test resource`() {
        assertEquals(DemoCities.portSaltmarsh(), decodeResource(CityCodec.fileNameFor(DemoCities.PORT_SALTMARSH_ID)))
    }

    @Test
    fun `bundled app assets match the generator when the app module is present`() {
        val assetsDir = repoRoot()?.resolve("app/src/main/assets/cities") ?: return
        if (!Files.isDirectory(assetsDir)) return // app module not checked out in this environment
        for (city in DemoCities.all()) {
            val path = assetsDir.resolve(CityCodec.fileNameFor(city.cityId))
            assertEquals(city, CityCodec.read(path), "stale bundled asset $path")
        }
    }

    /**
     * Not a test of behavior: when TERMINUS_CITY_ARTIFACTS_ROOT is set to the repo
     * root, rewrites the four checked-in artifacts from the generator (atomic writes).
     * Without the variable this is a no-op, so normal test runs never touch the repo.
     */
    @Test
    fun `regenerate checked-in artifacts when TERMINUS_CITY_ARTIFACTS_ROOT is set`() {
        val root = System.getenv("TERMINUS_CITY_ARTIFACTS_ROOT")?.let(Paths::get) ?: return
        for (city in DemoCities.all()) {
            val name = CityCodec.fileNameFor(city.cityId)
            CityCodec.writeAtomic(city, root.resolve("core/src/test/resources/cities/$name"))
            CityCodec.writeAtomic(city, root.resolve("app/src/main/assets/cities/$name"))
        }
    }

    /** Walks up from the working directory to the directory containing settings.gradle.kts. */
    private fun repoRoot(): Path? {
        var dir: Path? = Paths.get("").toAbsolutePath()
        while (dir != null) {
            if (Files.exists(dir.resolve("settings.gradle.kts"))) return dir
            dir = dir.parent
        }
        return null
    }
}
