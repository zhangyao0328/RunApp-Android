package com.runpulse.app.coach

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * 教练语音播报（TTS）极简封装。
 * - TextToSpeech 必须在有 Looper 的线程上初始化，因此统一切到主线程创建；
 * - 初始化完成前到达的文本进入队列，初始化成功后一次性播完；
 * - 懒初始化：第一次 speak 时才创建引擎，平时零开销。
 */
class CoachSpeaker private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()

    private var tts: TextToSpeech? = null
    private var ready = false
    private val pendingQueue = ArrayDeque<String>()

    fun speak(text: String) {
        synchronized(lock) {
            val engine = tts
            if (engine != null && ready) {
                speakInternal(engine, text)
            } else {
                pendingQueue.addLast(text)
                if (engine == null) initEngine()
            }
        }
    }

    private fun initEngine() {
        mainHandler.post {
            // OnInitListener 回调发生在构造返回之后，用 lateinit 局部变量让闭包安全捕获引擎实例
            lateinit var engine: TextToSpeech
            engine = TextToSpeech(appContext) { status ->
                synchronized(lock) {
                    ready = status == TextToSpeech.SUCCESS
                    if (ready) {
                        engine.setLanguage(Locale.CHINESE)
                        while (pendingQueue.isNotEmpty()) {
                            speakInternal(engine, pendingQueue.removeFirst())
                        }
                    } else {
                        Log.w(TAG, "TTS 引擎初始化失败，status=$status")
                    }
                }
            }
            synchronized(lock) { tts = engine }
        }
    }

    private fun speakInternal(engine: TextToSpeech, text: String) {
        // QUEUE_ADD：多条教练点评依次播报，不互相打断
        engine.speak(text, TextToSpeech.QUEUE_ADD, Bundle(), "coach_${System.currentTimeMillis()}")
    }

    fun shutdown() {
        synchronized(lock) {
            tts?.stop()
            tts?.shutdown()
            tts = null
            ready = false
        }
    }

    companion object {
        private const val TAG = "CoachSpeaker"

        @Volatile
        private var instance: CoachSpeaker? = null

        fun get(context: Context): CoachSpeaker {
            return instance ?: synchronized(this) {
                instance ?: CoachSpeaker(context).also { instance = it }
            }
        }
    }
}
