package com.example.voice_memo

import android.Manifest
import android.content.ContentValues
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var statusIndicatorView: View
    private lateinit var recordingTimeTextView: TextView
    private lateinit var transcriptTextView: TextView
    private lateinit var latestSaveTextView: TextView

    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null

    private var lastSavedUri: Uri? = null
    private var activeRecordingFile: File? = null
    private var hasReleasedWearHFMic = false
    private var isRecording = false
    private var shouldAutoStartAfterPermission = false
    private var recordingStartedAtMs = 0L

    @Volatile
    private var audioCaptureError: Throwable? = null

    private val recordingTimerUpdater = object : Runnable {
        override fun run() {
            if (!isRecording) {
                return
            }
            val elapsedMs = SystemClock.elapsedRealtime() - recordingStartedAtMs
            recordingTimeTextView.text = formatElapsedTime(elapsedMs)
            recordingTimeTextView.postDelayed(this, TIMER_UPDATE_INTERVAL_MS)
        }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val allGranted = result.values.all { it }
            if (allGranted && shouldAutoStartAfterPermission) {
                shouldAutoStartAfterPermission = false
                startRecordingFlow()
            } else if (!allGranted) {
                shouldAutoStartAfterPermission = false
                latestSaveTextView.text = getString(R.string.message_permission_denied)
                updateButtons()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        statusIndicatorView = findViewById(R.id.statusIndicatorView)
        recordingTimeTextView = findViewById(R.id.recordingTimeTextView)
        transcriptTextView = findViewById(R.id.transcriptTextView)
        latestSaveTextView = findViewById(R.id.latestSaveTextView)

        startButton.setOnClickListener { startRecordingFlow() }
        stopButton.setOnClickListener { stopRecordingFlow() }

        transcriptTextView.text = getString(R.string.hint_audio)
        latestSaveTextView.text = getString(R.string.label_latest_save_empty)
        recordingTimeTextView.text = getString(R.string.recording_time_initial)
        updateButtons()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRecordingTimer(resetDisplay = true)
        cleanupAudioCapture(deleteTempFile = true)
        releaseWearHFMicrophone()
    }

    private fun startRecordingFlow() {
        if (isRecording) {
            return
        }

        if (!hasRequiredPermissions()) {
            shouldAutoStartAfterPermission = true
            permissionLauncher.launch(requiredPermissions())
            return
        }

        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (bufferSize <= 0) {
            latestSaveTextView.text = getString(R.string.message_audio_buffer_failed)
            return
        }

        val recordingFile = runCatching {
            File.createTempFile("voice_memo_", ".wav", cacheDir)
        }.getOrElse { exception ->
            latestSaveTextView.text = getString(
                R.string.message_recording_failed,
                exception.message ?: getString(R.string.message_unknown_error)
            )
            return
        }

        val recorder = runCatching {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE_HZ)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize.coerceAtLeast(DEFAULT_BUFFER_SIZE_BYTES))
                .build()
        }.getOrElse { exception ->
            recordingFile.delete()
            latestSaveTextView.text = exception.message?.let {
                getString(R.string.message_recording_failed, it)
            } ?: getString(R.string.message_mic_init_failed)
            return
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            recordingFile.delete()
            latestSaveTextView.text = getString(R.string.message_mic_init_failed)
            return
        }

        resetSessionStateForNewCapture()
        audioCaptureError = null
        activeRecordingFile = recordingFile
        audioRecord = recorder
        isRecording = true
        recordingStartedAtMs = SystemClock.elapsedRealtime()
        latestSaveTextView.text = getString(R.string.message_recording_in_progress)
        transcriptTextView.text = getString(R.string.hint_audio_recording)
        startRecordingTimer()
        requestWearHFMicrophoneRelease()
        startAudioCapture(recorder, recordingFile, bufferSize.coerceAtLeast(DEFAULT_BUFFER_SIZE_BYTES))
        updateButtons()
    }

    private fun stopRecordingFlow() {
        if (!isRecording) {
            return
        }

        isRecording = false
        stopRecordingTimer(resetDisplay = false)
        latestSaveTextView.text = getString(R.string.message_saving_audio)
        transcriptTextView.text = getString(R.string.hint_audio_saving)
        updateButtons()

        Thread {
            val recordedFile = runCatching { stopAudioCaptureAndReturnFile() }
            runOnUiThread {
                recordedFile.onSuccess { wavFile ->
                    if (wavFile == null) {
                        handleCaptureFinishedWithoutAudio()
                    } else {
                        saveRecording(wavFile)
                    }
                }.onFailure { exception ->
                    handleAudioCaptureFailure(exception)
                }
            }
        }.start()
    }

    private fun startAudioCapture(recorder: AudioRecord, outputFile: File, bufferSize: Int) {
        recordingThread = Thread {
            val buffer = ByteArray(bufferSize)
            try {
                var totalAudioLength = 0L
                FileOutputStream(outputFile).use { stream ->
                    writeWavHeader(stream, 0)
                    recorder.startRecording()
                    while (isRecording) {
                        val readCount = recorder.read(
                            buffer,
                            0,
                            buffer.size,
                            AudioRecord.READ_BLOCKING
                        )
                        when {
                            readCount > 0 -> {
                                stream.write(buffer, 0, readCount)
                                totalAudioLength += readCount
                            }
                            readCount == 0 -> Unit
                            else -> throw IllegalStateException("AudioRecord read failed: $readCount")
                        }
                    }
                    stream.flush()
                }
                updateWavHeader(outputFile, totalAudioLength)
            } catch (throwable: Throwable) {
                audioCaptureError = throwable
            }
        }.apply { start() }
    }

    private fun stopAudioCaptureAndReturnFile(): File? {
        val recorder = audioRecord
        try {
            recorder?.stop()
        } catch (_: IllegalStateException) {
        }

        try {
            recordingThread?.join(RECORDING_JOIN_TIMEOUT_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }

        cleanupAudioCapture(deleteTempFile = false)
        releaseWearHFMicrophone()

        audioCaptureError?.let { throw it }

        val recordingFile = activeRecordingFile
        return if (recordingFile != null && recordingFile.exists() && recordingFile.length() > WAV_HEADER_SIZE_BYTES.toLong()) {
            recordingFile
        } else {
            null
        }
    }

    private fun saveRecording(recordingFile: File) {
        val saveResult = saveRecordingToMediaStore(recordingFile)
        deleteTemporaryRecording()

        saveResult.onSuccess { savedUri ->
            lastSavedUri = savedUri
            latestSaveTextView.text = getString(
                R.string.label_latest_save,
                extractDisplayName(savedUri)
            )
            transcriptTextView.text = getString(
                R.string.hint_audio_saved,
                createTimestampForDisplay()
            )
        }.onFailure { exception ->
            latestSaveTextView.text = getString(
                R.string.message_auto_save_failed,
                exception.message ?: getString(R.string.message_unknown_error)
            )
            transcriptTextView.text = getString(
                R.string.hint_audio_failed,
                exception.message ?: getString(R.string.message_unknown_error)
            )
            Toast.makeText(this, R.string.message_auto_save_failed_short, Toast.LENGTH_SHORT).show()
        }

        updateButtons()
    }

    private fun saveRecordingToMediaStore(recordingFile: File): Result<Uri> {
        val fileName = "voice_memo_${createTimestampForFile()}.wav"
        val resolver = contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "audio/wav")
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                "${Environment.DIRECTORY_MUSIC}/VoiceMemo"
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val targetUri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            ?: return Result.failure(IllegalStateException(getString(R.string.message_save_file_failed)))

        val writeResult = runCatching {
            resolver.openOutputStream(targetUri).use { output ->
                requireNotNull(output)
                recordingFile.inputStream().use { input ->
                    input.copyTo(output)
                }
            }
        }

        if (writeResult.isFailure) {
            resolver.delete(targetUri, null, null)
            return Result.failure(writeResult.exceptionOrNull()!!)
        }

        val completeValues = ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        }
        resolver.update(targetUri, completeValues, null, null)
        return Result.success(targetUri)
    }

    private fun extractDisplayName(uri: Uri): String {
        return contentResolver.query(
            uri,
            arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            val index = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) {
                cursor.getString(index)
            } else {
                uri.lastPathSegment ?: getString(R.string.label_latest_save_unknown)
            }
        } ?: (uri.lastPathSegment ?: getString(R.string.label_latest_save_unknown))
    }

    private fun handleCaptureFinishedWithoutAudio() {
        latestSaveTextView.text = getString(R.string.message_recording_empty)
        transcriptTextView.text = getString(R.string.hint_audio_failed, getString(R.string.message_recording_empty))
        deleteTemporaryRecording()
        updateButtons()
    }

    private fun handleAudioCaptureFailure(exception: Throwable) {
        latestSaveTextView.text = getString(
            R.string.message_recording_failed,
            exception.message ?: getString(R.string.message_unknown_error)
        )
        transcriptTextView.text = getString(
            R.string.hint_audio_failed,
            exception.message ?: getString(R.string.message_unknown_error)
        )
        deleteTemporaryRecording()
        updateButtons()
    }

    private fun cleanupAudioCapture(deleteTempFile: Boolean) {
        try {
            audioRecord?.release()
        } catch (_: Exception) {
        }
        audioRecord = null
        recordingThread = null
        if (deleteTempFile) {
            deleteTemporaryRecording()
        }
    }

    private fun deleteTemporaryRecording() {
        activeRecordingFile?.delete()
        activeRecordingFile = null
        audioCaptureError = null
    }

    private fun requestWearHFMicrophoneRelease() {
        if (hasReleasedWearHFMic) {
            return
        }

        sendBroadcast(android.content.Intent(ACTION_RELEASE_MIC).apply {
            putExtra(EXTRA_REALWEAR_SOURCE_PACKAGE, packageName)
            putExtra(EXTRA_REALWEAR_HIDE_TEXT, true)
        })
        hasReleasedWearHFMic = true
    }

    private fun releaseWearHFMicrophone() {
        if (!hasReleasedWearHFMic) {
            return
        }

        sendBroadcast(android.content.Intent(ACTION_MIC_RELEASED).apply {
            putExtra(EXTRA_REALWEAR_SOURCE_PACKAGE, packageName)
        })
        hasReleasedWearHFMic = false
    }

    private fun resetSessionStateForNewCapture() {
        lastSavedUri = null
        transcriptTextView.text = getString(R.string.hint_audio)
        recordingTimeTextView.text = getString(R.string.recording_time_initial)
        updateButtons()
    }

    private fun hasRequiredPermissions(): Boolean {
        return requiredPermissions().all { permission ->
            ContextCompat.checkSelfPermission(this, permission) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            arrayOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
        } else {
            arrayOf(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun updateButtons() {
        startButton.isEnabled = !isRecording
        stopButton.isEnabled = isRecording

        val indicatorBackground = if (isRecording) {
            R.drawable.status_indicator_recording
        } else {
            R.drawable.status_indicator_idle
        }
        statusIndicatorView.setBackgroundResource(indicatorBackground)
        statusIndicatorView.contentDescription = getString(
            if (isRecording) R.string.state_recording else R.string.state_idle
        )
    }

    private fun startRecordingTimer() {
        recordingTimeTextView.removeCallbacks(recordingTimerUpdater)
        recordingTimeTextView.text = formatElapsedTime(0)
        recordingTimeTextView.post(recordingTimerUpdater)
    }

    private fun stopRecordingTimer(resetDisplay: Boolean) {
        recordingTimeTextView.removeCallbacks(recordingTimerUpdater)
        if (resetDisplay) {
            recordingTimeTextView.text = getString(R.string.recording_time_initial)
        }
    }

    private fun createTimestampForFile(): String {
        return SimpleDateFormat("yyyyMMdd_HHmmss", Locale.JAPAN).format(Date())
    }

    private fun createTimestampForDisplay(): String {
        return SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.JAPAN).format(Date())
    }

    private fun formatElapsedTime(elapsedMs: Long): String {
        val totalSeconds = elapsedMs / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }

    private fun writeWavHeader(stream: FileOutputStream, audioDataLength: Long) {
        val byteRate = SAMPLE_RATE_HZ * CHANNEL_COUNT * BITS_PER_SAMPLE / 8
        val totalDataLength = audioDataLength + 36
        val header = ByteArray(WAV_HEADER_SIZE_BYTES)

        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        writeIntLE(header, 4, totalDataLength.toInt())
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        writeIntLE(header, 16, 16)
        writeShortLE(header, 20, 1.toShort())
        writeShortLE(header, 22, CHANNEL_COUNT.toShort())
        writeIntLE(header, 24, SAMPLE_RATE_HZ)
        writeIntLE(header, 28, byteRate)
        writeShortLE(header, 32, (CHANNEL_COUNT * BITS_PER_SAMPLE / 8).toShort())
        writeShortLE(header, 34, BITS_PER_SAMPLE.toShort())
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        writeIntLE(header, 40, audioDataLength.toInt())

        stream.write(header, 0, header.size)
    }

    private fun updateWavHeader(outputFile: File, audioDataLength: Long) {
        RandomAccessFile(outputFile, "rw").use { file ->
            val header = ByteArray(WAV_HEADER_SIZE_BYTES)
            writeIntLE(header, 4, (audioDataLength + 36).toInt())
            writeIntLE(header, 40, audioDataLength.toInt())
            file.seek(4)
            file.write(header, 4, 4)
            file.seek(40)
            file.write(header, 40, 4)
        }
    }

    private fun writeIntLE(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value and 0xff).toByte()
        target[offset + 1] = (value shr 8 and 0xff).toByte()
        target[offset + 2] = (value shr 16 and 0xff).toByte()
        target[offset + 3] = (value shr 24 and 0xff).toByte()
    }

    private fun writeShortLE(target: ByteArray, offset: Int, value: Short) {
        val intValue = value.toInt()
        target[offset] = (intValue and 0xff).toByte()
        target[offset + 1] = (intValue shr 8 and 0xff).toByte()
    }

    companion object {
        private const val SAMPLE_RATE_HZ = 16000
        private const val CHANNEL_COUNT = 1
        private const val BITS_PER_SAMPLE = 16
        private const val DEFAULT_BUFFER_SIZE_BYTES = 32_000
        private const val RECORDING_JOIN_TIMEOUT_MS = 2_000L
        private const val TIMER_UPDATE_INTERVAL_MS = 1_000L
        private const val WAV_HEADER_SIZE_BYTES = 44

        private const val ACTION_RELEASE_MIC =
            "com.realwear.wearhf.intent.action.RELEASE_MIC"
        private const val ACTION_MIC_RELEASED =
            "com.realwear.wearhf.intent.action.MIC_RELEASED"
        private const val EXTRA_REALWEAR_SOURCE_PACKAGE =
            "com.realwear.wearhf.intent.extra.SOURCE_PACKAGE"
        private const val EXTRA_REALWEAR_HIDE_TEXT =
            "com.realwear.wearhf.intent.extra.HIDE_TEXT"
    }
}
