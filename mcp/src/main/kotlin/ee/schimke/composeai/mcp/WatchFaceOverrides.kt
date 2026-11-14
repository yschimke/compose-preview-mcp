package ee.schimke.composeai.mcp

import ee.schimke.composeai.daemon.protocol.AmbientOverride
import ee.schimke.composeai.daemon.protocol.AmbientStateOverride
import ee.schimke.composeai.daemon.protocol.RemoteComposeOverride
import ee.schimke.composeai.daemon.protocol.RemoteNamedValue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Translates structured watch-face overrides (`overrides.complications` and `overrides.watchFace`)
 * into Remote Compose `USER`-domain named values (`dwf.complication.<slotId>.*`, `dwf.*`,
 * `metrics.*`) carried inside [RemoteComposeOverride.namedValues].
 *
 * Two modes for `overrides.complications.<slotId>`:
 * 1. **Change installed complication type** (`type` specified, e.g. `"SHORT_TEXT"`,
 *    `"RANGED_VALUE"`, `"GOAL_PROGRESS"`, `"WEIGHTED_ELEMENTS"`, `"MONOCHROMATIC_IMAGE"`,
 *    `"SMALL_IMAGE"`, `"PHOTO_IMAGE"`, `"LONG_TEXT"`, `"EMPTY"`): resets unmentioned slot fields
 *    (`title`, `text`, image presence flags) so leftover seed values from the previous complication
 *    do not bleed into the newly installed complication type.
 * 2. **Update complication data in place** (`type` omitted, e.g. `{"value": 85, "text": "85%"}`):
 *    patches only the explicitly supplied fields while preserving the slot's current complication
 *    type and unmentioned fields.
 */
internal object WatchFaceOverrides {

  const val MAX_COLOR_STOPS: Int = 4

  const val TYPE_EMPTY: Int = 0
  const val TYPE_SHORT_TEXT: Int = 1
  const val TYPE_LONG_TEXT: Int = 2
  const val TYPE_RANGED_VALUE: Int = 3
  const val TYPE_MONOCHROMATIC_IMAGE: Int = 4
  const val TYPE_SMALL_IMAGE: Int = 5
  const val TYPE_PHOTO_IMAGE: Int = 6
  const val TYPE_GOAL_PROGRESS: Int = 7
  const val TYPE_WEIGHTED_ELEMENTS: Int = 8

  private val validTypeNames =
    listOf(
      "EMPTY",
      "SHORT_TEXT",
      "LONG_TEXT",
      "RANGED_VALUE",
      "GOAL_PROGRESS",
      "WEIGHTED_ELEMENTS",
      "MONOCHROMATIC_IMAGE",
      "SMALL_IMAGE",
      "PHOTO_IMAGE",
    )

  data class DecodedWatchFaceOverrides(
    val remoteCompose: RemoteComposeOverride?,
    val ambient: AmbientOverride?,
  )

  fun mergeIntoRemoteCompose(
    overridesObj: JsonObject,
    explicitRemoteCompose: RemoteComposeOverride?,
    explicitAmbient: AmbientOverride?,
  ): DecodedWatchFaceOverrides {
    val complicationsElem = overridesObj["complications"]?.takeUnless { it is JsonNull }
    val watchFaceElem = overridesObj["watchFace"]?.takeUnless { it is JsonNull }
    if (complicationsElem == null && watchFaceElem == null) {
      return DecodedWatchFaceOverrides(explicitRemoteCompose, explicitAmbient)
    }

    val generated = LinkedHashMap<String, RemoteNamedValue>()
    var synthesizedAmbient: AmbientOverride? = explicitAmbient

    if (watchFaceElem != null) {
      val wfObj =
        (watchFaceElem as? JsonObject) ?: error("watchFace overrides must be a JSON object")
      decodeWatchFaceGlobals(wfObj, generated)?.let { isAmb ->
        if (synthesizedAmbient == null) {
          synthesizedAmbient =
            AmbientOverride(
              state = if (isAmb) AmbientStateOverride.AMBIENT else AmbientStateOverride.INTERACTIVE,
            )
        }
      }
    }

    if (complicationsElem != null) {
      val compObj =
        (complicationsElem as? JsonObject)
          ?: error(
            "complications overrides must be a JSON object keyed by slotId (e.g. \"0\", \"1\")",
          )
      decodeComplications(compObj, generated)
    }

    if (generated.isEmpty()) {
      return DecodedWatchFaceOverrides(explicitRemoteCompose, synthesizedAmbient)
    }

    val mergedNamedValues = LinkedHashMap<String, RemoteNamedValue>(generated)
    // Explicit `remoteCompose.namedValues` takes precedence on exact key collisions.
    if (explicitRemoteCompose != null) {
      mergedNamedValues.putAll(explicitRemoteCompose.namedValues)
    }

    val mergedRc =
      (explicitRemoteCompose?.newBuilder() ?: RemoteComposeOverride.Builder())
        .apply { namedValues = mergedNamedValues }
        .build()

    return DecodedWatchFaceOverrides(mergedRc, synthesizedAmbient)
  }

