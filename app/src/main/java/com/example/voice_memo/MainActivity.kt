package com.example.voice_memo

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private enum class VoiceInputMode {
        NONE,
        ANDROID_SPEECH,
        REALWEAR_DICTATION
    }

    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var statusIndicatorView: View
    private lateinit var transcriptTextView: TextView
    private lateinit var latestSaveTextView: TextView

    private var speechRecognizer: SpeechRecognizer? = null
    private var activeVoiceInputMode = VoiceInputMode.NONE

    private var currentTranscript = ""
    private var lastSavedUri: Uri? = null
    private var hasAutoSavedCurrentSession = false
    private var isRecording = false
    private var isTranscriptionPending = false
    private var shouldAutoStartAfterPermission = false

    private val realWearDictationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_REALWEAR_DICTATION_RESULT -> {
                    handleRealWearDictationSuccess(extractRealWearTranscript(intent))
                }

                ACTION_REALWEAR_DICTATION_ERROR -> {
                    handleRealWearDictationError()
                }
            }
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

    private val realWearDictationLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            handleRealWearDictationActivityResult(result)
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

        registerRealWearDictationReceiver()
        transcriptTextView.text = getString(R.string.hint_transcript)
        latestSaveTextView.text = getString(R.string.label_latest_save_empty)
        updateButtons()
    }

    override fun onDestroy() {
        unregisterReceiver(realWearDictationReceiver)
        super.onDestroy()
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

        if (isRealWearDictationAvailable()) {
            startRealWearDictationFlow()
            return
        }

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            latestSaveTextView.text = getString(R.string.message_speech_not_available)
            return
        }

        resetSessionStateForNewCapture()
        activeVoiceInputMode = VoiceInputMode.ANDROID_SPEECH
        latestSaveTextView.text = getString(R.string.message_recording_in_progress)

        destroySpeechRecognizer()
        speechRecognizer = createCompatibleSpeechRecognizer().also { recognizer ->
            recognizer.setRecognitionListener(buildRecognitionListener())
            recognizer.startListening(createRecognizerIntent())
        }
    }

    private fun stopRecordingFlow() {
        if (!isRecording) {
            return
        }

        if (activeVoiceInputMode == VoiceInputMode.REALWEAR_DICTATION) {
            latestSaveTextView.text = getString(R.string.message_realwear_dictation_in_progress)
            return
        }

        isRecording = false
        isTranscriptionPending = true
        latestSaveTextView.text = getString(R.string.message_processing_transcript)
        updateButtons()
        speechRecognizer?.stopListening()
    }

    private fun createRecognizerIntent(): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.JAPAN.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.message_recording_in_progress))
        }
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

        val writeResult = runCatching {
            resolver.openOutputStream(targetUri)?.bufferedWriter(Charsets.UTF_8).use { writer ->
                requireNotNull(writer)
                writer.appendLine(getString(R.string.file_header_title))
                writer.appendLine(getString(R.string.file_header_saved_at, createTimestampForDisplay()))
                writer.appendLine(getString(R.string.file_header_audio_path, getString(R.string.text_audio_path_unavailable)))
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
        return SpeechRecognizer.createSpeechRecognizer(this)
    }

    private fun startRealWearDictationFlow() {
        resetSessionStateForNewCapture()
        activeVoiceInputMode = VoiceInputMode.REALWEAR_DICTATION
        latestSaveTextView.text = getString(R.string.message_realwear_dictation_in_progress)

        val dictationIntent = Intent(ACTION_REALWEAR_DICTATION).apply {
            putExtra(EXTRA_REALWEAR_SOURCE_PACKAGE, packageName)
        }

        try {
            realWearDictationLauncher.launch(dictationIntent)
        } catch (exception: ActivityNotFoundException) {
            handleVoiceInputLaunchFailure(exception)
        } catch (exception: SecurityException) {
            handleVoiceInputLaunchFailure(exception)
        }
    }

    private fun buildRecognitionListener(): RecognitionListener {
        return object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit

            override fun onBeginningOfSpeech() = Unit

            override fun onRmsChanged(rmsdB: Float) = Unit

            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() {
                if (isRecording) {
                    isRecording = false
                    isTranscriptionPending = true
                    latestSaveTextView.text = getString(R.string.message_processing_transcript)
                    updateButtons()
                }
            }

            override fun onError(error: Int) {
                isRecording = false
                isTranscriptionPending = false
                activeVoiceInputMode = VoiceInputMode.NONE
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

                isRecording = false
                isTranscriptionPending = false
                activeVoiceInputMode = VoiceInputMode.NONE
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

    private fun registerRealWearDictationReceiver() {
        val filter = IntentFilter().apply {
            addAction(ACTION_REALWEAR_DICTATION_RESULT)
            addAction(ACTION_REALWEAR_DICTATION_ERROR)
        }
        ContextCompat.registerReceiver(
            this,
            realWearDictationReceiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    private fun handleRealWearDictationActivityResult(result: ActivityResult) {
        if (activeVoiceInputMode != VoiceInputMode.REALWEAR_DICTATION) {
            return
        }

        val transcriptFromResult = extractRealWearTranscript(result.data)
        if (transcriptFromResult.isNotBlank()) {
            handleRealWearDictationSuccess(transcriptFromResult)
            return
        }

        if (result.resultCode == Activity.RESULT_CANCELED) {
            handleRealWearDictationError()
        }
    }

    private fun handleRealWearDictationSuccess(transcript: String) {
        if (activeVoiceInputMode != VoiceInputMode.REALWEAR_DICTATION) {
            return
        }

        currentTranscript = transcript.trim()
        transcriptTextView.text = if (currentTranscript.isBlank()) {
            getString(R.string.hint_transcript_empty)
        } else {
            currentTranscript
        }

        isRecording = false
        isTranscriptionPending = false
        activeVoiceInputMode = VoiceInputMode.NONE
        autoSaveTranscript(currentTranscript)
        updateButtons()
    }

    private fun handleRealWearDictationError() {
        if (activeVoiceInputMode != VoiceInputMode.REALWEAR_DICTATION) {
            return
        }

        isRecording = false
        isTranscriptionPending = false
        activeVoiceInputMode = VoiceInputMode.NONE
        transcriptTextView.text = getString(
            R.string.hint_transcript_failed,
            getString(R.string.message_realwear_dictation_cancelled)
        )
        autoSaveTranscript(
            "",
            getString(
                R.string.file_body_transcript_failed,
                getString(R.string.message_realwear_dictation_cancelled)
            )
        )
        updateButtons()
    }

    private fun handleVoiceInputLaunchFailure(exception: Exception) {
        isRecording = false
        isTranscriptionPending = false
        activeVoiceInputMode = VoiceInputMode.NONE
        latestSaveTextView.text = getString(
            R.string.message_audio_session_failed,
            exception.message ?: getString(R.string.message_realwear_dictation_failed)
        )
        updateButtons()
    }

    private fun resetSessionStateForNewCapture() {
        currentTranscript = ""
        lastSavedUri = null
        hasAutoSavedCurrentSession = false
        isRecording = true
        isTranscriptionPending = false
        transcriptTextView.text = getString(R.string.hint_transcript)
        updateButtons()
    }

    private fun isRealWearDictationAvailable(): Boolean {
        val intent = Intent(ACTION_REALWEAR_DICTATION).apply {
            putExtra(EXTRA_REALWEAR_SOURCE_PACKAGE, packageName)
        }
        return packageManager.resolveActivity(
            intent,
            PackageManager.ResolveInfoFlags.of(0L)
        ) != null
    }

    private fun extractRealWearTranscript(intent: Intent?): String {
        if (intent == null) {
            return ""
        }

        return intent.getStringExtra(EXTRA_REALWEAR_TEXT)
            ?: intent.getStringExtra(EXTRA_LEGACY_REALWEAR_TEXT)
            ?: ""
    }

    private fun hasRequiredPermissions(): Boolean {
        return requiredPermissions().all { permission ->
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
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
        stopButton.isEnabled = isRecording && activeVoiceInputMode == VoiceInputMode.ANDROID_SPEECH

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

    companion object {
        private const val ACTION_REALWEAR_DICTATION =
            "com.realwear.wearhf.intent.action.DICTATION"
        private const val ACTION_REALWEAR_DICTATION_RESULT =
            "com.realwear.wearhf.intent.action.DICTATION_RESULT"
        private const val ACTION_REALWEAR_DICTATION_ERROR =
            "com.realwear.wearhf.intent.action.DICTATION_ERROR"
        private const val EXTRA_REALWEAR_SOURCE_PACKAGE =
            "com.realwear.wearhf.intent.extra.SOURCE_PACKAGE"
        private const val EXTRA_REALWEAR_TEXT = "com.realwear.wearhf.intent.extra.TEXT"
        private const val EXTRA_LEGACY_REALWEAR_TEXT = "result"
    }
}
