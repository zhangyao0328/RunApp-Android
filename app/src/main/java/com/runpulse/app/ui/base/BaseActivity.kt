package com.runpulse.app.ui.base

import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.viewbinding.ViewBinding
import com.gyf.immersionbar.ImmersionBar

/**
 * 所有 Activity 的基类：
 * 1. 统一完成 ViewBinding 的 inflate 与 setContentView；
 * 2. 用 ImmersionBar 做真全屏沉浸：状态栏/导航栏透明、内容延伸到刘海区域，
 *    状态栏图标深浅由 [statusBarDarkFont] 决定（深色背景页应覆写返回 false）；
 * 3. 自动维护全局 Activity 栈（见 [ActivityStack]）。
 *
 * 页面内的可交互 UI（按钮、卡片、列表）应调用 [fitSystemBars] 避开状态栏/刘海/虚拟键，
 * 纯展示的背景层（地图、渐变遮罩）保持全屏即可。
 *
 * 用法：
 * ```kotlin
 * class MainActivity : BaseActivity<ActivityMainBinding>(ActivityMainBinding::inflate)
 * ```
 */
abstract class BaseActivity<VB : ViewBinding>(
    private val inflate: (LayoutInflater) -> VB
) : AppCompatActivity() {

    protected lateinit var binding: VB
        private set

    /**
     * 状态栏/导航栏图标是否为深色。
     * 深色为主的全局主题下默认 false（浅色图标）；若个别浅色背景页需要深色图标则覆写为 true。
     */
    protected open fun statusBarDarkFont(): Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // 刘海屏：允许内容延伸到刘海区域（API 28+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        super.onCreate(savedInstanceState)
        binding = inflate(layoutInflater)
        setContentView(binding.root)

        ImmersionBar.with(this)
            .transparentBar() // 状态栏 + 导航栏全透明，内容真全屏
            .statusBarDarkFont(statusBarDarkFont())
            .navigationBarDarkIcon(statusBarDarkFont())
            .init()

        ActivityStack.push(this)
    }

    /**
     * 带统一淡入淡出转场的页面跳转（150–300ms 区间内的平台过渡）
     */
    protected fun startActivityWithAnim(intent: android.content.Intent) {
        startActivity(intent)
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    override fun onDestroy() {
        ActivityStack.pop(this)
        super.onDestroy()
    }

    /**
     * 让交互 UI 避开系统栏与刘海：
     * @param top 顶部视图，额外增加「状态栏+刘海」高度的 topMargin
     * @param bottom 底部视图，额外增加「虚拟键/手势条」高度的 bottomPadding
     */
    protected fun fitSystemBars(top: View? = null, bottom: View? = null) {
        val initialTopMargin = (top?.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin ?: 0
        val initialBottomPadding = bottom?.paddingBottom ?: 0
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            top?.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = initialTopMargin + bars.top
            }
            bottom?.setPadding(
                bottom.paddingLeft, bottom.paddingTop, bottom.paddingRight,
                initialBottomPadding + bars.bottom
            )
            insets
        }
    }
}
