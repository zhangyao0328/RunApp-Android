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
 * 步频检测器：优先使用系统步数传感器，加速度计作为备选
 * 
 * 系统步数传感器（TYPE_STEP_DETECTOR）：
 * - 由硬件/系统级算法检测步伐，准确率高、功耗低
 * - 每检测到一步触发一次回调
 * 
 * 加速度计备选方案：
 * - 当设备不支持步数传感器时使用
 * - 基于峰值检测算法
 */
class CadenceDetector(private val context: Context) : SensorEventListener {

    companion object {
        private const val TAG = "CadenceDetector"
        
        /** 最小步间隔（毫秒）：对应最大 240 SPM */
        private const val MIN_STEP_INTERVAL_MS = 250L
        
        /** 滑动窗口时长（毫秒）：用于计算实时步频 */
        private const val WINDOW_SIZE_MS = 10_000L
        
        /** 初始检测阈值（m/s²） */
        private const val INITIAL_THRESHOLD = 10.5f
        
        /** 低通滤波系数（0-1，越小越平滑） */
        private const val LOW_PASS_ALPHA = 0.15f
        
        /** 动态阈值调整系数 */
        private const val THRESHOLD_ADJUST_FACTOR = 0.3f
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    
    // 优先使用系统步数传感器
    private val stepDetector = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    
    /** 是否使用系统步数传感器 */
    private var useStepDetector = false

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
    
    // 加速度计滤波与阈值
    private var filteredMagnitude = 0f
    private var dynamicThreshold = INITIAL_THRESHOLD
    private var lastMagnitude = 0f

    /** 开始监听 */
    fun startListening() {
        if (isListening) return
        
        // 重置状态
        stepCount = 0
        stepTimes.clear()
        lastStepTime = 0L
        dynamicThreshold = INITIAL_THRESHOLD
        _currentCadence.value = 0
        _totalSteps.value = 0
        
        // 优先使用系统步数传感器
        if (stepDetector != null) {
            useStepDetector = true
            sensorManager.registerListener(
                this,
                stepDetector,
                SensorManager.SENSOR_DELAY_NORMAL
            )
            isListening = true
            Log.d(TAG, "步频检测已启动（系统步数传感器）")
            return
        }
        
        // 备选：加速度计
        if (accelerometer != null) {
            useStepDetector = false
            sensorManager.registerListener(
                this,
                accelerometer,
                SensorManager.SENSOR_DELAY_GAME
            )
            isListening = true
            Log.d(TAG, "步频检测已启动（加速度计）")
            return
        }
        
        Log.e(TAG, "设备不支持步频检测（无步数传感器和加速度计）")
    }

    /** 停止监听 */
    fun stopListening() {
        if (!isListening) return
        sensorManager.unregisterListener(this)
        isListening = false
        Log.d(TAG, "步频检测已停止，总步数：$stepCount")
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        
        when (event.sensor.type) {
            Sensor.TYPE_STEP_DETECTOR -> {
                // 系统步数传感器：每次回调代表检测到一步
                onStepDetected()
            }
            Sensor.TYPE_ACCELEROMETER -> {
                // 加速度计：需要自己做峰值检测
                onAccelerometerData(event)
            }
        }
    }
    
    /** 系统步数传感器回调：检测到一步 */
    private fun onStepDetected() {
        val now = System.currentTimeMillis()
        
        // 检查最小步间隔（防止误触发）
        if (now - lastStepTime < MIN_STEP_INTERVAL_MS) return
        
        stepCount++
        _totalSteps.value = stepCount
        stepTimes.add(now)
        lastStepTime = now
        
        // 保留滑动窗口内的数据
        stepTimes.removeAll { now - it > WINDOW_SIZE_MS }
        
        // 计算实时步频
        updateCurrentCadence()
        
        Log.d(TAG, "检测到步伐! 总步数=$stepCount, 当前步频=${_currentCadence.value}")
    }
    
    /** 加速度计数据处理 */
    private fun onAccelerometerData(event: SensorEvent) {
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        
        // 计算合加速度（包含重力）
        val magnitude = sqrt(x * x + y * y + z * z)
        
        // 低通滤波平滑数据
        filteredMagnitude = filteredMagnitude + LOW_PASS_ALPHA * (magnitude - filteredMagnitude)
        
        // 峰值检测：检测上升沿穿越阈值
        val isAboveThreshold = filteredMagnitude > dynamicThreshold
        val wasAboveThreshold = lastMagnitude > dynamicThreshold
        
        if (isAboveThreshold && !wasAboveThreshold) {
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
                
                Log.d(TAG, "检测到步伐(加速度计)! 总步数=$stepCount, 当前步频=${_currentCadence.value}")
                
                // 动态调整阈值
                adjustThreshold(filteredMagnitude)
            }
        }
        
        lastMagnitude = filteredMagnitude
    }

    /**
     * 动态调整阈值：根据历史峰值自动调整灵敏度
     */
    private fun adjustThreshold(peakMagnitude: Float) {
        dynamicThreshold = dynamicThreshold * (1 - THRESHOLD_ADJUST_FACTOR) + 
                          peakMagnitude * THRESHOLD_ADJUST_FACTOR
        dynamicThreshold = dynamicThreshold.coerceIn(10.0f, 13.0f)
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
