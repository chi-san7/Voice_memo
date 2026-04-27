package com.example.voice_memo

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

class MainActivity : AppCompatActivity() {

    private val sampleRate = 16_000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioEncoding = AudioFormat.ENCODING_PCM_16BIT

    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var statusIndicatorView: View
    private lateinit var transcriptTextView: TextView
    private lateinit var latestSaveTextView: TextView

    private var speechRecognizer: SpeechRecognizer? = null
    private var audioRecord: AudioRecord? = null
    private var audioThread: Thread? = null
    private var audioPipeWriteSide: ParcelFileDescriptor? = null

    private var rawAudioFile: File? = null
    private var wavAudioFile: File? = null
    private var currentTranscript = ""
    private var lastSavedUri: Uri? = null
    private var hasAutoSavedCurrentSession = false

    private var isRecording = false
    private var isTranscriptionPending = false
    private var shouldAutoStartAfterPermission = false

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
        transcriptTextView = findViewById(R.id.transcriptTextView)
        latestSaveTextView = findViewById(R.id.latestSaveTextView)

        startButton.setOnClickListener { startRecordingFlow() }
        stopButton.setOnClickListener { stopRecordingFlow() }

        transcriptTextView.text = getString(R.string.hint_transcript)
        latestSaveTextView.text = getString(R.string.label_latest_save_empty)
        updateButtons()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAudioCapture()
        destroySpeechRecognizer()
    }

    private fun startRecordingFlow() {
        if (isRecording || isTranscriptionPending) {
            return
        }

        if (!hasRequiredPermissions()) {
            shouldAutoStartAfterPermission = true
            permissionLauncher.launch(requiredPermissions())
            return
        }

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            latestSaveTextView.text = getString(R.string.message_speech_not_available)
            return
        }

        currentTranscript = ""
        lastSavedUri = null
        hasAutoSavedCurrentSession = false
        transcriptTextView.text = getString(R.string.hint_transcript)
        latestSaveTextView.text = getString(R.string.message_recording_in_progress)
        isTranscriptionPending = true
        updateButtons()

        val sessionStamp = createTimestampForFile()
        val sessionDir = File(getExternalFilesDir(Environment.DIRECTORY_MUSIC), "voice_sessions")
        if (!sessionDir.exists()) {
            sessionDir.mkdirs()
        }

        rawAudioFile = File(sessionDir, "voice_$sessionStamp.pcm")
        wavAudioFile = File(sessionDir, "voice_$sessionStamp.wav")

        val pipe = runCatching { ParcelFileDescriptor.createPipe() }.getOrElse { exception ->
            isTranscriptionPending = false
            latestSaveTextView.text = getString(
                R.string.message_audio_session_failed,
                exception.message ?: getString(R.string.message_unknown_error)
            )
            updateButtons()
            return
        }

        val readSide = pipe[0]
        audioPipeWriteSide = pipe[1]

        val recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.JAPAN.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, readSide)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, audioEncoding)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, sampleRate)
        }

        destroySpeechRecognizer()
        speechRecognizer = createCompatibleSpeechRecognizer().also { recognizer ->
            recognizer.setRecognitionListener(buildRecognitionListener())
            recognizer.startListening(recognizerIntent)
        }
        readSide.close()

        startAudioCapture()
    }

    private fun stopRecordingFlow() {
        if (!isRecording) {
            return
        }

        latestSaveTextView.text = getString(R.string.message_processing_transcript)
        stopAudioCapture()
        updateButtons()

        val rawFile = rawAudioFile
        val wavFile = wavAudioFile
        if (rawFile != null && wavFile != null) {
            Thread {
                runCatching { convertPcmToWav(rawFile, wavFile) }
            }.start()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAudioCapture() {
        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioEncoding)
        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            isTranscriptionPending = false
            latestSaveTextView.text = getString(R.string.message_audio_buffer_failed)
            updateButtons()
            return
        }

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            audioEncoding,
            max(minBufferSize * 2, sampleRate)
        )

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            isTranscriptionPending = false
            latestSaveTextView.text = getString(R.string.message_mic_init_failed)
            updateButtons()
            return
        }

        val rawFile = rawAudioFile
        val pipeWriteSide = audioPipeWriteSide
        if (rawFile == null || pipeWriteSide == null) {
            recorder.release()
            isTranscriptionPending = false
            latestSaveTextView.text = getString(R.string.message_session_init_failed)
            updateButtons()
            return
        }

        audioRecord = recorder
        isRecording = true
        updateButtons()

        audioThread = Thread {
            val buffer = ByteArray(max(minBufferSize, 4096))

            runCatching {
                FileOutputStream(rawFile).use { fileStream ->
                    ParcelFileDescriptor.AutoCloseOutputStream(pipeWriteSide).use { pipeStream ->
                        recorder.startRecording()
                        while (isRecording) {
                            val readSize = recorder.read(buffer, 0, buffer.size)
                            if (readSize > 0) {
                                fileStream.write(buffer, 0, readSize)
                                pipeStream.write(buffer, 0, readSize)
                            }
                        }
                        pipeStream.flush()
                    }
                }
            }.onFailure { exception ->
                runOnUiThread {
                    isTranscriptionPending = false
                    latestSaveTextView.text = getString(
                        R.string.message_recording_failed,
                        exception.message ?: getString(R.string.message_unknown_error)
                    )
                    updateButtons()
                }
            }
        }.apply {
            name = "voice-record-thread"
            start()
        }
    }

    private fun stopAudioCapture() {
        isRecording = false

        runCatching { audioRecord?.stop() }
        audioRecord?.release()
        audioRecord = null

        runCatching { audioThread?.join(1500) }
        audioThread = null
        audioPipeWriteSide = null
    }

    private fun autoSaveTranscript(transcript: String, fallbackBody: String? = null) {
        if (hasAutoSavedCurrentSession) {
            return
        }

        val textToSave = transcript.trim().ifBlank {
            fallbackBody ?: getString(R.string.text_no_transcript)
        }

        val saveResult = saveTranscriptToMyFiles(textToSave)
        hasAutoSavedCurrentSession = true

        saveResult.onSuccess { savedUri ->
            lastSavedUri = savedUri
            latestSaveTextView.text = getString(
                R.string.label_latest_save,
                extractDisplayName(savedUri)
            )
        }.onFailure { exception ->
            latestSaveTextView.text = getString(
                R.string.message_auto_save_failed,
                exception.message ?: getString(R.string.message_unknown_error)
            )
            Toast.makeText(this, R.string.message_auto_save_failed_short, Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveTranscriptToMyFiles(transcriptToSave: String): Result<Uri> {
        val fileName = "voice_memo_${createTimestampForFile()}.txt"
        val resolver = contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                "${Environment.DIRECTORY_DOCUMENTS}/VoiceMemo"
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val targetUri = resolver.insert(MediaStore.Files.getContentUri("external"), values)
            ?: return Result.failure(IllegalStateException(getString(R.string.message_save_file_failed)))

        val audioPathText = wavAudioFile?.absolutePath ?: getString(R.string.text_audio_path_unavailable)
        val writeResult = runCatching {
            resolver.openOutputStream(targetUri)?.bufferedWriter(Charsets.UTF_8).use { writer ->
                requireNotNull(writer)
                writer.appendLine(getString(R.string.file_header_title))
                writer.appendLine(getString(R.string.file_header_saved_at, createTimestampForDisplay()))
                writer.appendLine(getString(R.string.file_header_audio_path, audioPathText))
                writer.appendLine()
                writer.appendLine(transcriptToSave)
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

    private fun createCompatibleSpeechRecognizer(): SpeechRecognizer {
        return if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
        ) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
        } else {
            SpeechRecognizer.createSpeechRecognizer(this)
        }
    }

    private fun buildRecognitionListener(): RecognitionListener {
        return object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit

            override fun onBeginningOfSpeech() = Unit

            override fun onRmsChanged(rmsdB: Float) = Unit

            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() = Unit

            override fun onError(error: Int) {
                isTranscriptionPending = false
                val errorMessage = speechErrorToMessage(error)
                transcriptTextView.text = getString(R.string.hint_transcript_failed, errorMessage)
                autoSaveTranscript("", getString(R.string.file_body_transcript_failed, errorMessage))
                updateButtons()
            }

            override fun onResults(results: Bundle) {
                val transcript = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.joinToString(separator = "\n")
                    ?.trim()
                    .orEmpty()

                currentTranscript = transcript
                transcriptTextView.text =
                    if (transcript.isBlank()) {
                        getString(R.string.hint_transcript_empty)
                    } else {
                        transcript
                    }
                isTranscriptionPending = false

                autoSaveTranscript(transcript)
                updateButtons()
            }

            override fun onPartialResults(partialResults: Bundle) {
                val partialText = partialResults
                    .getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.joinToString(separator = "\n")
                    ?.trim()
                    .orEmpty()

                if (partialText.isNotBlank()) {
                    transcriptTextView.text = partialText
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        }
    }

    private fun destroySpeechRecognizer() {
        speechRecognizer?.cancel()
        speechRecognizer?.destroy()
        speechRecognizer = null
    }

    private fun hasRequiredPermissions(): Boolean {
        return requiredPermissions().all { permission ->
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requiredPermissions(): Array<String> {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
            permissions += Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            permissions += Manifest.permission.WRITE_EXTERNAL_STORAGE
        }

        return permissions.toTypedArray()
    }

    private fun convertPcmToWav(rawFile: File, wavFile: File) {
        val totalAudioLength = rawFile.length()
        val totalDataLength = totalAudioLength + 36
        val channels = 1
        val byteRate = sampleRate * channels * 16 / 8

        FileInputStream(rawFile).use { inputStream ->
            FileOutputStream(wavFile).use { outputStream ->
                writeWavHeader(
                    outputStream = outputStream,
                    totalAudioLength = totalAudioLength,
                    totalDataLength = totalDataLength,
                    sampleRate = sampleRate,
                    channels = channels,
                    byteRate = byteRate
                )

                val buffer = ByteArray(4096)
                while (true) {
                    val count = inputStream.read(buffer)
                    if (count <= 0) {
                        break
                    }
                    outputStream.write(buffer, 0, count)
                }
            }
        }
    }

    private fun writeWavHeader(
        outputStream: FileOutputStream,
        totalAudioLength: Long,
        totalDataLength: Long,
        sampleRate: Int,
        channels: Int,
        byteRate: Int
    ) {
        val header = ByteArray(44)

        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        header[4] = (totalDataLength and 0xff).toByte()
        header[5] = (totalDataLength shr 8 and 0xff).toByte()
        header[6] = (totalDataLength shr 16 and 0xff).toByte()
        header[7] = (totalDataLength shr 24 and 0xff).toByte()
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        header[16] = 16
        header[20] = 1
        header[22] = channels.toByte()
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = (sampleRate shr 8 and 0xff).toByte()
        header[26] = (sampleRate shr 16 and 0xff).toByte()
        header[27] = (sampleRate shr 24 and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = (byteRate shr 8 and 0xff).toByte()
        header[30] = (byteRate shr 16 and 0xff).toByte()
        header[31] = (byteRate shr 24 and 0xff).toByte()
        header[32] = (channels * 16 / 8).toByte()
        header[34] = 16
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        header[40] = (totalAudioLength and 0xff).toByte()
        header[41] = (totalAudioLength shr 8 and 0xff).toByte()
        header[42] = (totalAudioLength shr 16 and 0xff).toByte()
        header[43] = (totalAudioLength shr 24 and 0xff).toByte()

        outputStream.write(header, 0, header.size)
    }

    private fun speechErrorToMessage(error: Int): String {
        return when (error) {
            SpeechRecognizer.ERROR_AUDIO -> getString(R.string.error_audio)
            SpeechRecognizer.ERROR_CLIENT -> getString(R.string.error_client)
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> getString(R.string.error_permissions)
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> getString(R.string.error_network)
            SpeechRecognizer.ERROR_NO_MATCH -> getString(R.string.error_no_match)
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> getString(R.string.error_busy)
            SpeechRecognizer.ERROR_SERVER -> getString(R.string.error_server)
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> getString(R.string.error_timeout)
            else -> getString(R.string.message_unknown_error)
        }
    }

    private fun updateButtons() {
        startButton.isEnabled = !isRecording && !isTranscriptionPending
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

    private fun createTimestampForFile(): String {
        return SimpleDateFormat("yyyyMMdd_HHmmss", Locale.JAPAN).format(Date())
    }

    private fun createTimestampForDisplay(): String {
        return SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.JAPAN).format(Date())
    }
}
