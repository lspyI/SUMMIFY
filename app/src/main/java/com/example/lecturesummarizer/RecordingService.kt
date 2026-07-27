package com.example.lecturesummarizer

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File

class RecordingService : Service() {

    private var speechRecognizer: SpeechRecognizer? = null
    private var mediaRecorder: MediaRecorder? = null
    private var audioFile: File? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    companion object {
        const val CHANNEL_ID = "SummifyRecordingChannel"
        const val NOTIFICATION_ID = 101
        
        val transcriptFlow = MutableSharedFlow<String>(extraBufferCapacity = 1)
        val rmsFlow = MutableStateFlow(0f)
        val isServiceRunning = MutableStateFlow(false)
        val lastAudioPath = MutableStateFlow<String?>(null)

        fun startService(context: Context) {
            val intent = Intent(context, RecordingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, RecordingService::class.java)
            context.stopService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        isServiceRunning.value = true
        startForeground(NOTIFICATION_ID, createNotification("Подготовка к записи..."))
        
        startAudioRecording()
        initSpeechRecognizer()
    }

    private fun startAudioRecording() {
        try {
            audioFile = File(filesDir, "lecture_${System.currentTimeMillis()}.m4a")
            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                MediaRecorder()
            }.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(audioFile?.absolutePath)
                prepare()
                start()
            }
            lastAudioPath.value = audioFile?.absolutePath
            Log.d("RecordingService", "Audio recording started: ${audioFile?.absolutePath}")
        } catch (e: Exception) {
            Log.e("RecordingService", "Failed to start MediaRecorder: ${e.message}")
        }
    }

    private fun initSpeechRecognizer() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }

        val listener = object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                updateNotification("Слушаю и записываю лекцию...")
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {
                rmsFlow.value = rmsdB
            }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                if (isServiceRunning.value) {
                    serviceScope.launch {
                        delay(if (error == 5 || error == 8) 1000L else 500L)
                        speechRecognizer?.startListening(intent)
                    }
                }
            }
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                matches?.get(0)?.let { text ->
                    serviceScope.launch { transcriptFlow.emit(text) }
                }
                if (isServiceRunning.value) speechRecognizer?.startListening(intent)
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        }

        speechRecognizer?.setRecognitionListener(listener)
        speechRecognizer?.startListening(intent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID, "Запись лекций",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(text: String): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SUMMIFY")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_logo)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val notification = createNotification(text)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning.value = false
        speechRecognizer?.destroy()
        try {
            mediaRecorder?.stop()
            mediaRecorder?.release()
        } catch (e: Exception) {
            Log.e("RecordingService", "Error stopping MediaRecorder: ${e.message}")
        }
        Log.d("RecordingService", "Service destroyed")
    }
}
