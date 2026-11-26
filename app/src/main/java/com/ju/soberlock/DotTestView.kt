package com.ju.soberlock

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.random.Random

/** 점 하나에 대한 결과 */
data class DotResult(
    val reactionMs: Long,
    val hit: Boolean,
    val distancePx: Float
)

/**
 * 점 따라가기 + 반응속도 테스트용 커스텀 뷰.
 *
 * 10초 고정, 랜덤 위치에 점 5-6개. 점이 뜨자마자 터치하면 반응시간과 정확도를 잰다.
 * 손떨림 진폭을 재는 것이 아니라 목표 추적 오차(터치 정확도)와 반응시간을 재는 테스트다.
 */
class DotTestView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        /** 테스트 시간 10초 고정 (20초 금지) */
        const val TEST_DURATION_MS = 10_000L

        /** 점 최대 개수 */
        const val MAX_DOTS = 6

        /** 점 하나가 떠 있는 최대 시간 (이 안에 못 누르면 미스 처리하고 다음 점) */
        const val DOT_TIMEOUT_MS = 1_800L

        /** 고정 통과 기준: 평균 반응 700ms 이하 */
        const val PASS_AVG_REACTION_MS = 700L

        /** 고정 통과 기준: 적중률 70% 이상 */
        const val PASS_HIT_RATE = 0.70
    }

    private val handler = Handler(Looper.getMainLooper())
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1976D2") }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1976D2")
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private var dotX = -1f
    private var dotY = -1f
    private var dotRadiusPx = 90f
    private var dotShownAt = 0L
    private var isRunning = false
    private var results = mutableListOf<DotResult>()
    private var dotsShown = 0
    private var onFinish: ((List<DotResult>) -> Unit)? = null

    private val timeoutRunnable = Runnable {
        if (!isRunning) return@Runnable
        // 시간 안에 못 누르면 미스로 기록하고 다음 점
        recordResult(hit = false, reactionMs = DOT_TIMEOUT_MS, distancePx = Float.MAX_VALUE)
        showNextDotOrFinish()
    }

    private val finishRunnable = Runnable {
        finishTest()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        dotRadiusPx = (minOf(w, h) * 0.09f).coerceAtLeast(60f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (isRunning && dotX >= 0 && dotY >= 0) {
            canvas.drawCircle(dotX, dotY, dotRadiusPx * 1.6f, ringPaint)
            canvas.drawCircle(dotX, dotY, dotRadiusPx, dotPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isRunning || event.action != MotionEvent.ACTION_DOWN) return true
        if (dotX < 0 || dotY < 0) return true
        val dx = event.x - dotX
        val dy = event.y - dotY
        val dist = hypot(dx, dy)
        val hit = dist <= dotRadiusPx * 1.5f
        val reaction = System.currentTimeMillis() - dotShownAt
        handler.removeCallbacks(timeoutRunnable)
        recordResult(hit = hit, reactionMs = reaction, distancePx = dist)
        showNextDotOrFinish()
        return true
    }

    /** 테스트 시작. 끝나면 onFinish(results) 호출 */
    fun startTest(onFinish: (List<DotResult>) -> Unit) {
        this.onFinish = onFinish
        results = mutableListOf()
        dotsShown = 0
        isRunning = true
        handler.removeCallbacks(timeoutRunnable)
        handler.removeCallbacks(finishRunnable)
        handler.postDelayed(finishRunnable, TEST_DURATION_MS)
        showNextDotOrFinish()
    }

    fun stopTest() {
        isRunning = false
        handler.removeCallbacks(timeoutRunnable)
        handler.removeCallbacks(finishRunnable)
        dotX = -1f
        dotY = -1f
        invalidate()
    }

    private fun recordResult(hit: Boolean, reactionMs: Long, distancePx: Float) {
        results.add(DotResult(reactionMs = reactionMs, hit = hit, distancePx = distancePx))
    }

    private fun showNextDotOrFinish() {
        if (!isRunning) return
        if (dotsShown >= MAX_DOTS) {
            finishTest()
            return
        }
        // 남은 시간은 finishRunnable이 처리한다
        if (width <= 0 || height <= 0) {
            // 레이아웃이 아직 안 됐으면 잠시 뒤 다시 시도
            handler.postDelayed({ if (isRunning) showNextDotOrFinish() }, 100)
            return
        }
        val margin = dotRadiusPx * 2f
        dotX = Random.nextFloat() * (width - margin * 2) + margin
        dotY = Random.nextFloat() * (height - margin * 2) + margin
        dotShownAt = System.currentTimeMillis()
        dotsShown++
        invalidate()
        handler.removeCallbacks(timeoutRunnable)
        handler.postDelayed(timeoutRunnable, DOT_TIMEOUT_MS)
    }

    private fun finishTest() {
        if (!isRunning) return
        isRunning = false
        handler.removeCallbacks(timeoutRunnable)
        handler.removeCallbacks(finishRunnable)
        dotX = -1f
        dotY = -1f
        invalidate()
        onFinish?.invoke(results.toList())
        onFinish = null
    }

    /** 결과 판정: 고정 기준 또는 기준 측정값(+40% 여유) 중 하나라도 만족하면 통과 */
    companion object {
        fun evaluate(results: List<DotResult>, baselineReactionMs: Float): Pair<Boolean, String> {
            if (results.isEmpty()) return false to "점이 하나도 기록되지 않았습니다"
            val hits = results.filter { it.hit }
            val hitRate = hits.size.toDouble() / results.size
            val avgReaction = if (hits.isNotEmpty()) hits.map { it.reactionMs }.average() else Double.MAX_VALUE
            val thresholdMs = if (baselineReactionMs > 0f) {
                baselineReactionMs * 1.4
            } else {
                PASS_AVG_REACTION_MS.toDouble()
            }
            val pass = avgReaction <= thresholdMs && hitRate >= PASS_HIT_RATE
            val avgText = if (hits.isEmpty()) "측정 불가" else "${avgReaction.toLong()}ms"
            val detail = "평균 반응 $avgText (기준 ${thresholdMs.toLong()}ms 이하) · 적중률 ${(hitRate * 100).toInt()}% (70% 이상)"
            return pass to detail
        }
    }
}
