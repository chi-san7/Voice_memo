package com.example.voice_memo

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.view.inputmethod.InputMethodManager
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var modeSelectionContainer: View
    private lateinit var operationContainer: View
    private lateinit var onlineModeButton: Button
    private lateinit var offlineModeButton: Button
    private lateinit var changeModeButton: Button
    private lateinit var modeDescriptionTextView: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var statusIndicatorView: View
    private lateinit var recordingTimeTextView: TextView
    private lateinit var textMemoEditText: EditText
    private lateinit var savedPathTextView: TextView
    private lateinit var latestSaveTextView: TextView

    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null

    private var selectedMode = OperationMode.NONE
    private var lastSavedUri: Uri? = null
    private var activeRecordingFile: File? = null
    private var hasReleasedWearHFMic = false
    private var isRecording = false
    private var isDictationInProgress = false
    private var shouldAutoStartAfterPermission = false
    private var recordingStartedAtMs = 0L

    @Volatile
    private var audioCaptureError: Throwable? = null

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private val dictationResultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            if (!isDictationInProgress) {
                return
            }
            when (intent?.action) {
                ACTION_DICTATION_RESULT -> {
                    val recognizedText = intent.getStringExtra(EXTRA_REALWEAR_TEXT).orEmpty().trim()
                    isDictationInProgress = false
                    if (recognizedText.isNotEmpty()) {
                        applyDictationResult(recognizedText)
                        latestSaveTextView.text = getString(
                            R.string.label_text_input_completed,
                            createTimestampForDisplay()
                        )
                    } else {
                        latestSaveTextView.text = getString(R.string.label_transcription_error)
                    }
                    updateButtons()
                }

                ACTION_DICTATION_ERROR -> {
                    isDictationInProgress = false
                    latestSaveTextView.text = getString(R.string.label_transcription_error)
                    updateButtons()
                }
            }
        }
    }

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

    private val dictationLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            handleDictationActivityResult(result)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // RealWear action button's default home behavior must be disabled
        // before the app can receive keycode 500 events.
        (findViewById<ViewGroup>(android.R.id.content).getChildAt(0))?.contentDescription =
            REALWEAR_DISABLE_ACTION_BUTTON_HOME

        modeSelectionContainer = findViewById(R.id.modeSelectionContainer)
        operationContainer = findViewById(R.id.operationContainer)
        onlineModeButton = findViewById(R.id.onlineModeButton)
        offlineModeButton = findViewById(R.id.offlineModeButton)
        changeModeButton = findViewById(R.id.changeModeButton)
        modeDescriptionTextView = findViewById(R.id.modeDescriptionTextView)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        statusIndicatorView = findViewById(R.id.statusIndicatorView)
        recordingTimeTextView = findViewById(R.id.recordingTimeTextView)
        textMemoEditText = findViewById(R.id.textMemoEditText)
        savedPathTextView = findViewById(R.id.savedPathTextView)
        latestSaveTextView = findViewById(R.id.latestSaveTextView)

        textMemoEditText.doAfterTextChanged {
            if (selectedMode == OperationMode.ONLINE_TRANSCRIPTION && !isDictationInProgress) {
                updateButtons()
            }
        }

        onlineModeButton.setOnClickListener { selectMode(OperationMode.ONLINE_TRANSCRIPTION) }
        offlineModeButton.setOnClickListener { selectMode(OperationMode.OFFLINE_RECORDING) }
        changeModeButton.setOnClickListener { showModeSelection() }
        startButton.setOnClickListener {
            when (selectedMode) {
                OperationMode.OFFLINE_RECORDING -> startRecordingFlow()
                OperationMode.ONLINE_TRANSCRIPTION -> startDictationFlow()
                OperationMode.NONE -> Unit
            }
        }
        stopButton.setOnClickListener {
            when (selectedMode) {
                OperationMode.OFFLINE_RECORDING -> stopRecordingFlow()
                OperationMode.ONLINE_TRANSCRIPTION -> {
                    if (isDictationInProgress) {
                        cancelDictationFlow()
                    } else {
                        saveTextMemo()
                    }
                }
                OperationMode.NONE -> Unit
            }
        }

        registerReceiver(
            dictationResultReceiver,
            IntentFilter().apply {
                addAction(ACTION_DICTATION_RESULT)
                addAction(ACTION_DICTATION_ERROR)
            },
            RECEIVER_EXPORTED
        )

        showModeSelection()
        updateButtons()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(dictationResultReceiver)
        stopRecordingTimer(resetDisplay = true)
        cleanupAudioCapture(deleteTempFile = true)
        releaseWearHFMicrophone()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == REALWEAR_ACTION_BUTTON_KEY_CODE) {
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (
            keyCode == REALWEAR_ACTION_BUTTON_KEY_CODE &&
            isRecording &&
            event.repeatCount == 0
        ) {
            stopRecordingFlow()
            return true
        }
        return super.onKeyUp(keyCode, event)
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
        textMemoEditText.setText(getString(R.string.hint_audio_recording))
        startRecordingTimer()
        requestWearHFMicrophoneRelease()
        startAudioCapture(recorder, recordingFile, bufferSize.coerceAtLeast(DEFAULT_BUFFER_SIZE_BYTES))
        updateButtons()
    }

    private fun startDictationFlow() {
        if (isDictationInProgress) {
            return
        }

        isDictationInProgress = true
        textMemoEditText.requestFocus()
        latestSaveTextView.text = getString(R.string.label_text_input_waiting)
        updateButtons()

        try {
            dictationLauncher.launch(
                Intent(ACTION_DICTATION).apply {
                    putExtra(EXTRA_REALWEAR_SOURCE_PACKAGE, packageName)
                }
            )
        } catch (_: ActivityNotFoundException) {
            runCatching {
                sendBroadcast(Intent(ACTION_DICTATION).apply {
                    putExtra(EXTRA_REALWEAR_SOURCE_PACKAGE, packageName)
                })
            }.onFailure {
                isDictationInProgress = false
                showRealWearKeyboard()
                latestSaveTextView.text = getString(R.string.label_text_input_keyboard)
            }
        }
        updateButtons()
    }

    private fun handleDictationActivityResult(result: ActivityResult) {
        if (!isDictationInProgress) {
            return
        }

        if (result.resultCode != RESULT_OK) {
            isDictationInProgress = false
            latestSaveTextView.text = getString(R.string.label_transcription_error)
            updateButtons()
            return
        }

        val recognizedText = result.data?.getStringExtra(EXTRA_DICTATION_RESULT)
            ?.takeIf { it.isNotBlank() }
            ?: result.data?.getStringExtra(EXTRA_REALWEAR_TEXT)
                ?.takeIf { it.isNotBlank() }

        if (recognizedText != null) {
            isDictationInProgress = false
            applyDictationResult(recognizedText)
            latestSaveTextView.text = getString(
                R.string.label_text_input_completed,
                createTimestampForDisplay()
            )
            updateButtons()
        }
    }

    private fun cancelDictationFlow() {
        if (!isDictationInProgress) {
            return
        }

        isDictationInProgress = false
        latestSaveTextView.text = getString(R.string.label_transcription_stopped)
        updateButtons()
    }

    private fun stopRecordingFlow() {
        if (!isRecording) {
            return
        }

        isRecording = false
        stopRecordingTimer(resetDisplay = false)
        latestSaveTextView.text = getString(R.string.message_saving_audio)
        textMemoEditText.setText(getString(R.string.hint_audio_saving))
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
            textMemoEditText.setText(getString(R.string.hint_audio_saved, createTimestampForDisplay()))
        }.onFailure { exception ->
            latestSaveTextView.text = getString(
                R.string.message_auto_save_failed,
                exception.message ?: getString(R.string.message_unknown_error)
            )
            textMemoEditText.setText(
                getString(
                    R.string.hint_audio_failed,
                    exception.message ?: getString(R.string.message_unknown_error)
                )
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
        textMemoEditText.setText(getString(R.string.hint_audio_failed, getString(R.string.message_recording_empty)))
        deleteTemporaryRecording()
        updateButtons()
    }

    private fun handleAudioCaptureFailure(exception: Throwable) {
        latestSaveTextView.text = getString(
            R.string.message_recording_failed,
            exception.message ?: getString(R.string.message_unknown_error)
        )
        textMemoEditText.setText(
            getString(
                R.string.hint_audio_failed,
                exception.message ?: getString(R.string.message_unknown_error)
            )
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
        textMemoEditText.setText(getString(R.string.hint_audio))
        recordingTimeTextView.text = getString(R.string.recording_time_initial)
        updateButtons()
    }

    private fun applyDictationResult(recognizedText: String) {
        val currentText = textMemoEditText.text?.toString()?.trim().orEmpty()
        val nextText = when {
            currentText.isBlank() ||
                currentText == getString(R.string.hint_online) ||
                currentText == getString(R.string.message_realwear_dictation_in_progress) ->
                recognizedText
            else -> "$currentText\n$recognizedText"
        }
        textMemoEditText.setText(nextText)
        textMemoEditText.setSelection(textMemoEditText.text?.length ?: 0)
    }

    private fun showRealWearKeyboard() {
        textMemoEditText.requestFocus()
        textMemoEditText.setSelection(textMemoEditText.text?.length ?: 0)
        val inputMethodManager = getSystemService(InputMethodManager::class.java)
        inputMethodManager?.showSoftInput(textMemoEditText, InputMethodManager.SHOW_FORCED)
    }

    private fun saveTextMemo() {
        if (selectedMode != OperationMode.ONLINE_TRANSCRIPTION) {
            return
        }

        val textToSave = textMemoEditText.text?.toString()?.trim().orEmpty()
        if (textToSave.isBlank()) {
            latestSaveTextView.text = getString(R.string.message_text_empty)
            updateButtons()
            return
        }

        val saveResult = saveTextToMediaStore(textToSave)
        saveResult.onSuccess { savedUri ->
            lastSavedUri = savedUri
            latestSaveTextView.text = getString(
                R.string.label_latest_save,
                extractDisplayName(savedUri)
            )
            Toast.makeText(this, R.string.message_text_saved_short, Toast.LENGTH_SHORT).show()
        }.onFailure { exception ->
            latestSaveTextView.text = getString(
                R.string.message_text_save_failed,
                exception.message ?: getString(R.string.message_unknown_error)
            )
            Toast.makeText(this, R.string.message_text_save_failed_short, Toast.LENGTH_SHORT).show()
        }
        updateButtons()
    }

    private fun saveTextToMediaStore(textToSave: String): Result<Uri> {
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
            resolver.openOutputStream(targetUri).use { output ->
                requireNotNull(output)
                output.writer(Charsets.UTF_8).use { writer ->
                    writer.write(textToSave)
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

    private fun selectMode(mode: OperationMode) {
        selectedMode = mode
        isDictationInProgress = false
        if (isRecording) {
            stopRecordingFlow()
        } else {
            cleanupAudioCapture(deleteTempFile = true)
            releaseWearHFMicrophone()
        }
        stopRecordingTimer(resetDisplay = true)
        lastSavedUri = null
        activeRecordingFile = null
        audioCaptureError = null

        modeSelectionContainer.visibility = View.GONE
        operationContainer.visibility = View.VISIBLE

        when (mode) {
            OperationMode.ONLINE_TRANSCRIPTION -> {
                modeDescriptionTextView.text = getString(R.string.mode_online_description)
                startButton.text = getString(R.string.button_start_online)
                startButton.contentDescription = getString(R.string.command_input)
                stopButton.visibility = View.VISIBLE
                textMemoEditText.isEnabled = true
                textMemoEditText.isFocusable = true
                textMemoEditText.isFocusableInTouchMode = true
                textMemoEditText.setText("")
                textMemoEditText.hint = getString(R.string.hint_online)
                savedPathTextView.text = getString(R.string.label_text_save_path)
                latestSaveTextView.text = getString(R.string.label_text_input_ready)
                recordingTimeTextView.text = getString(R.string.recording_time_not_applicable)
            }

            OperationMode.OFFLINE_RECORDING -> {
                modeDescriptionTextView.text = getString(R.string.mode_offline_description)
                startButton.text = getString(R.string.button_start)
                startButton.contentDescription = getString(R.string.command_start)
                stopButton.visibility = View.VISIBLE
                textMemoEditText.isEnabled = false
                textMemoEditText.isFocusable = false
                textMemoEditText.isFocusableInTouchMode = false
                textMemoEditText.setText(getString(R.string.hint_audio))
                textMemoEditText.hint = null
                savedPathTextView.text = getString(R.string.label_saved_path)
                latestSaveTextView.text = getString(R.string.label_latest_save_empty)
                recordingTimeTextView.text = getString(R.string.recording_time_initial)
            }

            OperationMode.NONE -> Unit
        }

        updateButtons()
    }

    private fun showModeSelection() {
        selectedMode = OperationMode.NONE
        isDictationInProgress = false
        modeSelectionContainer.visibility = View.VISIBLE
        operationContainer.visibility = View.GONE
        textMemoEditText.isEnabled = false
        textMemoEditText.isFocusable = false
        textMemoEditText.isFocusableInTouchMode = false
        textMemoEditText.setText(getString(R.string.hint_audio))
        textMemoEditText.hint = null
        latestSaveTextView.text = getString(R.string.label_latest_save_empty)
        recordingTimeTextView.text = getString(R.string.recording_time_initial)
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
        val hasTextMemo = !textMemoEditText.text?.toString()?.trim().isNullOrEmpty()
        startButton.isEnabled = when (selectedMode) {
            OperationMode.OFFLINE_RECORDING -> !isRecording
            OperationMode.ONLINE_TRANSCRIPTION -> !isDictationInProgress
            OperationMode.NONE -> false
        }
        stopButton.isEnabled = when (selectedMode) {
            OperationMode.OFFLINE_RECORDING -> isRecording
            OperationMode.ONLINE_TRANSCRIPTION -> isDictationInProgress || hasTextMemo
            OperationMode.NONE -> false
        }
        changeModeButton.isEnabled = !isRecording && !isDictationInProgress

        if (selectedMode == OperationMode.ONLINE_TRANSCRIPTION) {
            if (isDictationInProgress) {
                stopButton.text = getString(R.string.button_cancel_online)
                stopButton.contentDescription = getString(R.string.command_cancel)
            } else {
                stopButton.text = getString(R.string.button_save_text)
                stopButton.contentDescription = getString(R.string.command_save)
            }
        } else {
            stopButton.text = getString(R.string.button_stop)
            stopButton.contentDescription = getString(R.string.command_stop)
        }

        val isBusy = isRecording || isDictationInProgress
        val indicatorBackground = if (isBusy) {
            R.drawable.status_indicator_recording
        } else {
            R.drawable.status_indicator_idle
        }
        statusIndicatorView.setBackgroundResource(indicatorBackground)
        statusIndicatorView.contentDescription = getString(
            if (isBusy) R.string.state_recording else R.string.state_idle
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
        private const val REALWEAR_ACTION_BUTTON_KEY_CODE = 500
        private const val REALWEAR_DISABLE_ACTION_BUTTON_HOME = "hf_no_ptt_home"

        private const val ACTION_RELEASE_MIC =
            "com.realwear.wearhf.intent.action.RELEASE_MIC"
        private const val ACTION_MIC_RELEASED =
            "com.realwear.wearhf.intent.action.MIC_RELEASED"
        private const val ACTION_DICTATION =
            "com.realwear.wearhf.intent.action.DICTATION"
        private const val ACTION_DICTATION_RESULT =
            "com.realwear.wearhf.intent.action.DICTATION_RESULT"
        private const val ACTION_DICTATION_ERROR =
            "com.realwear.wearhf.intent.action.DICTATION_ERROR"
        private const val EXTRA_REALWEAR_SOURCE_PACKAGE =
            "com.realwear.wearhf.intent.extra.SOURCE_PACKAGE"
        private const val EXTRA_REALWEAR_HIDE_TEXT =
            "com.realwear.wearhf.intent.extra.HIDE_TEXT"
        private const val EXTRA_REALWEAR_TEXT =
            "com.realwear.wearhf.intent.extra.TEXT"
        private const val EXTRA_DICTATION_RESULT = "result"
    }

    private enum class OperationMode {
        NONE,
        ONLINE_TRANSCRIPTION,
        OFFLINE_RECORDING
    }
}
