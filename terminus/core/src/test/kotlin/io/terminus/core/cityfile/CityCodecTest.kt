package io.terminus.core.cityfile

import java.nio.file.Files
import java.util.zip.GZIPInputStream
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** CityFile <-> .city.json.gz round trips per ARCHITECTURE.md §6 item 3. */
class CityCodecTest {

    @Test
    fun `round trip through bytes preserves the city exactly`() {
        for (city in DemoCities.all()) {
            val bytes = CityCodec.encodeToBytes(city)
            assertEquals(city, CityCodec.decode(bytes))
            // It really is GZIP (magic bytes) wrapping JSON.
            assertEquals(0x1f, bytes[0].toInt() and 0xff)
            assertEquals(0x8b, bytes[1].toInt() and 0xff)
            val json = GZIPInputStream(bytes.inputStream()).readBytes().toString(Charsets.UTF_8)
            assertTrue(json.startsWith("{") && "\"schemaVersion\"" in json)
        }
    }

    @Test
    fun `writeAtomic creates parent dirs writes and cleans up temp files`() {
        val dir = Files.createTempDirectory("citycodec-test")
        try {
            val target = dir.resolve("nested/cities").resolve(CityCodec.fileNameFor(DemoCities.DEMOVILLE_ID))
            assertEquals("demoville.city.json.gz", target.fileName.toString())

            val demoville = DemoCities.demoville()
            CityCodec.writeAtomic(demoville, target)
            assertTrue(target.exists())
            assertEquals(demoville, CityCodec.read(target))

            // Overwrite with the other city: replaced, no temp residue.
            val saltmarsh = DemoCities.portSaltmarsh()
            CityCodec.writeAtomic(saltmarsh, target)
            assertEquals(saltmarsh, CityCodec.read(target))
            assertEquals(listOf(target), target.parent.listDirectoryEntries())
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `toTransitNetwork and fromNetwork are inverses`() {
        val city = DemoCities.demoville()
        val network = CityCodec.toTransitNetwork(city)
        assertEquals(city.stations, network.stations)
        assertEquals(city.routes, network.routes)
        assertEquals(city.edges, network.edges)

        val rebuilt = CityCodec.fromNetwork(
            network = network,
            cityId = city.cityId,
            displayName = city.displayName,
            attribution = city.attribution,
            isSynthetic = city.isSynthetic,
            defaultStartStationId = city.defaultStartStationId,
        )
        assertEquals(city, rebuilt)
    }

    @Test
    fun `fromNetwork rejects an unknown start station`() {
        val network = CityCodec.toTransitNetwork(DemoCities.demoville())
        assertFailsWith<IllegalArgumentException> {
            CityCodec.fromNetwork(network, "x", "X", "a", true, "NOPE")
        }
    }
}
