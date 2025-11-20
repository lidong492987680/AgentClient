package com.example.agentclient.core

import android.graphics.PointF
import android.util.Log

/**
 * UI操作のドライバー
 * 今はログのみ。将来的にアクセシビリティやShellに差し替える。
 */
class UiDriver {

    /**
     * 単純なタップ
     */
    fun tap(x: Float, y: Float, durationMs: Long = 50) {
        Log.i("UiDriver", "tap at ($x, $y), duration=$durationMs ms")
        // TODO: 実機用の実装をここに入れる
    }

    /**
     * 線形スワイプ
     */
    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long) {
        Log.i("UiDriver", "swipe from ($x1,$y1) → ($x2,$y2), duration=$durationMs ms")
        // TODO: 実機用の実装をここに入れる
    }

    /**
     * ベジェ軌跡に沿ったスワイプ（現在はログのみ）
     */
    fun swipeBezier(path: List<BezierPath.PathPoint>) {
        if (path.isEmpty()) return

        val start = path.first()
        val end = path.last()
        Log.i(
            "UiDriver",
            "swipeBezier from (${start.x},${start.y}) → (${end.x},${end.y}), points=${path.size}, total=${end.timeOffsetMs}ms"
        )

        // TODO:
        // 実際の実装では path の各点を使って
        // GestureDescription を組み立てて送信する。
    }
}
