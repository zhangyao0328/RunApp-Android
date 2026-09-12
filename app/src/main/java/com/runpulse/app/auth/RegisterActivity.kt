package com.runpulse.app.auth

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.runpulse.app.MainActivity
import com.runpulse.app.R
import com.runpulse.app.databinding.ActivityRegisterBinding
import com.runpulse.app.ui.base.ActivityStack
import com.runpulse.app.ui.base.BaseActivity
import kotlinx.coroutines.launch

/**
 * 注册页：用户名 + 昵称 + 密码注册。
 * 注册成功后自动登录并进入主页。
 */
class RegisterActivity : BaseActivity<ActivityRegisterBinding>(ActivityRegisterBinding::inflate) {

    override fun statusBarDarkFont(): Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fitSystemBars(top = binding.registerContainer)

        binding.btnRegister.setOnClickListener { attemptRegister() }
        binding.btnGoLogin.setOnClickListener { finish() }
    }

    private fun attemptRegister() {
        val username = binding.etUsername.text.toString().trim()
        val displayName = binding.etDisplayName.text.toString().trim()
        val password = binding.etPassword.text.toString()
        val confirmPassword = binding.etConfirmPassword.text.toString()

        // 前端校验
        val error = validate(username, displayName, password, confirmPassword)
        if (error != null) {
            showError(error)
            return
        }

        hideError()
        setLoading(true)

        lifecycleScope.launch {
            val result = AuthManager.register(username, password, displayName)
            if (!isFinishing && !isDestroyed) {
                setLoading(false)
                result.onSuccess {
                    Toast.makeText(
                        this@RegisterActivity,
                        getString(R.string.auth_register_success, it.displayName),
                        Toast.LENGTH_SHORT
                    ).show()
                    navigateToMain()
                }.onFailure { e ->
                    showError(e.message ?: getString(R.string.auth_error_register_failed))
                }
            }
        }
    }

    /** 表单校验，返回错误信息或 null（通过） */
    private fun validate(
        username: String,
        displayName: String,
        password: String,
        confirmPassword: String
    ): String? {
        if (username.isEmpty()) return getString(R.string.auth_error_username_empty)
        if (username.length < 3 || username.length > 20)
            return getString(R.string.auth_error_username_length)
        if (!username.matches(Regex("^[a-zA-Z0-9_]+$")))
            return getString(R.string.auth_error_username_format)
        if (displayName.isEmpty()) return getString(R.string.auth_error_display_name_empty)
        if (password.isEmpty()) return getString(R.string.auth_error_password_empty)
        if (password.length < 6 || password.length > 20)
            return getString(R.string.auth_error_password_length)
        if (password != confirmPassword)
            return getString(R.string.auth_error_password_mismatch)
        return null
    }

    private fun navigateToMain() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        ActivityStack.finishAll()
    }

    private fun setLoading(loading: Boolean) {
        binding.btnRegister.isEnabled = !loading
        binding.btnRegister.visibility = if (loading) View.INVISIBLE else View.VISIBLE
        binding.progressRegister.visibility = if (loading) View.VISIBLE else View.GONE
        binding.etUsername.isEnabled = !loading
        binding.etDisplayName.isEnabled = !loading
        binding.etPassword.isEnabled = !loading
        binding.etConfirmPassword.isEnabled = !loading
    }

    private fun showError(message: String) {
        binding.tvError.text = message
        binding.tvError.visibility = View.VISIBLE
    }

    private fun hideError() {
        binding.tvError.visibility = View.GONE
    }
}
