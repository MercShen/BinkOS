package com.github.gezimos.inkos.helper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.net.Uri

/**
 * 封面图解码器。
 *
 * MediaMetadata.getBitmap() 会把源图整张解码进内存——某些播放器给出 3000~4000 px 的封面，
 * ARGB_8888 下就是 36~64 MB，长期挂在首页媒体组件上。这里统一限制到 [maxPx]（默认 144 px，
 * 足够 36dp 的组件在高倍屏上清晰），优先按 URI 用 inSampleSize 解码，拿不到 URI 才退回
 * getBitmap + 缩放，并回收原图。
 */
object MediaArtwork {

    /** 组件里封面显示尺寸约 36dp，144 px 已覆盖 4x 密度。 */
    const val DEFAULT_MAX_PX = 144

    fun load(context: Context, metadata: MediaMetadata?, maxPx: Int = DEFAULT_MAX_PX): Bitmap? {
        metadata ?: return null

        val uriText = runCatching {
            metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
                ?: metadata.getString(MediaMetadata.METADATA_KEY_ART_URI)
        }.getOrNull()
        if (!uriText.isNullOrBlank()) {
            decodeUri(context, uriText, maxPx)?.let { return it }
        }

        val raw = runCatching {
            metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        }.getOrNull() ?: return null
        return downscale(raw, maxPx)
    }

    private fun decodeUri(context: Context, uriText: String, maxPx: Int): Bitmap? {
        return try {
            val uri = Uri.parse(uriText)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var sample = 1
            while (bounds.outWidth / (sample * 2) >= maxPx && bounds.outHeight / (sample * 2) >= maxPx) {
                sample *= 2
            }
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun downscale(bitmap: Bitmap, maxPx: Int): Bitmap {
        val side = maxOf(bitmap.width, bitmap.height)
        if (side <= maxPx) return bitmap
        val scale = maxPx.toFloat() / side
        val w = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val h = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return try {
            val scaled = Bitmap.createScaledBitmap(bitmap, w, h, true)
            if (scaled !== bitmap) bitmap.recycle()
            scaled
        } catch (_: Throwable) {
            bitmap
        }
    }
}
