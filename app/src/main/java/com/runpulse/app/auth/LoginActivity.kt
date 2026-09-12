package com.runpulse.app.auth

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.runpulse.app.MainActivity
import com.runpulse.app.R
import com.runpulse.app.databinding.ActivityLoginBinding
import com.runpulse.app.ui.base.ActivityStack
import com.runpulse.app.ui.base.BaseActivity
import kotlinx.coroutines.launch

/**
 * 登录页：用户名 + 密码登录。
 * - 支持游客模式（暂不登录，先逛逛）
 * - 登录成功后清除页面栈并进入主页
 */
class LoginActivity : BaseActivity<ActivityLoginBinding>(ActivityLoginBinding::inflate) {

    override fun statusBarDarkFont(): Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fitSystemBars(top = binding.loginContainer)

        binding.btnLogin.setOnClickListener { attemptLogin() }
        binding.btnGoRegister.setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
        }
        binding.btnSkip.setOnClickListener { finish() }
    }

    private fun attemptLogin() {
        val username = binding.etUsername.text.toString().trim()
        val password = binding.etPassword.text.toString()

        // 前端校验
        if (username.isEmpty()) {
            showError(getString(R.string.auth_error_username_empty))
            return
        }
        if (password.isEmpty()) {
            showError(getString(R.string.auth_error_password_empty))
            return
        }

        hideError()
        setLoading(true)

        lifecycleScope.launch {
            val result = AuthManager.login(username, password)
            if (!isFinishing && !isDestroyed) {
                setLoading(false)
                result.onSuccess {
                    Toast.makeText(
                        this@LoginActivity,
                        getString(R.string.auth_login_success, it.displayName),
                        Toast.LENGTH_SHORT
                    ).show()
                    navigateToMain()
                }.onFailure { e ->
                    showError(e.message ?: getString(R.string.auth_error_login_failed))
                }
            }
        }
    }

    private fun navigateToMain() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        ActivityStack.finishAll()
    }

    private fun setLoading(loading: Boolean) {
        binding.btnLogin.isEnabled = !loading
        binding.btnLogin.visibility = if (loading) View.INVISIBLE else View.VISIBLE
        binding.progressLogin.visibility = if (loading) View.VISIBLE else View.GONE
        binding.etUsername.isEnabled = !loading
        binding.etPassword.isEnabled = !loading
    }

    private fun showError(message: String) {
        binding.tvError.text = message
        binding.tvError.visibility = View.VISIBLE
    }

    private fun hideError() {
        binding.tvError.visibility = View.GONE
    }
}
