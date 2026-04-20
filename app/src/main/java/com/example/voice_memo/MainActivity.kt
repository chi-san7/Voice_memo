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

    // 文字起こしと録音の両方で扱いやすい PCM 設定を利用する
    private val sampleRate = 16_000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioEncoding = AudioFormat.ENCODING_PCM_16BIT

    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var saveButton: Button
    private lateinit var statusTextView: TextView
    private lateinit var transcriptTextView: TextView

    private var speechRecognizer: SpeechRecognizer? = null
    private var audioRecord: AudioRecord? = null
    private var audioThread: Thread? = null
    private var audioPipeWriteSide: ParcelFileDescriptor? = null

    private var rawAudioFile: File? = null
    private var wavAudioFile: File? = null
    private var currentTranscript = ""
    private var lastSavedUri: Uri? = null

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
                showStatus("権限が許可されていないため録音を開始できません。")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        saveButton = findViewById(R.id.saveButton)
        statusTextView = findViewById(R.id.statusTextView)
        transcriptTextView = findViewById(R.id.transcriptTextView)

        startButton.setOnClickListener { startRecordingFlow() }
        stopButton.setOnClickListener { stopRecordingFlow() }
        saveButton.setOnClickListener { saveTranscriptToMyFiles() }

        showStatus(getString(R.string.status_waiting))
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
            showStatus("この端末では SpeechRecognizer が利用できません。")
            return
        }

        currentTranscript = ""
        lastSavedUri = null
        transcriptTextView.text = getString(R.string.hint_transcript)
        isTranscriptionPending = true
        showStatus(getString(R.string.status_recording))
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
            showStatus("音声認識の準備に失敗しました: ${exception.message}")
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

        showStatus(getString(R.string.status_transcribing))
        stopAudioCapture()
        updateButtons()

        val rawFile = rawAudioFile
        val wavFile = wavAudioFile
        if (rawFile != null && wavFile != null) {
            Thread {
                runCatching {
                    convertPcmToWav(rawFile, wavFile)
                }
            }.start()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAudioCapture() {
        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioEncoding)
        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            isTranscriptionPending = false
            showStatus("録音バッファの初期化に失敗しました。")
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
            showStatus("マイクの初期化に失敗しました。")
            updateButtons()
            return
        }

        val rawFile = rawAudioFile
        val pipeWriteSide = audioPipeWriteSide
        if (rawFile == null || pipeWriteSide == null) {
            recorder.release()
            isTranscriptionPending = false
            showStatus("録音セッションの初期化に失敗しました。")
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
                    showStatus("録音中にエラーが発生しました: ${exception.message}")
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

    private fun saveTranscriptToMyFiles() {
        if (currentTranscript.isBlank()) {
            Toast.makeText(this, "保存する文字起こし結果がありません。", Toast.LENGTH_SHORT).show()
            return
        }

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
        if (targetUri == null) {
            showStatus("保存先ファイルの作成に失敗しました。")
            return
        }

        val audioPathText = wavAudioFile?.absolutePath ?: "未生成"
        val saveResult = runCatching {
            resolver.openOutputStream(targetUri)?.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer?.apply {
                    appendLine("音声メモ")
                    appendLine("作成日時: ${createTimestampForDisplay()}")
                    appendLine("録音ファイル: $audioPathText")
                    appendLine()
                    appendLine(currentTranscript.trim())
                }
            }
        }

        if (saveResult.isFailure) {
            resolver.delete(targetUri, null, null)
            showStatus("テキスト保存に失敗しました: ${saveResult.exceptionOrNull()?.message}")
            return
        }

        val completeValues = ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        }
        resolver.update(targetUri, completeValues, null, null)
        lastSavedUri = targetUri
        showStatus("保存が完了しました。My Files の Documents/VoiceMemo を確認してください。")
        updateButtons()
    }

    private fun createCompatibleSpeechRecognizer(): SpeechRecognizer {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
        ) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
        } else {
            SpeechRecognizer.createSpeechRecognizer(this)
        }
    }

    private fun buildRecognitionListener(): RecognitionListener {
        return object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                showStatus(getString(R.string.status_recording))
            }

            override fun onBeginningOfSpeech() = Unit

            override fun onRmsChanged(rmsdB: Float) = Unit

            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() = Unit

            override fun onError(error: Int) {
                isTranscriptionPending = false
                showStatus("文字起こしエラー: ${speechErrorToMessage(error)}")
                updateButtons()
            }

            override fun onResults(results: Bundle) {
                val transcript = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.joinToString(separator = "\n")
                    ?.trim()
                    .orEmpty()

                currentTranscript = transcript
                transcriptTextView.text =
                    if (transcript.isBlank()) getString(R.string.hint_transcript) else transcript
                isTranscriptionPending = false

                showStatus(
                    if (transcript.isBlank()) {
                        "文字起こし結果が取得できませんでした。もう一度お試しください。"
                    } else {
                        getString(R.string.status_ready_to_save)
                    }
                )
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

        // API 33 では MediaStore を使うため追加の保存権限は不要だが、
        // 旧 API へ広げる場合に備えて条件付きで扱えるようにしている
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
            SpeechRecognizer.ERROR_AUDIO -> "音声入力処理に失敗しました。"
            SpeechRecognizer.ERROR_CLIENT -> "認識クライアントの状態が不正です。"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "マイク権限が不足しています。"
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "音声認識サービスへ接続できませんでした。"
            SpeechRecognizer.ERROR_NO_MATCH -> "認識できる音声が見つかりませんでした。"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "音声認識サービスが使用中です。"
            SpeechRecognizer.ERROR_SERVER -> "音声認識サービス側でエラーが発生しました。"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "音声入力がタイムアウトしました。"
            else -> "不明なエラーです。"
        }
    }

    private fun updateButtons() {
        startButton.isEnabled = !isRecording && !isTranscriptionPending
        stopButton.isEnabled = isRecording
        saveButton.isEnabled = !isRecording && !isTranscriptionPending && currentTranscript.isNotBlank()
    }

    private fun showStatus(text: String) {
        statusTextView.text = text
    }

    private fun createTimestampForFile(): String {
        return SimpleDateFormat("yyyyMMdd_HHmmss", Locale.JAPAN).format(Date())
    }

    private fun createTimestampForDisplay(): String {
        return SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.JAPAN).format(Date())
    }
}
