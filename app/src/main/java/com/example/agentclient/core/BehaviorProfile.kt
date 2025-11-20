package com.example.agentclient.core

import com.google.gson.Gson

/**
 * 行為設定プロファイル - 強化版
 * 異なる「人物設定」の操作特性を定義し、リスク管理と擬人化パラメータを含む
 */
data class BehaviorProfile(
    val name: String,
    val description: String = "",

    // --- 基本リズムパラメータ (ミリ秒) ---
    // タップ間隔範囲
    val minTapIntervalMs: Long = 500,
    val maxTapIntervalMs: Long = 3000,

    // スワイプ持続時間範囲
    val minSwipeDurationMs: Long = 300,
    val maxSwipeDurationMs: Long = 1000,

    // ---

    // 擬人化詳細 ---
    // 位置オフセット（ピクセル）
    val positionOffsetPx: Int = 10,

    // 誤タップオフセット（ピクセル）
    val misTapOffsetPx: Int = 50,

    // 躊躇行動（操作前の突然の停止）
    val hesitateProbability: Float = 0.1f,
    val hesitateMinMs: Long = 500,
    val hesitateMaxMs: Long = 2000,

    // 思考時間（一連の操作前の長い停止）
    val thinkingMinMs: Long = 1000,
    val thinkingMaxMs: Long = 5000,

    // 誤操作確率
    val misTapProbability: Float = 0.02f,

    // 読み取り速度（文字/秒）
    val readingSpeedCharsPerSec: Float = 10f,
    val readingSpeedJitter: Float = 0.2f,

    // --- リスク管理戦略 ---
    // 1回の放置最大時間（分）、0は無制限
    val sessionMaxDurationMinutes: Int = 120,

    // 夜間禁止時間帯（24時間制、例：23から7）
    // startHour = -1 は無効
    val nightOffStartHour: Int = -1,
    val nightOffEndHour: Int = -1,

    // 許容連続エラー回数（これを超えると停止または警告）
    val errorTolerance: Int = 10,

    // 思考時間トリガー確率
    val thinkingProbability: Float = 0.05f,

    // --- Phase 4 新規パラメータ ---
    // ステップ間最小間隔
    val minStepInterval: Long = 1000,
    // ステップ間最大間隔
    val maxStepInterval: Long = 3000,
    // エラーリトライ間隔
    val errorRetryInterval: Long = 5000,
    // 長時間休憩最小間隔（ミリ秒）
    val longRestIntervalMin: Long = 60000,
    // 長時間休憩最大間隔（ミリ秒）
    val longRestIntervalMax: Long = 300000
) {
    /**
     * 正規化された設定のコピーを返す
     * すべてのパラメータが妥当な範囲内にあることを保証し、誤った設定による異常を防ぐ
     */
    fun normalized(): BehaviorProfile {
        // 1. 確率フィールドを [0, 1] に制約
        val safeHesitateProb = hesitateProbability.coerceIn(0f, 1f)
        val safeMisTapProb = misTapProbability.coerceIn(0f, 1f)
        val safeReadingJitter = readingSpeedJitter.coerceIn(0f, 1f)
        val safeThinkingProb = thinkingProbability.coerceIn(0f, 1f)

        // 2. 時間間隔制約（min >= 0, max >= min）
        val safeMinTap = minTapIntervalMs.coerceAtLeast(0)
        val safeMaxTap = maxTapIntervalMs.coerceAtLeast(safeMinTap)

        val safeMinSwipe = minSwipeDurationMs.coerceAtLeast(0)
        val safeMaxSwipe = maxSwipeDurationMs.coerceAtLeast(safeMinSwipe)

        val safeHesitateMin = hesitateMinMs.coerceAtLeast(0)
        val safeHesitateMax = hesitateMaxMs.coerceAtLeast(safeHesitateMin)

        val safeThinkingMin = thinkingMinMs.coerceAtLeast(0)
        val safeThinkingMax = thinkingMaxMs.coerceAtLeast(safeThinkingMin)

        val safeMinStep = minStepInterval.coerceAtLeast(0)
        val safeMaxStep = maxStepInterval.coerceAtLeast(safeMinStep)

        val safeRestMin = longRestIntervalMin.coerceAtLeast(0)
        val safeRestMax = longRestIntervalMax.coerceAtLeast(safeRestMin)

        // 3. その他の制約
        val safeSessionMax = if (sessionMaxDurationMinutes < 0) 0 else sessionMaxDurationMinutes
        val safeErrorTolerance = errorTolerance.coerceAtLeast(1)
        val safePositionOffset = positionOffsetPx.coerceAtLeast(0)
        val safeMisTapOffset = misTapOffsetPx.coerceAtLeast(0)
        val safeErrorRetry = errorRetryInterval.coerceAtLeast(0)

        return copy(
            hesitateProbability = safeHesitateProb,
            misTapProbability = safeMisTapProb,
            readingSpeedJitter = safeReadingJitter,
            thinkingProbability = safeThinkingProb,
            minTapIntervalMs = safeMinTap,
            maxTapIntervalMs = safeMaxTap,
            minSwipeDurationMs = safeMinSwipe,
            maxSwipeDurationMs = safeMaxSwipe,
            hesitateMinMs = safeHesitateMin,
            hesitateMaxMs = safeHesitateMax,
            thinkingMinMs = safeThinkingMin,
            thinkingMaxMs = safeThinkingMax,
            sessionMaxDurationMinutes = safeSessionMax,
            errorTolerance = safeErrorTolerance,
            positionOffsetPx = safePositionOffset,
            misTapOffsetPx = safeMisTapOffset,
            minStepInterval = safeMinStep,
            maxStepInterval = safeMaxStep,
            errorRetryInterval = safeErrorRetry,
            longRestIntervalMin = safeRestMin,
            longRestIntervalMax = safeRestMax
        )
    }

    companion object {
        // 事前定義された行動設定

        val FAST_YOUNG = BehaviorProfile(
            name = "FAST_YOUNG",
            description = "若者の高速操作型",
            minTapIntervalMs = 200,
            maxTapIntervalMs = 1500,
            minSwipeDurationMs = 200,
            maxSwipeDurationMs = 600,
            positionOffsetPx = 8,
            misTapOffsetPx = 40,
            hesitateProbability = 0.05f,
            hesitateMinMs = 300,
            hesitateMaxMs = 1000,
            thinkingMinMs = 500,
            thinkingMaxMs = 2000,
            misTapProbability = 0.01f,
            readingSpeedCharsPerSec = 20f,
            sessionMaxDurationMinutes = 180,
            errorTolerance = 15,
            thinkingProbability = 0.03f,
            minStepInterval = 800,
            maxStepInterval = 2000
        )

        val SLOW_CAREFUL = BehaviorProfile(
            name = "SLOW_CAREFUL",
            description = "低速慎重型",
            minTapIntervalMs = 800,
            maxTapIntervalMs = 4000,
            minSwipeDurationMs = 500,
            maxSwipeDurationMs = 1200,
            positionOffsetPx = 5,
            misTapOffsetPx = 30,
            hesitateProbability = 0.25f,
            hesitateMinMs = 1000,
            hesitateMaxMs = 3000,
            thinkingMinMs = 2000,
            thinkingMaxMs = 8000,
            misTapProbability = 0.005f,
            readingSpeedCharsPerSec = 5f,
            sessionMaxDurationMinutes = 90,
            errorTolerance = 5,
            thinkingProbability = 0.1f,
            minStepInterval = 1500,
            maxStepInterval = 4000
        )

        val DEFAULT = FAST_YOUNG

        // すべての事前定義設定を取得
        fun getAllProfiles(): List<BehaviorProfile> {
            return listOf(FAST_YOUNG, SLOW_CAREFUL)
        }

        // 名前で設定を取得
        fun getByName(name: String): BehaviorProfile {
            return getAllProfiles().find { it.name == name } ?: DEFAULT
        }

        // JSONから解析
        fun fromJson(json: String): BehaviorProfile? {
            return try {
                Gson().fromJson(json, BehaviorProfile::class.java)?.normalized()
            } catch (e: Exception) {
                null
            }
        }
        
        // Mapから解析（Config更新用）
        fun fromMap(map: Map<String, Any>): BehaviorProfile? {
            return try {
                val gson = Gson()
                val json = gson.toJson(map)
                gson.fromJson(json, BehaviorProfile::class.java)?.normalized()
            } catch (e: Exception) {
                null
            }
        }
    }
}