package com.yhash.scrollvoice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlin.math.sqrt

/**
 * Listens to raw mic input and detects claps by looking for sharp amplitude
 * spikes (fast rise, short duration) against a rolling noise floor. This is
 * more robust than speech recognition when a video is playing, because a
 * clap's signature (sudden spike then silence) stands out from continuous
 * media audio, whereas spoken words get buried in it.
 *
 * Groups claps that happen close together in time (a "burst") and reports
 * the total count once the burst ends: 1 = down, 2 = up, 3 = back.
 */
class ClapDetector(
    private val onClapBurst: (count: Int) -> Unit,
    private val onError: (() -> Unit)? = null
) {

    companion object {
        private const val TAG = "ClapDetector"
        private const val SAMPLE_RATE = 16000
        private const val CHUNK_SIZE = 512 // ~32ms per chunk at 16kHz

        // How many times louder than the noise floor a chunk must be to count as a clap.
        private const val SPIKE_MULTIPLIER = 3.0

        // Absolute floor so near-silence doesn't get treated as "loud relative to nothing".
        private const val MIN_ABSOLUTE_RMS = 250.0

        // Once a clap is detected, ignore new spikes for this long (its own decay tail).
        private const val REFRACTORY_MS = 180L

        // Window after the first clap in a burst during which more claps still count
        // toward the same burst.
        private const val BURST_WINDOW_MS = 700L

        // First ~0.5s of chunks are used purely to measure actual ambient
        // noise instead of assuming a fixed starting level - real rooms and
        // phone mics vary a lot, and this makes both quiet and noisy
        // environments detect claps far more reliably.
        private const val CALIBRATION_CHUNKS = 15
    }

    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    @Volatile private var isRunning = false

    private val handler = Handler(Looper.getMainLooper())
    private var noiseFloor = 800.0 // adaptive baseline, refined during calibration below
    private var lastClapTime = 0L
    private var burstFirstClapTime = 0L
    private var burstCount = 0
    private var burstFinalizeRunnable: Runnable? = null
    private var chunksProcessed = 0
    private val calibrationSamples = mutableListOf<Double>()

    @SuppressLint("MissingPermission") // RECORD_AUDIO checked by caller before starting
    fun start() {
        if (isRunning) return
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = maxOf(minBuffer, CHUNK_SIZE * 4)

        audioRecord = createRecordPreferringUnprocessed(bufferSize)

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize")
            onError?.invoke()
            return
        }

        isRunning = true
        audioRecord?.startRecording()

        recordingThread = Thread {
            val buffer = ShortArray(CHUNK_SIZE)
            while (isRunning) {
                val read = audioRecord?.read(buffer, 0, CHUNK_SIZE) ?: -1
                if (read > 0) {
                    processChunk(buffer, read)
                }
            }
        }.apply { start() }
    }

    /**
     * UNPROCESSED gives raw mic samples without noise suppression/AGC, which
     * otherwise smooths out the sharp spike a clap produces. Not every device
     * supports it, so we try it first and fall back to VOICE_RECOGNITION if
     * construction fails or doesn't initialize properly.
     */
    private fun createRecordPreferringUnprocessed(bufferSize: Int): AudioRecord? {
        if (android.os.Build.VERSION.SDK_INT >= 24) {
            try {
                val record = AudioRecord(
                    MediaRecorder.AudioSource.UNPROCESSED,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )
                if (record.state == AudioRecord.STATE_INITIALIZED) {
                    return record
                }
                record.release()
            } catch (e: Exception) {
                Log.d(TAG, "UNPROCESSED source unavailable, falling back: ${e.message}")
            }
        }
        return AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )
    }

    fun stop() {
        isRunning = false
        recordingThread?.join(500)
        recordingThread = null
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        burstFinalizeRunnable?.let { handler.removeCallbacks(it) }
    }

    private fun processChunk(buffer: ShortArray, length: Int) {
        var sumSquares = 0.0
        for (i in 0 until length) {
            val sample = buffer[i].toDouble()
            sumSquares += sample * sample
        }
        val rms = sqrt(sumSquares / length)

        if (chunksProcessed < CALIBRATION_CHUNKS) {
            chunksProcessed++
            calibrationSamples.add(rms)
            if (chunksProcessed == CALIBRATION_CHUNKS) {
                val avg = calibrationSamples.average()
                noiseFloor = avg.coerceAtLeast(150.0) // never calibrate to near-zero
                Log.d(TAG, "Calibrated noise floor: $noiseFloor")
            }
            return
        }

        val now = System.currentTimeMillis()
        val isSpike = rms > noiseFloor * SPIKE_MULTIPLIER && rms > MIN_ABSOLUTE_RMS
        val pastRefractory = (now - lastClapTime) > REFRACTORY_MS

        if (isSpike && pastRefractory) {
            lastClapTime = now
            registerClap(now)
        } else if (!isSpike) {
            // Only adapt the noise floor during quiet chunks, so loud claps
            // themselves don't drag the baseline up.
            noiseFloor = noiseFloor * 0.98 + rms * 0.02
        }
    }

    private fun registerClap(now: Long) {
        handler.post {
            if (burstCount == 0) {
                burstFirstClapTime = now
            }
            burstCount++

            burstFinalizeRunnable?.let { handler.removeCallbacks(it) }
            val runnable = Runnable { finalizeBurst() }
            burstFinalizeRunnable = runnable
            handler.postDelayed(runnable, BURST_WINDOW_MS)
        }
    }

    private fun finalizeBurst() {
        if (burstCount > 0) {
            Log.d(TAG, "Clap burst finalized: count=$burstCount")
            onClapBurst(burstCount)
        }
        burstCount = 0
    }
}