  private fun decodeComplications(
    compObj: JsonObject,
    out: MutableMap<String, RemoteNamedValue>,
  ) {
    for ((rawKey, slotElem) in compObj) {
      val slotId =
        rawKey.trim().removePrefix("slot_").toIntOrNull()?.takeIf { it >= 0 }
          ?: error(
            "complications key '$rawKey' must be a non-negative integer slotId (e.g. \"0\", \"1\")",
          )
      if (slotElem is JsonNull) {
        applyEmptySlot(slotId, out)
        continue
      }
      if (slotElem is JsonPrimitive && slotElem.isString) {
        val shorthandObject = JsonObject(mapOf("type" to slotElem))
        decodeSingleSlot(slotId, shorthandObject, out)
        continue
      }
      val slotObj =
        (slotElem as? JsonObject)
          ?: error("complications['$rawKey'] must be an object or type string")
      decodeSingleSlot(slotId, slotObj, out)
    }
  }

  private fun applyEmptySlot(slotId: Int, out: MutableMap<String, RemoteNamedValue>) {
    out["dwf.complication.$slotId.type"] = RemoteNamedValue.IntValue(TYPE_EMPTY)
    out["dwf.complication.$slotId.text"] = RemoteNamedValue.StringValue("")
    out["dwf.complication.$slotId.title"] = RemoteNamedValue.StringValue("")
    out["dwf.complication.$slotId.has_monochromatic_image"] = RemoteNamedValue.BooleanValue(false)
    out["dwf.complication.$slotId.has_small_image"] = RemoteNamedValue.BooleanValue(false)
    out["dwf.complication.$slotId.has_photo_image"] = RemoteNamedValue.BooleanValue(false)
  }

