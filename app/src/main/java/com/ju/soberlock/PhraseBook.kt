package com.ju.soberlock

import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * 랩/발음 테스트용 랜덤 문장 목록 (12개 이상).
 * 매번 랜덤으로 하나를 뽑으므로 미리 녹음한 목소리로는 풀 수 없다.
 */
object PhraseBook {

    val phrases = listOf(
        "간장 공장 공장장은 강 공장장이고 된장 공장 공장장은 장 공장장이다",
        "내가 그린 기린 그림은 목 긴 기린 그림이고 네가 그린 기린 그림은 목 짧은 기린 그림이다",
        "경찰청 철창살은 외철창살이고 검찰청 철창살은 쌍철창살이다",
        "저기 있는 저 분은 박 법학 박사이고 여기 있는 이 분은 백 법학 박사이다",
        "서울특별시 특허허가과 허가과장 허과장",
        "작은 토끼 토끼통 통통 토끼 통통통",
        "앞집 팥죽은 붉은 팥죽이고 뒷집 콩죽은 검은 콩죽이다",
        "고려고려 고루고루 고려청자 고려자기",
        "내가 그린 구름 그림은 새털구름 그린 그림이고 네가 그린 구름 그림은 뭉게구름 그린 그림이다",
        "김 서방네 지붕 위의 콩깍지깐 콩깍지는 깐 콩깍지냐 안 깐 콩깍지냐",
        "사과를 사 과즙을 짜서 사과주스를 만들어 마셨다",
        "중앙청창살 쌍창살 외창살",
        "강낭콩깍지 콩깍지 깐 콩깍지",
        "육군 육군 육군 육군 육군 육군"
    )

    fun random(): String = phrases[Random.nextInt(phrases.size)]

    /** 공백·문장부호를 제거한 정규화 */
    fun normalize(text: String): String =
        text.replace(Regex("[\\s.,!?'\"()\\[\\]]"), "")

    /**
     * 인식 결과와 목표 문장의 일치도 (0.0~1.0).
     * 문자 편집거리 유사도와 단어 일치도 중 더 높은 값을 쓴다.
     * (음성인식이 단어를 붙이거나 띄우는 경우를 감안)
     */
    fun matchScore(target: String, recognized: String): Double {
        if (recognized.isBlank()) return 0.0
        val charScore = levenshteinSimilarity(normalize(target), normalize(recognized))
        val targetWords = target.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val recognizedWords = recognized.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (targetWords.isEmpty()) return charScore
        val matched = targetWords.count { w -> recognizedWords.any { it.contains(w) || w.contains(it) } }
        val wordScore = matched.toDouble() / targetWords.size
        return max(charScore, wordScore)
    }

    private fun levenshteinSimilarity(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val prev = IntArray(b.length + 1) { it }
        val cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = min(min(prev[j] + 1, cur[j - 1] + 1), prev[j - 1] + cost)
            }
            prev.indices.forEach { prev[it] = cur[it] }
        }
        val dist = prev[b.length]
        return 1.0 - dist.toDouble() / max(a.length, b.length)
    }
}
