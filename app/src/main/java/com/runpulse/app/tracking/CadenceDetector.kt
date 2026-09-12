package com.runpulse.app.tracking

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.sqrt

/**
 * 步频检测器：基于加速度计峰值检测算法
 * 
 * 算法原理：
 * 1. 计算三轴加速度的合加速度（去除重力影响）
 * 2. 使用低通滤波平滑数据
 * 3. 检测峰值（步伐产生的加速度峰值）
 * 4. 根据峰值间隔计算步频（SPM: Steps Per Minute）
 * 
 * 优化点：
 * - 动态阈值：根据历史数据自动调整检测灵敏度
 * - 最小步间隔：避免误检（最大支持 240 SPM）
 * - 滑动窗口：实时计算最近 10 秒的步频
 */
class CadenceDetector(private val context: Context) : SensorEventListener {

    companion object {
        private const val TAG = "CadenceDetector"
        
        /** 最小步间隔（毫秒）：对应最大 240 SPM */
        private const val MIN_STEP_INTERVAL_MS = 250L
        
        /** 滑动窗口时长（毫秒）：用于计算实时步频 */
        private const val WINDOW_SIZE_MS = 10_000L
        
        /** 初始检测阈值（m/s²） */
        private const val INITIAL_THRESHOLD = 11.0f
        
        /** 低通滤波系数（0-1，越小越平滑） */
        private const val LOW_PASS_ALPHA = 0.15f
        
        /** 动态阈值调整系数 */
        private const val THRESHOLD_ADJUST_FACTOR = 0.3f
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    /** 实时步频（SPM） */
    private val _currentCadence = MutableStateFlow(0)
    val currentCadence: StateFlow<Int> = _currentCadence

    /** 总步数 */
    private val _totalSteps = MutableStateFlow(0)
    val totalSteps: StateFlow<Int> = _totalSteps

    // 检测状态
    private var isListening = false
    private var lastStepTime = 0L
    private var stepCount = 0
    private val stepTimes = mutableListOf<Long>()
    
    // 滤波与阈值
    private var filteredMagnitude = 0f
    private var dynamicThreshold = INITIAL_THRESHOLD
    private var lastMagnitude = 0f
    private var isRising = false

    /** 开始监听 */
    fun startListening() {
        if (isListening) return
        
        if (accelerometer == null) {
            Log.e(TAG, "设备不支持加速度计")
            return
        }
        
        // 重置状态
        stepCount = 0
        stepTimes.clear()
        lastStepTime = 0L
        dynamicThreshold = INITIAL_THRESHOLD
        _currentCadence.value = 0
        _totalSteps.value = 0
        
        // 注册传感器监听（SENSOR_DELAY_GAME 约 20ms，适合步频检测）
        sensorManager.registerListener(
            this,
            accelerometer,
            SensorManager.SENSOR_DELAY_GAME
        )
        isListening = true
        Log.d(TAG, "步频检测已启动")
    }

    /** 停止监听 */
    fun stopListening() {
        if (!isListening) return
        sensorManager.unregisterListener(this)
        isListening = false
        Log.d(TAG, "步频检测已停止，总步数：$stepCount")
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type != Sensor.TYPE_ACCELEROMETER) return
        
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        
        // 计算合加速度（包含重力）
        val magnitude = sqrt(x * x + y * y + z * z)
        
        // 低通滤波平滑数据
        filteredMagnitude = filteredMagnitude + LOW_PASS_ALPHA * (magnitude - filteredMagnitude)
        
        // 峰值检测：检测上升沿
        val isPeak = detectPeak(filteredMagnitude)
        
        if (isPeak) {
            val now = System.currentTimeMillis()
            
            // 检查最小步间隔
            if (now - lastStepTime > MIN_STEP_INTERVAL_MS) {
                stepCount++
                _totalSteps.value = stepCount
                stepTimes.add(now)
                lastStepTime = now
                
                // 保留滑动窗口内的数据
                stepTimes.removeAll { now - it > WINDOW_SIZE_MS }
                
                // 计算实时步频
                updateCurrentCadence()
                
                // 动态调整阈值
                adjustThreshold(filteredMagnitude)
            }
        }
        
        lastMagnitude = filteredMagnitude
    }

    /**
     * 峰值检测：检测加速度的上升沿
     * 原理：步伐落地时产生加速度峰值，上升沿超过阈值即为一步
     */
    private fun detectPeak(currentMagnitude: Float): Boolean {
        val isAboveThreshold = currentMagnitude > dynamicThreshold
        val wasRising = isRising
        
        // 更新上升沿状态
        isRising = currentMagnitude > lastMagnitude
        
        // 检测：从低于阈值变为高于阈值，且处于上升沿
        return isAboveThreshold && !wasRising && isRising
    }

    /**
     * 动态调整阈值：根据历史峰值自动调整灵敏度
     * 避免不同用户/不同配速下的误检或漏检
     */
    private fun adjustThreshold(peakMagnitude: Float) {
        // 新阈值 = 当前阈值 * (1 - 系数) + 峰值 * 系数
        dynamicThreshold = dynamicThreshold * (1 - THRESHOLD_ADJUST_FACTOR) + 
                          peakMagnitude * THRESHOLD_ADJUST_FACTOR
        
        // 限制阈值范围，避免过低（误检）或过高（漏检）
        dynamicThreshold = dynamicThreshold.coerceIn(10.0f, 15.0f)
    }

    /** 更新实时步频 */
    private fun updateCurrentCadence() {
        if (stepTimes.size < 2) {
            _currentCadence.value = 0
            return
        }
        
        val windowDuration = stepTimes.last() - stepTimes.first()
        if (windowDuration <= 0) {
            _currentCadence.value = 0
            return
        }
        
        // 计算窗口内的平均步频（SPM）
        val cadence = (stepTimes.size * 60_000L / windowDuration).toInt()
        _currentCadence.value = cadence
    }

    /** 获取平均步频（整个会话） */
    fun getAverageCadence(sessionDurationMs: Long): Int {
        if (sessionDurationMs <= 0 || stepCount == 0) return 0
        return (stepCount * 60_000L / sessionDurationMs).toInt()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // 精度变化时无需处理
    }
}