  private fun decodeSingleSlot(
    slotId: Int,
    slotObj: JsonObject,
    out: MutableMap<String, RemoteNamedValue>,
  ) {
    val prefix = "dwf.complication.$slotId"
    val rawType = optionalString(slotObj, "type", "complications['$slotId'].type")
    val typeCode: Int? = rawType?.let { parseComplicationType(it, slotId) }

    if (typeCode == TYPE_EMPTY) {
      applyEmptySlot(slotId, out)
      return
    }

    if (typeCode != null) {
      out["$prefix.type"] = RemoteNamedValue.IntValue(typeCode)
    }

    // Strings: explicit null clears to ""; when `type` is explicitly installed, unmentioned
    // text/title
    // reset to "" so previous complication text/title seeds do not leak across types.
    val hasTextKey = "text" in slotObj
    val hasTitleKey = "title" in slotObj
    if (hasTextKey) {
      val textVal = nullableString(slotObj["text"], "complications['$slotId'].text").orEmpty()
      out["$prefix.text"] = RemoteNamedValue.StringValue(textVal)
    } else if (typeCode != null) {
      out["$prefix.text"] = RemoteNamedValue.StringValue("")
    }

    if (hasTitleKey) {
      val titleVal = nullableString(slotObj["title"], "complications['$slotId'].title").orEmpty()
      out["$prefix.title"] = RemoteNamedValue.StringValue(titleVal)
    } else if (typeCode != null) {
      out["$prefix.title"] = RemoteNamedValue.StringValue("")
    }

    // Numeric value / min / max (with `targetValue` alias for GOAL_PROGRESS).
    optionalFloat(slotObj, "value", "complications['$slotId'].value")?.let {
      out["$prefix.value"] = RemoteNamedValue.FloatValue(it)
    }
    optionalFloat(slotObj, "min", "complications['$slotId'].min")?.let {
      out["$prefix.min"] = RemoteNamedValue.FloatValue(it)
    }
    val explicitMax =
      optionalFloat(slotObj, "max", "complications['$slotId'].max")
        ?: optionalFloat(slotObj, "targetValue", "complications['$slotId'].targetValue")
    if (explicitMax != null) {
      out["$prefix.max"] = RemoteNamedValue.FloatValue(explicitMax)
    }

    // Image presence flags (`monochromaticImage` / `icon`, `smallImage`, `photoImage`).
    val explicitMono =
      optionalBoolean(slotObj, "monochromaticImage", "complications['$slotId'].monochromaticImage")
        ?: optionalBoolean(slotObj, "icon", "complications['$slotId'].icon")
    val explicitSmall =
      optionalBoolean(slotObj, "smallImage", "complications['$slotId'].smallImage")
    val explicitPhoto =
      optionalBoolean(slotObj, "photoImage", "complications['$slotId'].photoImage")

    if (explicitMono != null) {
      out["$prefix.has_monochromatic_image"] = RemoteNamedValue.BooleanValue(explicitMono)
    } else if (typeCode != null) {
      out["$prefix.has_monochromatic_image"] =
        RemoteNamedValue.BooleanValue(typeCode == TYPE_MONOCHROMATIC_IMAGE)
    }

    if (explicitSmall != null) {
      out["$prefix.has_small_image"] = RemoteNamedValue.BooleanValue(explicitSmall)
    } else if (typeCode != null) {
      out["$prefix.has_small_image"] = RemoteNamedValue.BooleanValue(typeCode == TYPE_SMALL_IMAGE)
    }

    if (explicitPhoto != null) {
      out["$prefix.has_photo_image"] = RemoteNamedValue.BooleanValue(explicitPhoto)
    } else if (typeCode != null) {
      out["$prefix.has_photo_image"] = RemoteNamedValue.BooleanValue(typeCode == TYPE_PHOTO_IMAGE)
    }

    // Procedural icon/photo colors.
    optionalColor(slotObj, "imageColor", "complications['$slotId'].imageColor")?.let {
      out["$prefix.image_color"] = RemoteNamedValue.ColorValue(it)
    }
    optionalColor(slotObj, "accentColor", "complications['$slotId'].accentColor")?.let {
      out["$prefix.accent_color"] = RemoteNamedValue.ColorValue(it)
    }

    // Color ramps & weighted elements (`colors`, `weights`, `interpolate`).
    val colorsElem = slotObj["colors"]
    val weightsElem = slotObj["weights"]

    val parsedColors: List<String>? =
      when (colorsElem) {
        null -> null
        is JsonNull -> emptyList()
        is JsonArray -> {
          if (colorsElem.isEmpty()) {
            emptyList()
          } else {
            if (colorsElem.size > MAX_COLOR_STOPS) {
              error(
                "complications['$slotId'].colors supports 1..$MAX_COLOR_STOPS stops, got ${colorsElem.size}",
              )
            }
            colorsElem.mapIndexed { idx, el ->
              val rawHex =
                (el as? JsonPrimitive)?.contentOrNull
                  ?: error("complications['$slotId'].colors[$idx] must be a hex color string")
              normalizeHexColor(rawHex, "complications['$slotId'].colors[$idx]")
            }
          }
        }
        else -> error("complications['$slotId'].colors must be an array of hex colors")
      }

    val parsedWeights: List<Float>? =
      when (weightsElem) {
        null -> null
        is JsonNull -> emptyList()
        is JsonArray -> {
          if (weightsElem.isEmpty()) {
            emptyList()
          } else {
            if (weightsElem.size > MAX_COLOR_STOPS) {
              error(
                "complications['$slotId'].weights supports 1..$MAX_COLOR_STOPS values, got ${weightsElem.size}",
              )
            }
            weightsElem.mapIndexed { idx, el ->
              val v =
                (el as? JsonPrimitive)?.contentOrNull?.toFloatOrNull()
                  ?: error("complications['$slotId'].weights[$idx] must be a number")
              if (v < 0f) {
                error("complications['$slotId'].weights[$idx] must be >= 0, got $v")
              }
              v
            }
          }
        }
        else -> error("complications['$slotId'].weights must be an array of numbers")
      }

    if (parsedColors != null) {
      if (parsedColors.isEmpty()) {
        out["$prefix.has_ranged_value_colors"] = RemoteNamedValue.BooleanValue(false)
        out["$prefix.has_goal_progress_colors"] = RemoteNamedValue.BooleanValue(false)
        if (parsedWeights == null) {
          out["$prefix.has_weighted_elements"] = RemoteNamedValue.BooleanValue(false)
        }
      } else {
        out["$prefix.color_count"] = RemoteNamedValue.IntValue(parsedColors.size)
        val padColor = parsedColors.last()
        for (idx in 0 until MAX_COLOR_STOPS) {
          out["$prefix.color.$idx"] =
            RemoteNamedValue.ColorValue(parsedColors.getOrElse(idx) { padColor })
        }
        when (typeCode) {
          TYPE_RANGED_VALUE ->
            out["$prefix.has_ranged_value_colors"] = RemoteNamedValue.BooleanValue(true)
          TYPE_GOAL_PROGRESS ->
            out["$prefix.has_goal_progress_colors"] = RemoteNamedValue.BooleanValue(true)
          TYPE_WEIGHTED_ELEMENTS ->
            out["$prefix.has_weighted_elements"] = RemoteNamedValue.BooleanValue(true)
          else -> {
            out["$prefix.has_ranged_value_colors"] = RemoteNamedValue.BooleanValue(true)
            out["$prefix.has_goal_progress_colors"] = RemoteNamedValue.BooleanValue(true)
            if (parsedWeights != null) {
              out["$prefix.has_weighted_elements"] = RemoteNamedValue.BooleanValue(true)
            }
          }
        }
      }
    } else if (typeCode == TYPE_WEIGHTED_ELEMENTS) {
      out["$prefix.has_weighted_elements"] = RemoteNamedValue.BooleanValue(true)
    }

    if (parsedWeights != null) {
      if (parsedWeights.isEmpty()) {
        out["$prefix.has_weighted_elements"] = RemoteNamedValue.BooleanValue(false)
      } else {
        out["$prefix.has_weighted_elements"] = RemoteNamedValue.BooleanValue(true)
        if (parsedColors == null) {
          out["$prefix.color_count"] = RemoteNamedValue.IntValue(parsedWeights.size)
        }
        for (idx in 0 until MAX_COLOR_STOPS) {
          out["$prefix.weight.$idx"] =
            RemoteNamedValue.FloatValue(parsedWeights.getOrElse(idx) { 0f })
        }
      }
    }

    optionalBoolean(slotObj, "interpolate", "complications['$slotId'].interpolate")?.let { interp ->
      out["$prefix.ranged_value_colors_interpolate"] = RemoteNamedValue.BooleanValue(interp)
      out["$prefix.goal_progress_colors_interpolate"] = RemoteNamedValue.BooleanValue(interp)
    }
  }

