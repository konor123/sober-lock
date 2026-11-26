package com.ju.soberlock

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_RECORD_AUDIO = 1001

        /** 잠글 수 없는 패키지 (전화/설정/자기 자신) */
        val NON_LOCKABLE = setOf(
            "com.android.dialer",
            "com.google.android.dialer",
            "com.samsung.android.dialer",
            "com.android.phone",
            "com.android.server.telecom",
            "com.android.settings"
        )
    }

    private lateinit var prefs: Prefs

    private lateinit var tvStatusA11y: TextView
    private lateinit var tvStatusBattery: TextView
    private lateinit var tvStatusMic: TextView
    private lateinit var llAppList: LinearLayout
    private lateinit var rgDuration: RadioGroup
    private lateinit var rbDuration5: RadioButton
    private lateinit var rbDuration10: RadioButton
    private lateinit var rbDuration30: RadioButton
    private lateinit var rbDurationScreenOff: RadioButton
    private lateinit var btnBaseline: Button
    private lateinit var dotTestViewBaseline: DotTestView
    private lateinit var tvBaselineResult: TextView
    private lateinit var tvLog: TextView

    private var baselineRuns = 0
    private val baselineAvgs = mutableListOf<Double>()
    private var baselineRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = Prefs(this)

        tvStatusA11y = findViewById(R.id.tvStatusA11y)
        tvStatusBattery = findViewById(R.id.tvStatusBattery)
        tvStatusMic = findViewById(R.id.tvStatusMic)
        llAppList = findViewById(R.id.llAppList)
        rgDuration = findViewById(R.id.rgDuration)
        rbDuration5 = findViewById(R.id.rbDuration5)
        rbDuration10 = findViewById(R.id.rbDuration10)
        rbDuration30 = findViewById(R.id.rbDuration30)
        rbDurationScreenOff = findViewById(R.id.rbDurationScreenOff)
        btnBaseline = findViewById(R.id.btnBaseline)
        dotTestViewBaseline = findViewById(R.id.dotTestViewBaseline)
        tvBaselineResult = findViewById(R.id.tvBaselineResult)
        tvLog = findViewById(R.id.tvLog)

        val btnA11y: Button = findViewById(R.id.btnA11y)
        val btnBattery: Button = findViewById(R.id.btnBattery)
        val btnMic: Button = findViewById(R.id.btnMic)
        val btnClearLog: Button = findViewById(R.id.btnClearLog)

        btnA11y.setOnClickListener { openAccessibilitySettings() }
        btnBattery.setOnClickListener { requestIgnoreBatteryOptimizations() }
        btnMic.setOnClickListener { requestMicPermission() }
        btnClearLog.setOnClickListener {
            LogStore.clear(this)
            refreshLog()
            Toast.makeText(this, "로그를 지웠습니다", Toast.LENGTH_SHORT).show()
        }
        btnBaseline.setOnClickListener { startBaselineMeasurement() }

        when (prefs.unlockDurationMin) {
            5 -> rbDuration5.isChecked = true
            10 -> rbDuration10.isChecked = true
            30 -> rbDuration30.isChecked = true
            -1 -> rbDurationScreenOff.isChecked = true
            else -> rbDuration5.isChecked = true
        }
        rgDuration.setOnCheckedChangeListener { _, checkedId ->
            val dur = when (checkedId) {
                R.id.rbDuration5 -> 5
                R.id.rbDuration10 -> 10
                R.id.rbDuration30 -> 30
                R.id.rbDurationScreenOff -> -1
                else -> 5
            }
            prefs.unlockDurationMin = dur
            LogStore.append(this, "해제 유지 시간 변경: ${if (dur == -1) "화면 끌 때까지" else "${dur}분"}")
            Toast.makeText(this, "해제 유지 시간 저장됨", Toast.LENGTH_SHORT).show()
        }

        refreshBaselineResult()
        loadAppList()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun isAccessibilityEnabled(): Boolean {
        val expected = ComponentName(this, LockAccessibilityService::class.java).flattenToString()
        val enabled =
            Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?: return false
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private fun isBatteryOptIgnored(): Boolean {
        val pm = getSystemService(PowerManager::class.java)
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun isMicGranted(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun refreshStatus() {
        val a11yOk = isAccessibilityEnabled()
        tvStatusA11y.text = if (a11yOk) "접근성 서비스: 활성화됨 ✓" else "접근성 서비스: 비활성화 ✗ (앱 잠금에 필요)"
        val battIgnored = isBatteryOptIgnored()
        tvStatusBattery.text =
            if (battIgnored) "배터리 최적화: 제외됨 ✓" else "배터리 최적화: 적용 중 ✗ (백그라운드에서 죽을 수 있음)"
        val micOk = isMicGranted()
        tvStatusMic.text = if (micOk) "마이크 권한: 허용됨 ✓" else "마이크 권한: 비허용 ✗ (랩 테스트에 필요)"
        refreshLog()
    }

    private fun refreshLog() {
        val text = LogStore.read(this)
        tvLog.text = text.ifEmpty { "로그가 없습니다" }
            .lines().asReversed().joinToString("\n")
    }

    private fun refreshBaselineResult() {
        val base = prefs.baselineReactionMs
        tvBaselineResult.text = if (base > 0f) {
            "기준 평균 반응시간: ${base.toLong()}ms (${prefs.baselineCount}회 측정). 통과 기준: ${ (base * 1.4).toLong() }ms 이하"
        } else {
            "아직 기준을 측정하지 않았습니다. (없으면 고정 기준 700ms 사용)"
        }
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        Toast.makeText(this, "설치된 앱에서 '맑은잠금'을 켜주세요", Toast.LENGTH_LONG).show()
    }

    private fun requestIgnoreBatteryOptimizations() {
        val intent = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:$packageName")
        )
        startActivity(intent)
    }

    private fun requestMicPermission() {
        ActivityCompat.requestPermissions(
            this, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORD_AUDIO) {
            refreshStatus()
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "마이크 권한 허용됨", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 설치된 실행 가능 앱 목록을 체크박스로 표시한다 */
    private fun loadAppList() {
        llAppList.removeAllViews()
        val pm = packageManager
        val locked = prefs.lockedApps.toMutableSet()
        val apps = try {
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { ai ->
                    val pkg = ai.packageName
                    pkg != packageName && pkg !in NON_LOCKABLE &&
                        pm.getLaunchIntentForPackage(pkg) != null
                }
                .sortedBy { pm.getApplicationLabel(it).toString() }
        } catch (_: Exception) {
            emptyList()
        }
        if (apps.isEmpty()) {
            val tv = TextView(this).apply { text = "앱 목록을 불러올 수 없습니다." }
            llAppList.addView(tv)
            return
        }
        for (ai in apps) {
            val pkg = ai.packageName
            val label = pm.getApplicationLabel(ai).toString()
            val cb = CheckBox(this).apply {
                text = "$label ($pkg)"
                isChecked = pkg in locked
                setOnCheckedChangeListener { _, checked ->
                    val updated = prefs.lockedApps.toMutableSet()
                    if (checked) updated.add(pkg) else updated.remove(pkg)
                    prefs.lockedApps = updated
                    LogStore.append(this@MainActivity, "잠글 앱 변경: $label ${if (checked) "추가" else "제거"}")
                }
            }
            llAppList.addView(cb)
        }
    }

    /** 점 테스트 기준 측정: 멀쩡할 때 3회, 각 10초 */
    private fun startBaselineMeasurement() {
        if (baselineRunning) return
        baselineRunning = true
        baselineRuns = 0
        baselineAvgs.clear()
        btnBaseline.isEnabled = false
        tvBaselineResult.text = "기준 측정 중… 1/3회"
        runBaselineOnce()
    }

    private fun runBaselineOnce() {
        dotTestViewBaseline.post {
            dotTestViewBaseline.startTest { results ->
                val hits = results.filter { it.hit }
                val avg = if (hits.isNotEmpty()) hits.map { it.reactionMs }.average() else Double.MAX_VALUE
                baselineRuns++
                if (avg != Double.MAX_VALUE) baselineAvgs.add(avg)
                if (baselineRuns < 3) {
                    tvBaselineResult.text = "기준 측정 중… ${baselineRuns + 1}/3회 (이전 평균 ${avg.toLong()}ms)"
                    Toast.makeText(this, "${baselineRuns}/3회 완료. 다음 측정을 시작합니다.", Toast.LENGTH_SHORT).show()
                    runBaselineOnce()
                } else {
                    baselineRunning = false
                    btnBaseline.isEnabled = true
                    if (baselineAvgs.isNotEmpty()) {
                        val finalAvg = baselineAvgs.average().toFloat()
                        prefs.baselineReactionMs = finalAvg
                        prefs.baselineCount = baselineAvgs.size
                        LogStore.append(this, "점 테스트 기준 측정 완료: 평균 ${finalAvg.toLong()}ms (${baselineAvgs.size}회)")
                        Toast.makeText(this, "기준 측정 완료: 평균 ${finalAvg.toLong()}ms", Toast.LENGTH_LONG).show()
                    } else {
                        tvBaselineResult.text = "기준 측정에 실패했습니다. 다시 시도하세요."
                        Toast.makeText(this, "기준 측정에 실패했습니다.", Toast.LENGTH_LONG).show()
                    }
                    refreshBaselineResult()
                }
            }
        }
    }
}
