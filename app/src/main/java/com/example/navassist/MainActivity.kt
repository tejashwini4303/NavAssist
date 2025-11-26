package com.example.navassist

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Size
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.example.navassist.databinding.ActivityMainBinding
import org.jtransforms.fft.DoubleFFT_1D
import java.util.*
import java.util.concurrent.Executors
import kotlin.random.Random

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var tts: TextToSpeech
    private lateinit var classifier: TFLiteClassifier

    // CameraX
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null

    // Audio (buzzer detection)
    private var audioRecord: AudioRecord? = null
    private var isListeningBuzzer = false
    private var buzzerThread: Thread? = null

    // Flow control
    private var waitingForBuzzer = false
    private val mainHandler = Handler(Looper.getMainLooper())

    // Rooms & steps (will be set by voice input)
    private var startRoom = 302
    private var destRoom = 307
    private val stepsPerRoom = 15
    private var stepsRemaining = (destRoom - startRoom) * stepsPerRoom

    // Audio detection params (tune if needed)
    private val sampleRate = 44100
    private val targetFreqMin = 2400.0   // buzzer approx lower
    private val targetFreqMax = 3000.0   // buzzer approx upper
    private val detectionMagnitudeThreshold = 1e6  // adjust if many false positives

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        tts = TextToSpeech(this, this)

        // load tflite classifier (make sure file names in assets match)
        classifier = TFLiteClassifier(this, "final_model_new.tflite", "labels.txt")

        // Camera provider (async)
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            cameraProvider = providerFuture.get()
        }, ContextCompat.getMainExecutor(this))

        binding.btnSpeak.setOnClickListener { startVoiceInput() }
        binding.btnStartNav.setOnClickListener { onStartNavClicked() }
        binding.btnStep.setOnClickListener { takeStep() }

        requestPermissionsIfNeeded()
    }

    private fun requestPermissionsIfNeeded() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.CAMERA)
        }
        if (needed.isNotEmpty()) {
            requestPermissions(needed.toTypedArray(), 123)
        }
    }

    // TTS init + utterance listener to start buzzer listening AFTER TTS completes speaking the "total steps" line
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.US
            tts.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onError(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    // When total steps utterance finished, start buzzer listening
                    if (utteranceId == "UTT_TOTAL_STEPS") {
                        mainHandler.post {
                            // small delay to ensure spoken message finished fully
                            startBuzzerListening()
                        }
                    }
                }
            })
        }
    }

    private fun speak(text: String, utteranceId: String = UUID.randomUUID().toString()) {
        val params = Bundle()
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
    }

    // ------------------- Voice input (room numbers) -------------------
    private fun startVoiceInput() {
        val sr = SpeechRecognizer.createSpeechRecognizer(this)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        }

        sr.setRecognitionListener(object : SimpleRecognitionListener() {
            override fun onResultsBundle(results: Bundle) {
                val text = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.get(0) ?: ""
                val nums = Regex("\\d+").findAll(text).map { it.value.toInt() }.toList()
                if (nums.size >= 2) {
                    startRoom = nums[0]
                    destRoom = nums[1]
                    stepsRemaining = (destRoom - startRoom) * stepsPerRoom
                    // Speak total steps and ensure buzzer listening runs only AFTER this utterance finishes
                    speak("Navigation set from $startRoom to $destRoom. Total $stepsRemaining steps.", "UTT_TOTAL_STEPS")
                } else {
                    speak("Please say two room numbers clearly, for example three zero two to three zero seven.")
                }
            }
        })

        sr.startListening(intent)
    }

    // ------------------- Start Nav button clicked -------------------
    private fun onStartNavClicked() {
        speak("Please press Speak and say source and destination first.")
    }

    // ------------------- Step button -------------------
    private fun takeStep() {
        if (stepsRemaining > 0) {
            stepsRemaining--
            when {
                stepsRemaining % 10 == 0 && stepsRemaining > 5 -> speak("$stepsRemaining steps away")
                stepsRemaining in 1..5 -> speak("You are near the destination")
                stepsRemaining == 0 -> speak("You have reached the destination")
            }
        }
    }

    // ------------------- Buzzer listening -------------------
    private fun startBuzzerListening() {
        // Ensure permissions for record audio are granted
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            speak("Audio permission not granted. Please allow microphone permission.")
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 124)
            return
        }

        if (isListeningBuzzer) return
        isListeningBuzzer = true
        waitingForBuzzer = true
        speak("Listening for buzzer sound now.")

        buzzerThread = Thread {
            try {
                val minBuf = AudioRecord.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )

                val bufferSize = if (minBuf > 16384) minBuf else 16384

                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )

                val shortBuffer = ShortArray(bufferSize / 2)
                val fftN = 16384
                val fft = DoubleFFT_1D(fftN.toLong())
                val fftInput = DoubleArray(fftN * 2)

                audioRecord?.startRecording()

                while (isListeningBuzzer) {
                    val read = audioRecord?.read(shortBuffer, 0, shortBuffer.size) ?: 0
                    if (read <= 0) continue

                    for (i in 0 until fftN) {
                        fftInput[2 * i] = if (i < read) shortBuffer[i].toDouble() else 0.0
                        fftInput[2 * i + 1] = 0.0
                    }

                    fft.complexForward(fftInput)

                    var maxMag = 0.0
                    var maxIdx = 0
                    for (i in 0 until fftN / 2) {
                        val re = fftInput[2 * i]
                        val im = fftInput[2 * i + 1]
                        val mag = re * re + im * im
                        if (mag > maxMag) {
                            maxMag = mag
                            maxIdx = i
                        }
                    }

                    val freq = maxIdx * sampleRate.toDouble() / fftN

                    if (freq in targetFreqMin..targetFreqMax && maxMag > detectionMagnitudeThreshold && waitingForBuzzer) {
                        waitingForBuzzer = false
                        mainHandler.post {
                            speak("Obstacle detected. Turning on camera.")
                            handleBuzzerEventOnce()
                        }
                        Thread.sleep(800)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                try { audioRecord?.stop() } catch (_: Exception) {}
                try { audioRecord?.release() } catch (_: Exception) {}
                audioRecord = null
            }
        }

        buzzerThread?.start()
    }

    private fun stopBuzzerListening() {
        isListeningBuzzer = false
        waitingForBuzzer = false
        buzzerThread?.interrupt()
        buzzerThread = null
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
    }

    // ------------------- Handle buzzer event: start camera ONCE, classify single frame -------------------
    private fun handleBuzzerEventOnce() {
        val cp = cameraProvider ?: run {
            speak("Camera not ready.")
            waitingForBuzzer = true
            return
        }

        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
        }

        var didRunOnce = false

        val analysis = ImageAnalysis.Builder()
            .setTargetResolution(Size(224, 224))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        analysis.setAnalyzer(cameraExecutor) { image: ImageProxy ->
            try {
                if (didRunOnce) {
                    image.close()
                    return@setAnalyzer
                }

                val (label, confidence) = classifier.classify(image)

                if (label != "none" && confidence > 0.5f) {
                    didRunOnce = true

                    val direction = if (Random.nextBoolean()) "Turn left and move forward" else "Turn right and move forward"
                    speak(direction)

                    mainHandler.post {
                        try {
                            cp.unbindAll()
                        } catch (e: Exception) {
                            e.printStackTrace()
                        } finally {
                            mainHandler.postDelayed({
                                waitingForBuzzer = true
                                speak("Listening for next buzzer.")
                            }, 600)
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                image.close()
            }
        }

        mainHandler.post {
            try {
                cp.unbindAll()
                cp.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (e: Exception) {
                e.printStackTrace()
                speak("Camera failed.")
                waitingForBuzzer = true
            }
        }
    }

    override fun onDestroy() {
        stopBuzzerListening()
        try { classifier.close() } catch (_: Exception) {}
        try { tts.shutdown() } catch (_: Exception) {}
        try { cameraExecutor.shutdown() } catch (_: Exception) {}
        super.onDestroy()
    }
}