  private fun decodeWatchFaceGlobals(
    wfObj: JsonObject,
    out: MutableMap<String, RemoteNamedValue>,
  ): Boolean? {
    var ambientFlag: Boolean? = null
    optionalBoolean(wfObj, "isAmbient", "watchFace.isAmbient")?.let {
      out["dwf.is_ambient"] = RemoteNamedValue.BooleanValue(it)
      ambientFlag = it
    }
    optionalBoolean(wfObj, "is24Hour", "watchFace.is24Hour")?.let {
      out["dwf.is_24_hour"] = RemoteNamedValue.BooleanValue(it)
    }
    optionalBoolean(wfObj, "isDst", "watchFace.isDst")?.let {
      out["dwf.is_dst"] = RemoteNamedValue.BooleanValue(it)
    }
    val explicitBatteryLow = optionalBoolean(wfObj, "batteryIsLow", "watchFace.batteryIsLow")
    optionalFloat(wfObj, "batteryPercent", "watchFace.batteryPercent")?.let { pct ->
      out["dwf.battery_percent"] = RemoteNamedValue.FloatValue(pct)
      if (explicitBatteryLow == null) {
        out["dwf.battery_is_low"] = RemoteNamedValue.BooleanValue(pct <= 15f)
      }
    }
    if (explicitBatteryLow != null) {
      out["dwf.battery_is_low"] = RemoteNamedValue.BooleanValue(explicitBatteryLow)
    }
    optionalBoolean(wfObj, "batteryCharging", "watchFace.batteryCharging")?.let {
      out["dwf.battery_charging"] = RemoteNamedValue.BooleanValue(it)
    }
    optionalInt(wfObj, "unreadNotifications", "watchFace.unreadNotifications")?.let {
      out["dwf.unread_notifications"] = RemoteNamedValue.IntValue(it)
    }
    optionalInt(wfObj, "stepCount", "watchFace.stepCount")?.let {
      out["metrics.day.steps"] = RemoteNamedValue.IntValue(it)
    }
    optionalFloat(wfObj, "stepGoal", "watchFace.stepGoal")?.let {
      out["dwf.step_goal"] = RemoteNamedValue.FloatValue(it)
    }
    optionalFloat(wfObj, "heartRate", "watchFace.heartRate")?.let {
      out["metrics.heart_rate"] = RemoteNamedValue.FloatValue(it)
    }
    optionalFloat(wfObj, "calories", "watchFace.calories")?.let {
      out["metrics.day.calories"] = RemoteNamedValue.FloatValue(it)
    }
    optionalFloat(wfObj, "distance", "watchFace.distance")?.let {
      out["metrics.day.distance"] = RemoteNamedValue.FloatValue(it)
    }
    optionalFloat(wfObj, "floors", "watchFace.floors")?.let {
      out["metrics.day.flights_of_stairs"] = RemoteNamedValue.FloatValue(it)
    }

    wfObj["configurations"]
      ?.takeUnless { it is JsonNull }
      ?.let { cfgElem ->
        val cfgObj = (cfgElem as? JsonObject) ?: error("watchFace.configurations must be an object")
        for ((cfgId, rawVal) in cfgObj) {
          if (rawVal is JsonNull) continue
          val prim =
            (rawVal as? JsonPrimitive)
              ?: error("watchFace.configurations['$cfgId'] must be boolean, integer, or string")
          val content = prim.content
          val namedVal: RemoteNamedValue =
            if (prim.isString) {
              RemoteNamedValue.StringValue(content)
            } else {
              content.toBooleanStrictOrNull()?.let { RemoteNamedValue.BooleanValue(it) }
                ?: content.toIntOrNull()?.let { RemoteNamedValue.IntValue(it) }
                ?: content.toFloatOrNull()?.let { RemoteNamedValue.FloatValue(it) }
                ?: RemoteNamedValue.StringValue(content)
            }
          out["dwf.config.$cfgId"] = namedVal
        }
      }

    wfObj["themeColors"]
      ?.takeUnless { it is JsonNull }
      ?.let { themeElem ->
        val themeObj =
          (themeElem as? JsonObject) ?: error("watchFace.themeColors must be an object")
        for ((rawColorKey, rawHexElem) in themeObj) {
          if (rawHexElem is JsonNull) continue
          val rawHex =
            (rawHexElem as? JsonPrimitive)?.contentOrNull
              ?: error("watchFace.themeColors['$rawColorKey'] must be a hex color string")
          val normalized = normalizeHexColor(rawHex, "watchFace.themeColors['$rawColorKey']")
          val dot = rawColorKey.lastIndexOf('.')
          val fullKey =
            if (dot > 0 && rawColorKey.substring(dot + 1).toIntOrNull() != null) {
              "dwf.color.$rawColorKey"
            } else {
              "dwf.color.$rawColorKey.0"
            }
          out[fullKey] = RemoteNamedValue.ColorValue(normalized)
        }
      }

    return ambientFlag
  }

