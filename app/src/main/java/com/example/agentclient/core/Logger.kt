package com.example.agentclient.core

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 統一ログシステム
 * コンソール出力・ファイル保存・エラー件数の計測を担当する
 */
class Logger private constructor(private val context: Context) {

    /**
     * ログレベル定義
     */
    enum class Level(val value: Int) {
        DEBUG(1),
        INFO(2),
        WARN(3),
        ERROR(4),
        FATAL(5)
    }

    // 非同期書き込み用キュー
    private val logQueue = ConcurrentLinkedQueue<LogEntry>()

    // ログ書き込み専用スレッド
    private val executor = Executors.newSingleThreadExecutor()

    // 日付フォーマッタ
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    // エラー件数カウンタ（10分ウィンドウ内で使用）
    private val scriptErrorCount = AtomicInteger(0)
    private val networkErrorCount = AtomicInteger(0)
    private val accessibilityErrorCount = AtomicInteger(0)

    // 最後のエラー発生時刻（ミリ秒）
    private val lastErrorTime = AtomicLong(0L)

    // エラー件数ウィンドウの開始時刻（ミリ秒）
    private val windowStartTime = AtomicLong(System.currentTimeMillis())

    // 設定値
    private var minLevel: Level = Level.DEBUG
    private var enableFileLog: Boolean = true
    private var currentLogFile: File? = null
    private var currentLogSize: Long = 0

    // ログ書き込みループ制御フラグ
    @Volatile
    private var isRunning: Boolean = true

    companion object {
        private const val TAG = "AgentClient"
        private const val LOG_DIR = "logs"
        private const val MAX_LOG_FILE_SIZE = 5 * 1024 * 1024L // 5MB
        private const val MAX_LOG_FILES = 10
        private const val ERROR_COUNT_WINDOW = 10 * 60 * 1000L // 10分

        @Volatile
        private var instance: Logger? = null

        fun getInstance(context: Context): Logger {
            return instance ?: synchronized(this) {
                instance ?: Logger(context.applicationContext).also {
                    instance = it
                    it.init()
                }
            }
        }
    }

    /**
     * ログエントリ構造体
     */
    data class LogEntry(
        val level: Level,
        val tag: String,
        val message: String,
        val timestamp: Long = System.currentTimeMillis(),
        val throwable: Throwable? = null
    )

    /**
     * エラー統計情報
     */
    data class ErrorStats(
        val scriptErrors: Int,
        val networkErrors: Int,
        val accessibilityErrors: Int,
        val lastErrorTime: Long
    )

    /**
     * 初期化処理
     * ログディレクトリ作成・古いログ削除・ファイルローテーション・書き込みスレッド起動
     */
    private fun init() {
        // ログディレクトリを作成
        val logDir = File(context.filesDir, LOG_DIR)
        if (!logDir.exists()) {
            logDir.mkdirs()
        }

        // 古いログファイルをクリーンアップ
        cleanOldLogs(logDir)

        // 現在のログファイルを準備
        rotateLogFile()

        // 非同期書き込みスレッドを開始
        startLogWriter()
    }

    fun debug(tag: String, message: String, throwable: Throwable? = null) {
        log(Level.DEBUG, tag, message, throwable)
    }

    fun info(tag: String, message: String, throwable: Throwable? = null) {
        log(Level.INFO, tag, message, throwable)
    }

    fun warn(tag: String, message: String, throwable: Throwable? = null) {
        log(Level.WARN, tag, message, throwable)
    }

    fun error(tag: String, message: String, throwable: Throwable? = null) {
        log(Level.ERROR, tag, message, throwable)
        incrementErrorCount(tag)
    }

    fun fatal(tag: String, message: String, throwable: Throwable? = null) {
        log(Level.FATAL, tag, message, throwable)
        incrementErrorCount(tag)
    }

    /**
     * ログ出力の共通処理
     */
    private fun log(level: Level, tag: String, message: String, throwable: Throwable?) {
        // 最小レベル以下は無視
        if (level.value < minLevel.value) return

        val entry = LogEntry(level, tag, message, throwable = throwable)

        // Logcat へ出力
        when (level) {
            Level.DEBUG -> Log.d("$TAG:$tag", message, throwable)
            Level.INFO  -> Log.i("$TAG:$tag", message, throwable)
            Level.WARN  -> Log.w("$TAG:$tag", message, throwable)
            Level.ERROR -> Log.e("$TAG:$tag", message, throwable)
            Level.FATAL -> Log.wtf("$TAG:$tag", message, throwable)
        }

        // ファイルログ有効ならキューに追加
        if (enableFileLog) {
            logQueue.offer(entry)
        }
    }

    /**
     * エラー件数カウントアップ
     * タグに応じてカテゴリ別に増加させる
     */
    private fun incrementErrorCount(tag: String) {
        when {
            tag.contains("Script", ignoreCase = true) -> scriptErrorCount.incrementAndGet()
            tag.contains("Network", ignoreCase = true) -> networkErrorCount.incrementAndGet()
            tag.contains("Accessibility", ignoreCase = true) -> accessibilityErrorCount.incrementAndGet()
        }

        // 最終エラー時刻を更新
        val now = System.currentTimeMillis()
        lastErrorTime.set(now)

        // 閾値チェック
        checkErrorThreshold(now)
    }

