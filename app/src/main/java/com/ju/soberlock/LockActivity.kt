package com.ju.soberlock

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.Locale

/**
 * 잠긴 앱 차단 화면.
 *
 * 해제 1순위: 랩/발음 테스트 (랜덤 문장, 한국어 음성인식, 일치도 70% 이상·15초 이내).
 * 폴백(시끄러운 환경용): 점 따라가기 + 반응속도 테스트 10초 고정.
 * 실패 시 5분 쿨다운. 긴급 해제: 5초 꾹 누르기 + 확인 후 10분 전체 해제.
 */
class LockActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PACKAGE = "locked_package"
        private const val REQUEST_RECORD_AUDIO = 1001
        private const val COOLDOWN_MS = 5 * 60 * 1000L
        private const val EMERGENCY_HOLD_MS = 5_000L
        private const val EMERGENCY_UNLOCK_MS = 10 * 60 * 1000L
        private const val RAP_TIMEOUT_MS = 15_000L
        private const val MATCH_THRESHOLD = 0.70
    }

    private lateinit var prefs: Prefs
    private var lockedPackage: String = ""
    private var appLabel: String = ""

    private lateinit var tvLockedApp: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvNoise: TextView
    private lateinit var tvPhrase: TextView
    private lateinit var btnStartRap: Button
    private lateinit var tvRapResult: TextView
    private lateinit var btnDotTest: Button
    private lateinit var btnRapTest: Button
    private lateinit var btnStartDot: Button
    private lateinit var dotTestView: DotTestView
    private lateinit var tvDotResult: TextView
    private lateinit var tvCooldown: TextView
    private lateinit var btnEmergency: Button
    private lateinit var layoutRap: View
    private lateinit var layoutDot: View

    private var speechRecognizer: SpeechRecognizer? = null
    private var currentPhrase: String = ""
    private var rapStartAt = 0L
    private var listening = false
    private var rapHandled = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var rapTimeoutRunnable: Runnable? = null
    private var cooldownTimer: CountDownTimer? = null

    private var emergencyHolding = false
    private var emergencyHoldStart = 0L
    private val emergencyHandler = Handler(Looper.getMainLooper())
    private var emergencyProgressRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lock)

        prefs = Prefs(this)
        lockedPackage = intent.getStringExtra(EXTRA_PACKAGE) ?: ""
        appLabel = try {
            val ai = packageManager.getApplicationInfo(lockedPackage, 0)
            packageManager.getApplicationLabel(ai).toString()
        } catch (_: Exception) {
            lockedPackage
        }

        tvLockedApp = findViewById(R.id.tvLockedApp)
        tvStatus = findViewById(R.id.tvStatus)
        tvNoise = findViewById(R.id.tvNoise)
        tvPhrase = findViewById(R.id.tvPhrase)
        btnStartRap = findViewById(R.id.btnStartRap)
        tvRapResult = findViewById(R.id.tvRapResult)
        btnDotTest = findViewById(R.id.btnDotTest)
        btnRapTest = findViewById(R.id.btnRapTest)
        btnStartDot = findViewById(R.id.btnStartDot)
        dotTestView = findViewById(R.id.dotTestView)
        tvDotResult = findViewById(R.id.tvDotResult)
        tvCooldown = findViewById(R.id.tvCooldown)
        btnEmergency = findViewById(R.id.btnEmergency)
        layoutRap = findViewById(R.id.layoutRap)
        layoutDot = findViewById(R.id.layoutDot)
        val btnGoHome: Button = findViewById(R.id.btnGoHome)

        tvLockedApp.text = "🔒 $appLabel 앱이 잠겨 있습니다"
        tvStatus.text = "해제하려면 랩 테스트 또는 점 테스트를 통과하세요."

        btnStartRap.setOnClickListener { startRap() }
        btnDotTest.setOnClickListener { showDotTest() }
        btnRapTest.setOnClickListener { showRapTest() }
        btnStartDot.setOnClickListener { startDotTest() }
        btnGoHome.setOnClickListener { goHome() }

        setupEmergencyButton()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                goHome()
            }
        })

        refreshCooldown()
        measureNoise()
    }

    override fun onResume() {
        super.onResume()
        refreshCooldown()
    }

    override fun onDestroy() {
        super.onDestroy()
        cooldownTimer?.cancel()
        mainHandler.removeCallbacksAndMessages(null)
        emergencyHandler.removeCallbacksAndMessages(null)
        try {
            speechRecognizer?.destroy()
        } catch (_: Exception) {
        }
        speechRecognizer = null
    }

    // ---------- 소음 측정 ----------

    private fun measureNoise() {
        tvNoise.text = "주변 소음 측정 중… (1초)"
        NoiseMeter.measure(this) { amplitude, isNoisy ->
            runOnUiThread {
                val earphone = isEarphoneMicInUse()
                tvNoise.text = if (isNoisy) {
                    "주변 소음이 큽니다 (진폭 $amplitude). 랩 대신 점 테스트를 권장합니다." +
                        if (earphone) " 이어폰 마이크 사용 중." else ""
                } else {
                    "주변 소음 보통 (진폭 $amplitude). 랩 테스트를 할 수 있습니다." +
                        if (earphone) " 이어폰 마이크 사용 중." else ""
                }
                if (isNoisy) {
                    // 랩을 강요하지 않고 점 테스트로 자동 전환 제안
                    showDotTest()
                    tvStatus.text = "시끄러운 환경입니다. 점 테스트로 해제하세요. 랩으로 바꾸려면 아래 버튼을 누르세요."
                }
            }
        }
    }

    private fun isEarphoneMicInUse(): Boolean {
        return try {
            val am = getSystemService(AudioManager::class.java)
            am.isWiredHeadsetOn || am.isBluetoothA2dpOn
        } catch (_: Exception) {
            false
        }
    }

    private fun showDotTest() {
        layoutRap.visibility = View.GONE
        layoutDot.visibility = View.VISIBLE
        tvDotResult.text = ""
    }

    private fun showRapTest() {
        layoutDot.visibility = View.GONE
        layoutRap.visibility = View.VISIBLE
        dotTestView.stopTest()
    }

    // ---------- 랩/발음 테스트 ----------

    private fun startRap() {
        if (isCoolingDown()) {
            Toast.makeText(this, "쿨다운 중입니다. 잠시 뒤 다시 시도하세요.", Toast.LENGTH_SHORT).show()
            return
        }
        if (listening) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO
            )
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "이 기기에서 음성인식을 사용할 수 없습니다. 점 테스트를 이용하세요.", Toast.LENGTH_LONG).show()
            showDotTest()
            return
        }
        currentPhrase = PhraseBook.random()
        tvPhrase.text = "따라 읽으세요:\n\"$currentPhrase\""
        tvRapResult.text = "듣는 중… 박자에 맞춰 빠르고 정확하게 읽으세요."
        rapStartAt = System.currentTimeMillis()
        rapHandled = false
        listening = true

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        try {
            speechRecognizer?.destroy()
        } catch (_: Exception) {
        }
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}

                override fun onError(error: Int) {
                    if (rapHandled) return
                    rapHandled = true
                    listening = false
                    cancelRapTimeout()
                    tvRapResult.text = "인식에 실패했습니다 (오류 $error). 점 테스트를 이용하거나 5분 뒤 다시 시도하세요."
                    // 인식 오류 자체는 쿨다운을 걸지 않고 재시도 기회를 준다.
                    // 단, 말이 안 들린 경우(NO_MATCH/SPEECH_TIMEOUT)는 실패로 처리한다.
                    if (error == SpeechRecognizer.ERROR_NO_MATCH ||
                        error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                    ) {
                        handleFail("랩 인식 실패 (말이 인식되지 않음)")
                    }
                }

                override fun onResults(results: Bundle?) {
                    if (rapHandled) return
                    rapHandled = true
                    listening = false
                    cancelRapTimeout()
                    val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val heard = list?.firstOrNull() ?: ""
                    val elapsed = System.currentTimeMillis() - rapStartAt
                    val score = PhraseBook.matchScore(currentPhrase, heard)
                    val scorePct = (score * 100).toInt()
                    tvRapResult.text = "인식: \"$heard\"\n일치도 $scorePct% · 소요 ${elapsed}ms"
                    LogStore.append(
                        this@LockActivity,
                        "랩 결과: 일치도 $scorePct% · ${elapsed}ms"
                    )
                    if (score >= MATCH_THRESHOLD && elapsed <= RAP_TIMEOUT_MS) {
                        unlockSuccess(emergency = false)
                    } else {
                        handleFail("랩 불일치 (일치도 $scorePct%, ${elapsed}ms)")
                    }
                }
            })
            startListening(intent)
        }
        rapTimeoutRunnable = Runnable {
            if (!rapHandled && listening) {
                rapHandled = true
                listening = false
                try {
                    speechRecognizer?.stopListening()
                } catch (_: Exception) {
                }
                tvRapResult.text = "15초 안에 끝내지 못했습니다."
                handleFail("랩 시간 초과 (15초)")
            }
        }
        mainHandler.postDelayed(rapTimeoutRunnable!!, RAP_TIMEOUT_MS)
    }

    private fun cancelRapTimeout() {
        rapTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        rapTimeoutRunnable = null
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORD_AUDIO) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startRap()
            } else {
                Toast.makeText(this, "마이크 권한이 없으면 랩 테스트를 할 수 없습니다. 점 테스트를 이용하세요.", Toast.LENGTH_LONG).show()
                showDotTest()
            }
        }
    }

    // ---------- 점 테스트 ----------

    private fun startDotTest() {
        if (isCoolingDown()) {
            Toast.makeText(this, "쿨다운 중입니다. 잠시 뒤 다시 시도하세요.", Toast.LENGTH_SHORT).show()
            return
        }
        tvDotResult.text = "10초간 점을 뜨자마자 터치하세요."
        dotTestView.post {
            dotTestView.startTest { results ->
                val (pass, detail) = DotTestView.evaluate(results, prefs.baselineReactionMs)
                tvDotResult.text = detail
                LogStore.append(this@LockActivity, "점 테스트 결과: $detail")
                if (pass) {
                    unlockSuccess(emergency = false)
                } else {
                    handleFail("점 테스트 불통과 ($detail)")
                }
            }
        }
    }

    // ---------- 쿨다운 ----------

    private fun isCoolingDown(): Boolean =
        System.currentTimeMillis() < prefs.cooldownUntil

    private fun refreshCooldown() {
        val now = System.currentTimeMillis()
        val remaining = prefs.cooldownUntil - now
        if (remaining <= 0) {
            cooldownTimer?.cancel()
            tvCooldown.text = ""
            btnStartRap.isEnabled = true
            btnDotTest.isEnabled = true
            return
        }
        btnStartRap.isEnabled = false
        btnDotTest.isEnabled = false
        cooldownTimer?.cancel()
        cooldownTimer = object : CountDownTimer(remaining, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val sec = millisUntilFinished / 1000
                tvCooldown.text = "쿨다운 중: ${sec / 60}분 ${sec % 60}초 남음 (그 전에는 재도전 불가)"
            }

            override fun onFinish() {
                tvCooldown.text = ""
                btnStartRap.isEnabled = true
                btnDotTest.isEnabled = true
                Toast.makeText(this@LockActivity, "쿨다운이 끝났습니다. 다시 시도할 수 있습니다.", Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    private fun handleFail(reason: String) {
        val now = System.currentTimeMillis()
        prefs.cooldownUntil = now + COOLDOWN_MS
        try {
            speechRecognizer?.stopListening()
        } catch (_: Exception) {
        }
        listening = false
        dotTestView.stopTest()
        LogStore.append(this, "해제 실패: $reason — 5분 쿨다운")
        Toast.makeText(this, "해제에 실패했습니다. 5분 뒤 다시 시도하세요.", Toast.LENGTH_LONG).show()
        refreshCooldown()
    }

    // ---------- 해제 성공 ----------

    private fun unlockSuccess(emergency: Boolean) {
        val now = System.currentTimeMillis()
        if (emergency) {
            prefs.emergencyUnlockUntil = now + EMERGENCY_UNLOCK_MS
            LogStore.append(this, "긴급 해제: 10분간 전체 해제 ($appLabel)")
        } else {
            val dur = prefs.unlockDurationMin
            prefs.unlockUntil = if (dur == -1) {
                Long.MAX_VALUE
            } else {
                now + dur * 60 * 1000L
            }
            LogStore.append(this, "해제 성공: $appLabel (유지 ${if (dur == -1) "화면 끌 때까지" else "${dur}분"})")
        }
        Toast.makeText(this, "잠금이 해제되었습니다.", Toast.LENGTH_SHORT).show()
        launchLockedApp()
        finish()
    }

    private fun launchLockedApp() {
        if (lockedPackage.isEmpty()) {
            goHome()
            return
        }
        try {
            val intent = packageManager.getLaunchIntentForPackage(lockedPackage)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            } else {
                goHome()
            }
        } catch (_: Exception) {
            goHome()
        }
    }

    private fun goHome() {
        val home = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        try {
            startActivity(home)
        } catch (_: Exception) {
        }
        finish()
    }

    // ---------- 긴급 해제 ----------

    private fun setupEmergencyButton() {
        btnEmergency.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    emergencyHolding = true
                    emergencyHoldStart = System.currentTimeMillis()
                    btnEmergency.text = "꾹 누르는 중… 0.0초"
                    emergencyProgressRunnable = object : Runnable {
                        override fun run() {
                            if (!emergencyHolding) return
                            val elapsed = System.currentTimeMillis() - emergencyHoldStart
                            val sec = elapsed / 1000.0
                            btnEmergency.text = "꾹 누르는 중… %.1f초 / 5초".format(sec)
                            if (elapsed >= EMERGENCY_HOLD_MS) {
                                emergencyHolding = false
                                btnEmergency.text = "긴급 해제 (5초 꾹 누르기)"
                                showEmergencyConfirm()
                            } else {
                                emergencyHandler.postDelayed(this, 100)
                            }
                        }
                    }
                    emergencyHandler.post(emergencyProgressRunnable!!)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    emergencyHolding = false
                    emergencyHandler.removeCallbacksAndMessages(null)
                    btnEmergency.text = "긴급 해제 (5초 꾹 누르기)"
                    true
                }
                else -> false
            }
        }
    }

    private fun showEmergencyConfirm() {
        AlertDialog.Builder(this)
            .setTitle("긴급 해제")
            .setMessage("정말 긴급한가요? 해제 기록이 남습니다.\n긴급 해제 시 10분간 모든 잠금이 풀립니다.")
            .setPositiveButton("긴급 해제") { _, _ ->
                unlockSuccess(emergency = true)
            }
            .setNegativeButton("취소", null)
            .show()
    }
}
