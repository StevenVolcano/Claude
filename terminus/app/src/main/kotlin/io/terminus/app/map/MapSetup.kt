package io.terminus.app.map

import android.content.Context
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import java.io.File

/**
 * osmdroid global configuration (ARCHITECTURE.md §1.2 `map`):
 * disk tile cache under `cacheDir`, the project-identifying user agent required by
 * the OpenStreetMap tile usage policy, and the standard Mapnik tile source with a
 * visible attribution overlay. The game tolerates missing tiles — overlays still
 * render over a blank grid.
 */
object MapSetup {

    /** User agent identifying this app to the OSM tile servers (OSM tile policy). */
    const val USER_AGENT = "terminus-game"

    @Volatile
    private var initialized = false

    /** Idempotent; call before the first [MapView] is created. */
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        val config = Configuration.getInstance()
        config.userAgentValue = USER_AGENT
        val base = File(context.applicationContext.cacheDir, "osmdroid")
        config.osmdroidBasePath = base
        config.osmdroidTileCache = File(base, "tiles")
    }

    /**
     * A [MapView] preconfigured with the OpenStreetMap Mapnik source, multi-touch
     * controls, and the "© OpenStreetMap contributors" attribution overlay.
     */
    fun newMapView(context: Context): MapView {
        init(context)
        val mapView = MapView(context)
        mapView.setTileSource(TileSourceFactory.MAPNIK)
        mapView.setMultiTouchControls(true)
        mapView.overlays.add(CopyrightOverlay(context))
        return mapView
    }
}
