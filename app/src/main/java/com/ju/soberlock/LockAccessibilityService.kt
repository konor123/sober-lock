package com.ju.soberlock

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.view.accessibility.AccessibilityEvent

/**
 * 앱 잠금용 접근성 서비스.
 * TYPE_WINDOW_STATE_CHANGED로 전경 패키지를 감지하고, 잠긴 앱이면 LockActivity를 띄워 차단한다.
 */
class LockAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: LockAccessibilityService? = null
            private set

        /** 전화/다이얼러는 잠금 대상에서 제외 (기본 제외, 잠글 수 없음) */
        val EXCLUDED_PACKAGES = setOf(
            "com.android.dialer",
            "com.google.android.dialer",
            "com.samsung.android.dialer",
            "com.android.phone",
            "com.android.server.telecom"
        )
    }

    private lateinit var prefs: Prefs
    private var lastBlockedPackage: String? = null
    private var lastBlockedAt = 0L

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                // 화면 끌 때까지 모드면 화면 꺼짐 시 해제 상태 초기화
                if (::prefs.isInitialized && prefs.unlockDurationMin == -1) {
                    prefs.unlockUntil = 0L
                    LogStore.append(this@LockAccessibilityService, "화면 꺼짐: 해제 상태 초기화")
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        prefs = Prefs(this)
        androidx.core.content.ContextCompat.registerReceiver(
            this, screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
        LogStore.append(this, "접근성 서비스 연결됨")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        try {
            unregisterReceiver(screenOffReceiver)
        } catch (_: Exception) {
        }
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return // 자기 자신은 무시
        if (pkg == "com.android.systemui") return
        if (pkg in EXCLUDED_PACKAGES) return

        if (!::prefs.isInitialized) prefs = Prefs(this)
        val locked = prefs.lockedApps
        if (pkg !in locked) return

        val now = System.currentTimeMillis()
        if (prefs.isUnlocked(now)) return

        // 같은 앱을 1초 안에 반복 차단하지 않음
        if (pkg == lastBlockedPackage && now - lastBlockedAt < 1000L) return
        lastBlockedPackage = pkg
        lastBlockedAt = now

        LogStore.append(this, "잠금 차단: $pkg")
        val intent = Intent(this, LockActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(LockActivity.EXTRA_PACKAGE, pkg)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            LogStore.append(this, "LockActivity 시작 실패: ${e.message}")
        }
    }

    override fun onInterrupt() = Unit
}
