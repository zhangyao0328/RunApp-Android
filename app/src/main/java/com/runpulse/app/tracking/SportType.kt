package com.runpulse.app.tracking

/**
 * 运动类型枚举，value 与云端 API 契约严格一致。游泳明确不做。
 */
enum class SportType(val value: String) {
    RUN("run"),
    TRAIL("trail"),
    BIKE("bike");

    companion object {
        fun fromValue(value: String?): SportType {
            return entries.firstOrNull { it.value == value } ?: RUN
        }
    }
}
