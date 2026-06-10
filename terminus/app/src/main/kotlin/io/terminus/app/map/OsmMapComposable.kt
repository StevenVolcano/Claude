package io.terminus.app.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.terminus.core.geo.LatLng
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay

/**
 * An osmdroid [MapView] wrapped for Compose (ARCHITECTURE.md §1.2): lifecycle-aware
 * (onResume/onPause/onDetach), with an optional tap callback and an [update] block
 * re-run on every recomposition for overlay refresh.
 */
@Composable
fun OsmMap(
    modifier: Modifier = Modifier,
    onTap: ((LatLng) -> Unit)? = null,
    onCreate: (MapView) -> Unit = {},
    update: (MapView) -> Unit = {},
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val mapHolder = remember { mutableStateOf<MapView?>(null) }
    val currentOnTap by rememberUpdatedState(onTap)

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapHolder.value?.onResume()
                Lifecycle.Event.ON_PAUSE -> mapHolder.value?.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapHolder.value?.onDetach()
            mapHolder.value = null
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            val mapView = MapSetup.newMapView(context)
            val receiver = object : MapEventsReceiver {
                override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                    val tap = currentOnTap ?: return false
                    if (p != null) tap(LatLng(p.latitude, p.longitude))
                    return true
                }

                override fun longPressHelper(p: GeoPoint?): Boolean = false
            }
            mapView.overlays.add(MapEventsOverlay(receiver))
            onCreate(mapView)
            mapHolder.value = mapView
            mapView
        },
        update = { mapView ->
            update(mapView)
            mapView.invalidate()
        },
    )
}
