package com.runpulse.app.auth

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

/**
 * 认证管理器（单例）：本地模拟登录/注册/Token 管理。
 * 后续对接后端时，只需替换 login/register 为真实 API 调用。
 *
 * 存储说明：
 * - 当前登录态（token/userId/username/displayName）存 "auth_prefs"
 * - 已注册用户列表存 "auth_users"（本地模拟用，后端对接后移除）
 * - 密码存 SHA-256 哈希，不存明文
 */
object AuthManager {

    private const val PREFS_NAME = "auth_prefs"
    private const val PREFS_USERS = "auth_users"
    private const val KEY_TOKEN = "token"
    private const val KEY_USER_ID = "user_id"
    private const val KEY_USERNAME = "username"
    private const val KEY_DISPLAY_NAME = "display_name"

    private lateinit var prefs: SharedPreferences
    private lateinit var usersPrefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        usersPrefs = context.getSharedPreferences(PREFS_USERS, Context.MODE_PRIVATE)
    }

    // ---------- 状态查询 ----------

    fun isLoggedIn(): Boolean = prefs.getString(KEY_TOKEN, null) != null

    fun getToken(): String? = prefs.getString(KEY_TOKEN, null)

    fun getUserId(): String? = prefs.getString(KEY_USER_ID, null)

    fun getUsername(): String? = prefs.getString(KEY_USERNAME, null)

    fun getDisplayName(): String? = prefs.getString(KEY_DISPLAY_NAME, null)

    // ---------- 登录 ----------

    /**
     * 本地模拟登录：校验用户名+密码 → 生成 token → 保存登录态。
     * 模拟网络延迟 800ms。
     *
     * 本地模拟阶段的体验约定：账号不存在时【自动注册并登录】（以输入的密码建档），
     * 避免"用户不存在"阻断使用；已存在的账号仍校验密码。
     */
    suspend fun login(username: String, password: String): Result<UserInfo> =
        withContext(Dispatchers.IO) {
            delay(800) // 模拟网络延迟

            val userJson = usersPrefs.getString(username, null)
            if (userJson == null) {
                // 账号不存在：自动注册（displayName 默认用用户名）并直接登录
                return@withContext register(username, password, username)
            }

            val user = JSONObject(userJson)
            val storedHash = user.getString("passwordHash")
            if (hashPassword(password) != storedHash) {
                return@withContext Result.failure(AuthException("密码错误"))
            }

            val token = "local_${UUID.randomUUID()}"
            val userInfo = UserInfo(
                userId = user.getString("userId"),
                username = username,
                displayName = user.getString("displayName"),
                token = token
            )
            saveLoginState(userInfo)
            Result.success(userInfo)
        }

    // ---------- 注册 ----------

    /**
     * 本地模拟注册：用户名查重 → 保存用户 → 自动登录。
     * 模拟网络延迟 800ms。
     */
    suspend fun register(
        username: String,
        password: String,
        displayName: String
    ): Result<UserInfo> = withContext(Dispatchers.IO) {
        delay(800)

        // 用户名查重
        if (usersPrefs.contains(username)) {
            return@withContext Result.failure(AuthException("用户名已存在"))
        }

        val userId = UUID.randomUUID().toString()
        val token = "local_${UUID.randomUUID()}"

        // 保存用户到本地用户表
        val userJson = JSONObject().apply {
            put("userId", userId)
            put("passwordHash", hashPassword(password))
            put("displayName", displayName)
            put("createdAt", System.currentTimeMillis())
        }
        usersPrefs.edit().putString(username, userJson.toString()).apply()

        // 自动登录
        val userInfo = UserInfo(
            userId = userId,
            username = username,
            displayName = displayName,
            token = token
        )
        saveLoginState(userInfo)
        Result.success(userInfo)
    }

    // ---------- 登出 ----------

    fun logout() {
        prefs.edit().clear().apply()
    }

    // ---------- 第三方登录 ----------

    /**
     * 根据用户名查找用户（供第三方登录使用）。
     */
    /** 本地用户表是否已有该用户名（调试用） */
    fun userExists(username: String): Boolean = usersPrefs.contains(username)

    fun findUserByUsername(username: String): JSONObject? {
        val userJson = usersPrefs.getString(username, null) ?: return null
        return JSONObject(userJson)
    }

    /**
     * 微信登录（由 WeChatAuthManager 调用）。
     */
    suspend fun loginWithWeChat(username: String, displayName: String): Result<UserInfo> =
        withContext(Dispatchers.IO) {
            val userJson = usersPrefs.getString(username, null)
                ?: return@withContext Result.failure(AuthException("用户不存在"))

            val user = JSONObject(userJson)
            val token = "wechat_${UUID.randomUUID()}"
            val userInfo = UserInfo(
                userId = user.getString("userId"),
                username = username,
                displayName = displayName,
                token = token
            )
            saveLoginState(userInfo)
            WeChatAuthManager.notifyLoginResult(Result.success(userInfo))
            Result.success(userInfo)
        }

    /**
     * 微信注册（由 WeChatAuthManager 调用）。
     */
    suspend fun registerWithWeChat(username: String, displayName: String): Result<UserInfo> =
        withContext(Dispatchers.IO) {
            if (usersPrefs.contains(username)) {
                return@withContext Result.failure(AuthException("用户已存在"))
            }

            val userId = UUID.randomUUID().toString()
            val token = "wechat_${UUID.randomUUID()}"

            val userJson = JSONObject().apply {
                put("userId", userId)
                put("displayName", displayName)
                put("loginType", "wechat")
                put("createdAt", System.currentTimeMillis())
            }
            usersPrefs.edit().putString(username, userJson.toString()).apply()

            val userInfo = UserInfo(userId, username, displayName, token)
            saveLoginState(userInfo)
            WeChatAuthManager.notifyLoginResult(Result.success(userInfo))
            Result.success(userInfo)
        }

    /**
     * 手机号登录（由 PhoneAuthManager 调用）。
     */
    suspend fun loginWithPhone(username: String, displayName: String, phone: String): Result<UserInfo> =
        withContext(Dispatchers.IO) {
            val userJson = usersPrefs.getString(username, null)
                ?: return@withContext Result.failure(AuthException("用户不存在"))

            val user = JSONObject(userJson)
            val token = "phone_${UUID.randomUUID()}"
            val userInfo = UserInfo(
                userId = user.getString("userId"),
                username = username,
                displayName = displayName,
                token = token
            )
            saveLoginState(userInfo)
            PhoneAuthManager.notifyLoginResult(Result.success(userInfo))
            Result.success(userInfo)
        }

    /**
     * 手机号注册（由 PhoneAuthManager 调用）。
     */
    suspend fun registerWithPhone(username: String, displayName: String, phone: String): Result<UserInfo> =
        withContext(Dispatchers.IO) {
            if (usersPrefs.contains(username)) {
                return@withContext Result.failure(AuthException("用户已存在"))
            }

            val userId = UUID.randomUUID().toString()
            val token = "phone_${UUID.randomUUID()}"

            val userJson = JSONObject().apply {
                put("userId", userId)
                put("displayName", displayName)
                put("phone", phone)
                put("loginType", "phone")
                put("createdAt", System.currentTimeMillis())
            }
            usersPrefs.edit().putString(username, userJson.toString()).apply()

            val userInfo = UserInfo(userId, username, displayName, token)
            saveLoginState(userInfo)
            PhoneAuthManager.notifyLoginResult(Result.success(userInfo))
            Result.success(userInfo)
        }

    // ---------- 内部方法 ----------

    private fun saveLoginState(user: UserInfo) {
        prefs.edit()
            .putString(KEY_TOKEN, user.token)
            .putString(KEY_USER_ID, user.userId)
            .putString(KEY_USERNAME, user.username)
            .putString(KEY_DISPLAY_NAME, user.displayName)
            .apply()
    }

    private fun hashPassword(password: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(password.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }
}

/** 用户信息 */
data class UserInfo(
    val userId: String,
    val username: String,
    val displayName: String,
    val token: String
)

/** 认证异常 */
class AuthException(message: String) : Exception(message)
