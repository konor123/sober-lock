package com.ju.soberlock

import android.content.Context
import android.media.MediaRecorder
import java.io.File

/**
 * 잠금 해제 시작 전 1초간 주변 소음을 측정한다 (MediaRecorder getMaxAmplitude 기반).
 * 시끄러우면 랩 테스트를 강요하지 않고 점 테스트로 자동 전환을 제안하기 위함.
 *
 * getMaxAmplitude() 범위 0~32767. 조용한 실내 1000 미만, 시끄러운 술자리 4000 이상이 일반적이다.
 */
object NoiseMeter {

    /** 시끄럽다고 판단하는 진폭 임계값 */
    const val NOISY_THRESHOLD = 4000

    /**
     * 1초간 측정해 (진폭, 시끄러움 여부)를 콜백으로 돌려준다. 백그라운드 스레드에서 동작한다.
     * 녹음 파일은 캐시 파일로 만들고 측정 후 삭제한다.
     */
    fun measure(context: Context, onResult: (amplitude: Int, isNoisy: Boolean) -> Unit) {
        Thread {
            var amplitude = 0
            var recorder: MediaRecorder? = null
            val tmpFile = File(context.cacheDir, "noise_check.3gp")
            try {
                recorder = MediaRecorder().apply {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                    setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                    setOutputFile(tmpFile.absolutePath)
                    prepare()
                    start()
                }
                Thread.sleep(1000)
                amplitude = try {
                    recorder.maxAmplitude
                } catch (_: Exception) {
                    0
                }
            } catch (_: Exception) {
                amplitude = 0
            } finally {
                try {
                    recorder?.stop()
                } catch (_: Exception) {
                }
                try {
                    recorder?.release()
                } catch (_: Exception) {
                }
                try {
                    tmpFile.delete()
                } catch (_: Exception) {
                }
            }
            val noisy = amplitude >= NOISY_THRESHOLD
            onResult(amplitude, noisy)
        }.start()
    }
}
