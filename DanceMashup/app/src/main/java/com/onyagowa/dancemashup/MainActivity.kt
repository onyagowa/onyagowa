package com.onyagowa.dancemashup

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.io.File
import java.util.concurrent.Executors
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

    private val clips = mutableListOf<Uri>()
    private val clipDurations = mutableListOf<Long>()
    private var song: Uri? = null
    private var songDurationMs = 0L
    private var lastOutput: File? = null

    private lateinit var renderer: MashupRenderer
    private var player: ExoPlayer? = null
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private lateinit var clipsText: TextView
    private lateinit var songText: TextView
    private lateinit var lengthGroup: RadioGroup
    private lateinit var chaosBar: SeekBar
    private lateinit var bpmInput: EditText
    private lateinit var makeButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var resultPanel: View
    private lateinit var playerView: PlayerView

    private val pickVideos = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_CLIPS)
    ) { uris -> addMedia(uris) }

    private val pickSong = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) addMedia(listOf(uri), forceAudio = true) }

    private val progressTicker = object : Runnable {
        override fun run() {
            val p = renderer.progress()
            if (p >= 0) {
                progressBar.isIndeterminate = false
                progressBar.progress = p
                statusText.text = getString(R.string.status_rendering_pct, p)
            }
            main.postDelayed(this, 300)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        renderer = MashupRenderer(this)

        clipsText = findViewById(R.id.clipsText)
        songText = findViewById(R.id.songText)
        lengthGroup = findViewById(R.id.lengthGroup)
        chaosBar = findViewById(R.id.chaosBar)
        bpmInput = findViewById(R.id.bpmInput)
        makeButton = findViewById(R.id.makeButton)
        progressBar = findViewById(R.id.progressBar)
        statusText = findViewById(R.id.statusText)
        resultPanel = findViewById(R.id.resultPanel)
        playerView = findViewById(R.id.playerView)

        findViewById<Button>(R.id.addVideosButton).setOnClickListener {
            pickVideos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
        }
        findViewById<Button>(R.id.clearVideosButton).setOnClickListener {
            clips.clear(); clipDurations.clear(); refreshUi()
        }
        findViewById<Button>(R.id.pickSongButton).setOnClickListener { pickSong.launch(arrayOf("audio/*")) }
        makeButton.setOnClickListener { makeMashup() }
        findViewById<Button>(R.id.remixButton).setOnClickListener { makeMashup() }
        findViewById<Button>(R.id.shareButton).setOnClickListener { share() }
        findViewById<Button>(R.id.saveButton).setOnClickListener { save() }

        handleShareIntent(intent)
        refreshUi()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        main.removeCallbacks(progressTicker)
        renderer.cancel()
        player?.release()
        io.shutdown()
    }

    /** Videos and songs "dropped" on the app via the Android share sheet. */
    private fun handleShareIntent(intent: Intent?) {
        intent ?: return
        val uris = when (intent.action) {
            Intent.ACTION_SEND ->
                listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> return
        }
        addMedia(uris)
    }

    private fun addMedia(uris: List<Uri>, forceAudio: Boolean = false) {
        if (uris.isEmpty()) return
        statusText.text = getString(R.string.status_reading)
        io.execute {
            val videos = mutableListOf<Pair<Uri, Long>>()
            var newSong: Pair<Uri, Long>? = null
            var skipped = 0
            for (uri in uris) {
                val type = contentResolver.getType(uri).orEmpty()
                val duration = MediaUtils.durationMs(this, uri)
                when {
                    forceAudio || type.startsWith("audio/") -> newSong = uri to duration
                    duration >= MashupPlanner.MIN_CUT_MS -> videos += uri to duration
                    else -> skipped++
                }
            }
            val songName = newSong?.let { MediaUtils.displayName(this, it.first) }
            main.post {
                if (isDestroyed) return@post
                for ((u, d) in videos) {
                    if (clips.size < MAX_CLIPS && u !in clips) { clips += u; clipDurations += d }
                }
                newSong?.let { (u, d) -> song = u; songDurationMs = d; songText.text = songName }
                statusText.text = if (skipped > 0) getString(R.string.status_skipped, skipped) else ""
                refreshUi()
            }
        }
    }

    private fun refreshUi() {
        clipsText.text = if (clips.isEmpty()) {
            getString(R.string.no_videos)
        } else {
            resources.getQuantityString(R.plurals.videos_added, clips.size, clips.size)
        }
        if (song == null) songText.text = getString(R.string.no_song)
        makeButton.isEnabled = clips.isNotEmpty() && song != null
    }

    private fun targetLengthMs(): Long {
        val fixed = when (lengthGroup.checkedRadioButtonId) {
            R.id.len15 -> 15_000L
            R.id.len30 -> 30_000L
            R.id.len60 -> 60_000L
            else -> 0L
        }
        if (fixed > 0) return fixed
        // "Whole song" — capped at 3 minutes so renders stay quick.
        return songDurationMs.takeIf { it > 0 }?.coerceAtMost(180_000L) ?: 30_000L
    }

    private fun makeMashup() {
        val songUri = song ?: return
        if (clips.isEmpty()) return
        val bpm = bpmInput.text.toString().toIntOrNull() ?: 120
        val segments = MashupPlanner(Random(System.nanoTime()))
            .plan(clipDurations, targetLengthMs(), bpm, chaosBar.progress / 100f)

        player?.stop()
        resultPanel.visibility = View.GONE
        makeButton.isEnabled = false
        progressBar.visibility = View.VISIBLE
        progressBar.isIndeterminate = true
        statusText.text = getString(R.string.status_rendering)
        main.post(progressTicker)

        val output = File(cacheDir, "mashup.mp4")
        renderer.render(clips.toList(), segments, songUri, output, object : MashupRenderer.Listener {
            override fun onDone(output: File) {
                main.removeCallbacks(progressTicker)
                progressBar.visibility = View.GONE
                statusText.text = getString(R.string.status_done, segments.size)
                lastOutput = output
                refreshUi()
                showResult(output)
            }

            override fun onFailed(message: String) {
                main.removeCallbacks(progressTicker)
                progressBar.visibility = View.GONE
                statusText.text = getString(R.string.status_failed, message)
                refreshUi()
            }
        })
    }

    private fun showResult(file: File) {
        val p = player ?: ExoPlayer.Builder(this).build().also {
            it.repeatMode = Player.REPEAT_MODE_ALL
            playerView.player = it
            player = it
        }
        p.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
        p.prepare()
        p.play()
        resultPanel.visibility = View.VISIBLE
    }

    private fun share() {
        val file = lastOutput ?: return
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("video/mp4")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, getString(R.string.share)))
    }

    private fun save() {
        val file = lastOutput ?: return
        io.execute {
            val msg = try {
                getString(R.string.saved_to, MediaUtils.saveToGallery(this, file))
            } catch (e: Exception) {
                getString(R.string.save_failed, e.message)
            }
            main.post { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
        }
    }

    companion object {
        private const val MAX_CLIPS = 30
    }
}
