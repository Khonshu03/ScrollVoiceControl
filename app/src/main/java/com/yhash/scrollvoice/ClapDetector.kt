package com.yhash.scrollvoice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlin.math.sqrt

class ClapDetector(private val onClapBurst: (count: Int) -> Unit) {

    companion object {
        private const val TAG = "ClapDetector"
        private const val SAMPLE_RATE = 16000
        private const val CHUNK_SIZE = 512

        private const val SPIKE_MULTIPLIER = 3.0
        private const val MIN_ABSOLUTE_RMS = 250.0
        private const val REFRACTORY_MS = 180L
        private const val BURST_WINDOW_MS = 700L
    }

    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    @Volatile private var isRunning = false

    private val handler = Handler(Looper.getMainLooper())
    private var noiseFloor = 800.0
    private var lastClapTime = 0L
    private var burstFirstClapTime = 0L
    private var burstCount = 0
    private var burstFinalizeRunnable: Runnable? = null

    @SuppressLint("MissingPermission")
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

        val now = System.currentTimeMillis()
        val isSpike = rms > noiseFloor * SPIKE_MULTIPLIER && rms > MIN_ABSOLUTE_RMS
        val pastRefractory = (now - lastClapTime) > REFRACTORY_MS

        if (isSpike && pastRefractory) {
            lastClapTime = now
            registerClap(now)
        } else if (!isSpike) {
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
            
