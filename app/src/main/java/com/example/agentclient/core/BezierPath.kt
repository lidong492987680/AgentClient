package com.example.agentclient.core

import android.graphics.PointF
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * ベジェ曲線を使った軌跡生成クラス
 */
object BezierPath {

    /**
     * スワイプ用の自然な軌跡を生成する
     */
    fun generateNaturalPath(
        start: PointF,
        end: PointF,
        totalDurationMs: Long,
        pointCount: Int = 20
    ): List<PathPoint> {
        // 開始点と終了点の中間にコントロールポイントを置く
        val midX = (start.x + end.x) / 2f
        val midY = (start.y + end.y) / 2f

        // 垂直方向に少しずらしてカーブを作る
        val dx = end.x - start.x
        val dy = end.y - start.y
        val len = sqrt(dx * dx + dy * dy)
        val offset = if (len > 0f) len * 0.2f else 30f // 0.2倍くらい曲げる

        val nx = if (len > 0f) -dy / len else 0f
        val ny = if (len > 0f) dx / len else 1f

        val randomScale = (0.5f + Random.nextFloat()) // 0.5〜1.5倍
        val cx = midX + nx * offset * randomScale
        val cy = midY + ny * offset * randomScale

        val control = PointF(cx, cy)

        return generateQuadratic(start, control, end, totalDurationMs, pointCount)
    }

    /**
     * 2次ベジェ曲線
     */
    private fun generateQuadratic(
        p0: PointF,
        p1: PointF,
        p2: PointF,
        totalDurationMs: Long,
        pointCount: Int
    ): List<PathPoint> {
        val list = ArrayList<PathPoint>(pointCount + 1)
        for (i in 0..pointCount) {
            val t = i.toFloat() / pointCount.toFloat()
            val oneMinusT = 1f - t

            val x = oneMinusT.pow(2) * p0.x +
                    2f * oneMinusT * t * p1.x +
                    t.pow(2) * p2.x

            val y = oneMinusT.pow(2) * p0.y +
                    2f * oneMinusT * t * p1.y +
                    t.pow(2) * p2.y

            // イージング（最初ゆっくり→中間速い→最後ゆっくり）
            val easedT = easeInOutCubic(t)
            val timeOffset = (totalDurationMs * easedT).toLong()

            list.add(PathPoint(x, y, timeOffset))
        }
        return list
    }

    /**
     * 3次イージング関数
     */
    private fun easeInOutCubic(t: Float): Float {
        return if (t < 0.5f) {
            4f * t * t * t
        } else {
            1f - (-2f * t + 2f).pow(3) / 2f
        }
    }

    /**
     * 軌跡上の1点
     */
    data class PathPoint(
        val x: Float,
        val y: Float,
        val timeOffsetMs: Long
    )
}