  private fun parseComplicationType(raw: String, slotId: Int): Int =
    when (raw.trim().uppercase().removePrefix("COMPLICATION_TYPE_")) {
      "EMPTY",
      "NO_DATA",
      "NOT_CONFIGURED" -> TYPE_EMPTY
      "SHORT_TEXT" -> TYPE_SHORT_TEXT
      "LONG_TEXT" -> TYPE_LONG_TEXT
      "RANGED_VALUE" -> TYPE_RANGED_VALUE
      "MONOCHROMATIC_IMAGE",
      "ICON" -> TYPE_MONOCHROMATIC_IMAGE
      "SMALL_IMAGE" -> TYPE_SMALL_IMAGE
      "PHOTO_IMAGE",
      "LARGE_IMAGE" -> TYPE_PHOTO_IMAGE
      "GOAL_PROGRESS" -> TYPE_GOAL_PROGRESS
      "WEIGHTED_ELEMENTS" -> TYPE_WEIGHTED_ELEMENTS
      else ->
        error(
          "complications['$slotId'].type='$raw' is not valid; supported types: ${validTypeNames.joinToString(", ")}",
        )
    }

  private fun normalizeHexColor(raw: String, fieldName: String): String {
    val trimmed = raw.trim()
    val hex = trimmed.removePrefix("#")
    return when (hex.length) {
      6 -> {
        hex.toLongOrNull(16) ?: error("$fieldName must be #RRGGBB or #AARRGGBB, got '$raw'")
        "#FF${hex.uppercase()}"
      }
      8 -> {
        hex.toLongOrNull(16) ?: error("$fieldName must be #RRGGBB or #AARRGGBB, got '$raw'")
        "#${hex.uppercase()}"
      }
      else -> error("$fieldName must be #RRGGBB or #AARRGGBB, got '$raw'")
    }
  }

