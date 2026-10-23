package org.microg.gms.maps.mapbox

import android.content.Context
import android.content.res.Configuration
import com.google.android.gms.maps.model.MapStyleOptions
import org.json.JSONArray

private const val SCHEME_DARK = 1
private const val SCHEME_FOLLOW_SYSTEM = 2

// Google's documented "night" map style, applied through the same styling rules apps use (MapStyleOptions).
private const val NIGHT_STYLE = """[
  {"elementType":"geometry","stylers":[{"color":"#242f3e"}]},
  {"elementType":"labels.text.stroke","stylers":[{"color":"#242f3e"}]},
  {"elementType":"labels.text.fill","stylers":[{"color":"#746855"}]},
  {"featureType":"administrative.locality","elementType":"labels.text.fill","stylers":[{"color":"#d59563"}]},
  {"featureType":"poi","elementType":"labels.text.fill","stylers":[{"color":"#d59563"}]},
  {"featureType":"poi.park","elementType":"geometry","stylers":[{"color":"#263c3f"}]},
  {"featureType":"poi.park","elementType":"labels.text.fill","stylers":[{"color":"#6b9a76"}]},
  {"featureType":"road","elementType":"geometry","stylers":[{"color":"#38414e"}]},
  {"featureType":"road","elementType":"geometry.stroke","stylers":[{"color":"#212a37"}]},
  {"featureType":"road","elementType":"labels.text.fill","stylers":[{"color":"#9ca5b3"}]},
  {"featureType":"road.highway","elementType":"geometry","stylers":[{"color":"#746855"}]},
  {"featureType":"road.highway","elementType":"geometry.stroke","stylers":[{"color":"#1f2835"}]},
  {"featureType":"road.highway","elementType":"labels.text.fill","stylers":[{"color":"#f3d19c"}]},
  {"featureType":"transit","elementType":"geometry","stylers":[{"color":"#2f3948"}]},
  {"featureType":"transit.station","elementType":"labels.text.fill","stylers":[{"color":"#d59563"}]},
  {"featureType":"water","elementType":"geometry","stylers":[{"color":"#17263c"}]},
  {"featureType":"water","elementType":"labels.text.fill","stylers":[{"color":"#515c6d"}]},
  {"featureType":"water","elementType":"labels.text.stroke","stylers":[{"color":"#17263c"}]}
]"""

/** The app's style with the night style in front of it when [scheme] asks for a dark map (dark, or the system is dark). */
fun withColorScheme(context: Context, scheme: Int, style: MapStyleOptions?): MapStyleOptions? {
    val dark = scheme == SCHEME_DARK || (scheme == SCHEME_FOLLOW_SYSTEM &&
            (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES)
    if (!dark) return style
    val rules = JSONArray(NIGHT_STYLE)
    // The app's own rules come last, so they still win where they overlap.
    style?.getJson()?.let { json -> runCatching { JSONArray(json) }.getOrNull()?.let { app -> for (i in 0 until app.length()) rules.put(app.get(i)) } }
    return MapStyleOptions(rules.toString())
}
