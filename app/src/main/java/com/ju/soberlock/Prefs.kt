package com.ju.soberlock

import android.content.Context
import androidx.core.content.edit

/** 앱 설정 (SharedPreferences 래퍼) */
class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("sober_lock", Context.MODE_PRIVATE)

    /** 잠글 앱 패키지명 집합 (사용자가 설정에서 직접 선택) */
    var lockedApps: Set<String>
        get() = sp.getStringSet("locked_apps", emptySet()) ?: emptySet()
        set(v) = sp.edit { putStringSet("locked_apps", v) }

    /**
     * 해제 유지 시간 (분).
     * 5 / 10 / 30 / -1(화면 끌 때까지). 기본 5분.
     */
    var unlockDurationMin: Int
        get() = sp.getInt("unlock_duration_min", 5)
        set(v) = sp.edit { putInt("unlock_duration_min", v) }

    /** 해제 유효 시각 (epoch ms). 0 = 해제 상태 아님. 화면 끌 때까지 모드면 Long.MAX_VALUE 사용 */
    var unlockUntil: Long
        get() = sp.getLong("unlock_until", 0L)
        set(v) = sp.edit { putLong("unlock_until", v) }

    /** 긴급 해제 유효 시각 (epoch ms). 0 = 긴급 해제 상태 아님 */
    var emergencyUnlockUntil: Long
        get() = sp.getLong("emergency_unlock_until", 0L)
        set(v) = sp.edit { putLong("emergency_unlock_until", v) }

    /** 실패 쿨다운 종료 시각 (epoch ms). 0 = 쿨다운 없음 */
    var cooldownUntil: Long
        get() = sp.getLong("cooldown_until", 0L)
        set(v) = sp.edit { putLong("cooldown_until", v) }

    /** 점 테스트 기준 평균 반응시간 (ms). 0 = 아직 측정 안 함 */
    var baselineReactionMs: Float
        get() = sp.getFloat("baseline_reaction_ms", 0f)
        set(v) = sp.edit { putFloat("baseline_reaction_ms", v) }

    /** 기준 측정에 사용한 횟수 (표시용) */
    var baselineCount: Int
        get() = sp.getInt("baseline_count", 0)
        set(v) = sp.edit { putInt("baseline_count", v) }

    fun isUnlocked(now: Long): Boolean =
        now < unlockUntil || now < emergencyUnlockUntil

    fun isEmergencyUnlocked(now: Long): Boolean = now < emergencyUnlockUntil

    /** 화면 꺼짐 시: 화면 끌 때까지 모드면 일반 해제를 초기화한다 */
    fun clearUnlockOnScreenOff() {
        if (unlockDurationMin == -1) {
            unlockUntil = 0L
        }
    }
}
