package io.terminus.core.cityfile

import io.terminus.core.geo.BoundingBox
import io.terminus.core.persistence.TerminusJson
import io.terminus.core.transit.TransitNetwork
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Encodes/decodes the on-disk `.city.json.gz` format (ARCHITECTURE.md §3.1):
 * kotlinx-serialization JSON wrapped in a `java.util.zip` GZIP stream, using the
 * shared [TerminusJson] configuration. Also provides the atomic-write helper
 * (temp + rename, ARCHITECTURE.md §5) and the [CityFile] ↔ [TransitNetwork] bridges.
 */
object CityCodec {

    /** Canonical file extension (ARCHITECTURE.md §3.1). */
    const val FILE_EXTENSION = ".city.json.gz"

    /** The file name a city is stored under: `<cityId>.city.json.gz` (ARCHITECTURE.md §5). */
    fun fileNameFor(cityId: String): String = cityId + FILE_EXTENSION

    /** Writes [city] as gzipped JSON to [output] and closes it. */
    fun encode(city: CityFile, output: OutputStream) {
        GZIPOutputStream(output).use { gz ->
            gz.write(TerminusJson.json.encodeToString(CityFile.serializer(), city).toByteArray(Charsets.UTF_8))
        }
    }

    /** [encode] to an in-memory byte array. */
    fun encodeToBytes(city: CityFile): ByteArray {
        val buffer = ByteArrayOutputStream()
        encode(city, buffer)
        return buffer.toByteArray()
    }

    /** Reads a gzipped-JSON city from [input] and closes it. */
    fun decode(input: InputStream): CityFile =
        GZIPInputStream(input).use { gz ->
            TerminusJson.json.decodeFromString(CityFile.serializer(), gz.readBytes().toString(Charsets.UTF_8))
        }

    /** [decode] from an in-memory byte array. */
    fun decode(bytes: ByteArray): CityFile = decode(ByteArrayInputStream(bytes))

    /** Reads the city stored at [path]. */
    fun read(path: Path): CityFile = decode(Files.newInputStream(path))

    /**
     * Atomically writes [city] to [target] (ARCHITECTURE.md §5: temp + rename): the
     * bytes go to a temp file in the same directory, then a rename replaces [target].
     * Readers never observe a partially written city file. Parent directories are
     * created as needed.
     */
    fun writeAtomic(city: CityFile, target: Path) {
        val dir = target.toAbsolutePath().parent
        Files.createDirectories(dir)
        val temp = Files.createTempFile(dir, target.fileName.toString(), ".tmp")
        try {
            encode(city, Files.newOutputStream(temp))
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    /** The in-memory transit graph for [city]'s stations/routes/edges. */
    fun toTransitNetwork(city: CityFile): TransitNetwork =
        TransitNetwork(city.stations, city.routes, city.edges)

    /**
     * Wraps [network] in a [CityFile] envelope, computing the bounding box from the
     * station coordinates.
     */
    fun fromNetwork(
        network: TransitNetwork,
        cityId: String,
        displayName: String,
        attribution: String,
        isSynthetic: Boolean,
        defaultStartStationId: String,
    ): CityFile {
        require(network.stations.isNotEmpty()) { "cannot build a CityFile from an empty network" }
        require(defaultStartStationId in network.stationsById) {
            "defaultStartStationId $defaultStartStationId is not a station of the network"
        }
        return CityFile(
            schemaVersion = 1,
            cityId = cityId,
            displayName = displayName,
            attribution = attribution,
            isSynthetic = isSynthetic,
            bbox = BoundingBox(
                minLat = network.stations.minOf { it.latLng.lat },
                minLon = network.stations.minOf { it.latLng.lon },
                maxLat = network.stations.maxOf { it.latLng.lat },
                maxLon = network.stations.maxOf { it.latLng.lon },
            ),
            stations = network.stations,
            routes = network.routes,
            edges = network.edges,
            defaultStartStationId = defaultStartStationId,
        )
    }
}