    /**
     * エラー件数の閾値チェック（10分ウィンドウ）
     */
    private fun checkErrorThreshold(now: Long) {
        val windowStart = windowStartTime.get()

        // ウィンドウを超えた場合はリセット
        if (now - windowStart > ERROR_COUNT_WINDOW) {
            windowStartTime.set(now)
            scriptErrorCount.set(0)
            networkErrorCount.set(0)
            accessibilityErrorCount.set(0)
            return
        }

        val total = scriptErrorCount.get() + networkErrorCount.get() + accessibilityErrorCount.get()
        if (total > 50) {
            // 10分以内に50件以上のエラーが発生した場合
            warn(
                "Logger",
                "High error rate detected: $total errors in last ${ERROR_COUNT_WINDOW / 1000}s"
            )
            // TODO: ここで ScriptEngine 等へ通知し、実行頻度を下げるなどの保護処理を呼び出す
        }
    }

    /**
     * 非同期ログ書き込みスレッド開始
     */
    private fun startLogWriter() {
        executor.execute {
            while (isRunning) {
                try {
                    val entries = mutableListOf<LogEntry>()

                    // バッチでログを取得（最大100件）
                    while (entries.size < 100) {
                        val entry = logQueue.poll() ?: break
                        entries.add(entry)
                    }

                    if (entries.isNotEmpty()) {
                        writeToFile(entries)
                    } else {
                        // ログがない場合は少し待機
                        Thread.sleep(1000)
                    }

                } catch (e: InterruptedException) {
                    // スレッド中断時はループを抜けて終了
                    Log.d(TAG, "Log writer thread interrupted")
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Error in log writer", e)
                }
            }
        }
    }

    /**
     * ログをファイルに書き込む
     */
    private fun writeToFile(entries: List<LogEntry>) {
        try {
            val file = currentLogFile ?: return

            FileWriter(file, true).use { writer ->
                entries.forEach { entry ->
                    val timestamp = dateFormat.format(Date(entry.timestamp))
                    val line = buildString {
                        append("[$timestamp] ")
                        append("[${entry.level}] ")
                        append("[${entry.tag}] ")
                        append(entry.message)
                        entry.throwable?.let {
                            append("\n")
                            append(it.stackTraceToString())
                        }
                        append("\n")
                    }
                    writer.write(line)
                    currentLogSize += line.length
                }
            }

            // ファイルサイズが上限を超えたらローテーション
            if (currentLogSize > MAX_LOG_FILE_SIZE) {
                rotateLogFile()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to write log to file", e)
        }
    }

    /**
     * ログファイルのローテーション
     */
    private fun rotateLogFile() {
        try {
            val logDir = File(context.filesDir, LOG_DIR)
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val newFile = File(logDir, "agent_$timestamp.log")

            currentLogFile = newFile
            currentLogSize = 0

            info("Logger", "Rotated to new log file: ${newFile.name}")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to rotate log file", e)
        }
    }

    /**
     * 古いログファイルの削除
     */
    private fun cleanOldLogs(logDir: File) {
        try {
            val logFiles = logDir.listFiles { file -> file.name.endsWith(".log") }
                ?.sortedByDescending { it.lastModified() }
                ?: return

            if (logFiles.size > MAX_LOG_FILES) {
                logFiles.drop(MAX_LOG_FILES).forEach { file ->
                    file.delete()
                    Log.d(TAG, "Deleted old log file: ${file.name}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clean old logs", e)
        }
    }

    /**
     * エラー統計情報を取得
     */
    fun getErrorStats(): ErrorStats {
        return ErrorStats(
            scriptErrors = scriptErrorCount.get(),
            networkErrors = networkErrorCount.get(),
            accessibilityErrors = accessibilityErrorCount.get(),
            lastErrorTime = lastErrorTime.get()
        )
    }

    /**
     * エラーカウンタをリセット
     */
    fun resetErrorCounters() {
        scriptErrorCount.set(0)
        networkErrorCount.set(0)
        accessibilityErrorCount.set(0)
        windowStartTime.set(System.currentTimeMillis())
        lastErrorTime.set(0L)
    }

    /**
     * ログ出力の最小レベルを設定
     */
    fun setMinLevel(level: Level) {
        minLevel = level
    }

    /**
     * ファイルログ有効／無効を切り替え
     */
    fun setFileLogEnabled(enabled: Boolean) {
        enableFileLog = enabled
    }

    /**
     * ログファイルをエクスポート（現状はディレクトリ参照のみ）
     * TODO: 必要に応じてZIP圧縮などの実装を追加する
     */
    fun exportLogs(): File? {
        return try {
            val logDir = File(context.filesDir, LOG_DIR)
            val exportDir = File(context.getExternalFilesDir(null), "exported_logs")
            if (!exportDir.exists()) {
                exportDir.mkdirs()
            }

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val zipFile = File(exportDir, "logs_$timestamp.zip")

            // TODO: 実際のZIP圧縮は未実装。現時点ではログディレクトリをそのまま返す。
            logDir
        } catch (e: Exception) {
            error("Logger", "Failed to export logs", e)
            null
        }
    }

    /**
     * ログシステムをシャットダウン
     * （アプリ完全終了時などに呼び出すことを想定）
     */
    fun shutdown() {
        isRunning = false
        executor.shutdownNow()
    }
}