  private fun optionalString(obj: JsonObject, key: String, fieldName: String): String? {
    val elem = obj[key]?.takeUnless { it is JsonNull } ?: return null
    val prim = (elem as? JsonPrimitive) ?: error("$fieldName must be a string")
    return prim.contentOrNull
  }

  private fun nullableString(elem: JsonElement?, fieldName: String): String? {
    if (elem == null || elem is JsonNull) return null
    val prim = (elem as? JsonPrimitive) ?: error("$fieldName must be a string")
    return prim.content
  }

  private fun optionalFloat(obj: JsonObject, key: String, fieldName: String): Float? {
    val elem = obj[key]?.takeUnless { it is JsonNull } ?: return null
    val raw = (elem as? JsonPrimitive)?.contentOrNull ?: error("$fieldName must be a number")
    return raw.toFloatOrNull() ?: error("$fieldName must be a number, got '$raw'")
  }

  private fun optionalInt(obj: JsonObject, key: String, fieldName: String): Int? {
    val elem = obj[key]?.takeUnless { it is JsonNull } ?: return null
    val raw = (elem as? JsonPrimitive)?.contentOrNull ?: error("$fieldName must be an integer")
    return raw.toIntOrNull() ?: error("$fieldName must be an integer, got '$raw'")
  }

  private fun optionalBoolean(obj: JsonObject, key: String, fieldName: String): Boolean? {
    val elem = obj[key]?.takeUnless { it is JsonNull } ?: return null
    val raw = (elem as? JsonPrimitive)?.contentOrNull ?: error("$fieldName must be true or false")
    return raw.toBooleanStrictOrNull() ?: error("$fieldName must be true or false, got '$raw'")
  }

  private fun optionalColor(obj: JsonObject, key: String, fieldName: String): String? {
    val raw = optionalString(obj, key, fieldName) ?: return null
    return normalizeHexColor(raw, fieldName)
  }
}
