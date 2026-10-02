package com.onyagowa.dancemashup

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Crop
import androidx.media3.effect.Presentation
import androidx.media3.effect.RgbFilter
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import java.io.File

/** Renders a planned mashup to an MP4 with the song as the only soundtrack. Call from the main thread. */
@OptIn(UnstableApi::class)
class MashupRenderer(private val context: Context) {

    interface Listener {
        fun onDone(output: File)
        fun onFailed(message: String)
    }

    private var transformer: Transformer? = null
    private val progressHolder = ProgressHolder()

    fun render(clips: List<Uri>, segments: List<Segment>, song: Uri, output: File, listener: Listener) {
        output.delete()

        val videoItems = segments.map { seg ->
            val item = MediaItem.Builder()
                .setUri(clips[seg.clipIndex])
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(seg.startMs)
                        .setEndPositionMs(seg.startMs + seg.durationMs)
                        .build()
                )
                .build()
            EditedMediaItem.Builder(item)
                .setRemoveAudio(true) // drop the original clip sound
                .setEffects(Effects(emptyList(), videoEffects(seg.effect)))
                .build()
        }
        val videoSequence = EditedMediaItemSequence(videoItems)

        // A looping audio-only sequence is trimmed to the video length automatically.
        val songItem = EditedMediaItem.Builder(MediaItem.fromUri(song)).build()
        val audioSequence = EditedMediaItemSequence(listOf(songItem), /* isLooping= */ true)

        val composition = Composition.Builder(videoSequence, audioSequence).build()

        val t = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    transformer = null
                    listener.onDone(output)
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException,
                ) {
                    transformer = null
                    listener.onFailed(exportException.message ?: exportException.errorCodeName)
                }
            })
            .build()
        transformer = t
        t.start(composition, output.absolutePath)
    }

    /** Export progress 0..100, or -1 when unknown. */
    fun progress(): Int {
        val t = transformer ?: return -1
        return if (t.getProgress(progressHolder) == Transformer.PROGRESS_STATE_AVAILABLE) {
            progressHolder.progress
        } else {
            -1
        }
    }

    fun cancel() {
        transformer?.cancel()
        transformer = null
    }

    private fun videoEffects(effect: FunnyEffect): List<Effect> {
        val list = mutableListOf<Effect>()
        when (effect) {
            FunnyEffect.NONE -> Unit
            FunnyEffect.MIRROR -> list += ScaleAndRotateTransformation.Builder().setScale(-1f, 1f).build()
            // Crop the centre; the Presentation below blows it back up = punch-in zoom.
            FunnyEffect.ZOOM -> list += Crop(-0.6f, 0.6f, -0.6f, 0.6f)
            FunnyEffect.UPSIDE_DOWN -> list += ScaleAndRotateTransformation.Builder().setRotationDegrees(180f).build()
            FunnyEffect.GRAYSCALE -> list += RgbFilter.createGrayscaleFilter()
        }
        // Every cut ends at the same portrait size so mixed-resolution clips line up.
        list += Presentation.createForWidthAndHeight(OUT_WIDTH, OUT_HEIGHT, Presentation.LAYOUT_SCALE_TO_FIT)
        return list
    }

    companion object {
        const val OUT_WIDTH = 720
        const val OUT_HEIGHT = 1280
    }
}
