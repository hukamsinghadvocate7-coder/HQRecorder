package com.hqrec.app

import android.media.MediaCodecInfo
import android.media.MediaCodecList

/** Phone ka hardware encoder dhoondta hai aur check karta hai ki kaunsi resolution chal sakti hai. */
object Caps {
    fun pick(mime: String): MediaCodecInfo? {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, true) } }
        return list.firstOrNull { it.isHardwareAccelerated && !it.isSoftwareOnly }
            ?: list.firstOrNull()
    }

    fun maxBitrate(info: MediaCodecInfo, mime: String): Int =
        info.getCapabilitiesForType(mime).videoCapabilities.bitrateRange.upper

    /** longSide = 3840 / 2560 / 1920 / 1280. Supported ho to (width, height) deta hai, warna null. */
    fun fit(
        info: MediaCodecInfo, mime: String, longSide: Int,
        aspect: Float, fps: Int, landscape: Boolean
    ): Pair<Int, Int>? {
        return try {
            val vc = info.getCapabilitiesForType(mime).videoCapabilities
            val l = longSide and 1.inv()
            val s = (longSide * aspect).toInt() and 1.inv()
            var w = if (landscape) l else s
            var h = if (landscape) s else l
            if (!vc.isSizeSupported(w, h)) {
                w = w / vc.widthAlignment * vc.widthAlignment
                h = h / vc.heightAlignment * vc.heightAlignment
            }
            if (vc.areSizeAndRateSupported(w, h, fps.toDouble())) Pair(w, h) else null
        } catch (e: Exception) {
            null
        }
    }
}
