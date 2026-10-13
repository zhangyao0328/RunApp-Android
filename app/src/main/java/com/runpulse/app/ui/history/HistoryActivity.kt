package com.runpulse.app.ui.history

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.runpulse.app.api.ApiClient
import com.runpulse.app.api.ImportTrackResponse
import com.runpulse.app.R
import com.runpulse.app.data.AppDatabase
import com.runpulse.app.data.SessionEntity
import com.runpulse.app.data.TrackPointEntity
import com.runpulse.app.databinding.ActivityHistoryBinding
import com.runpulse.app.importer.TrackImporter
import com.runpulse.app.ui.base.BaseActivity
import com.runpulse.app.ui.detail.SessionDetailActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 历史记录页：已结束会话列表（距离/时长/配速或速度），点击进入详情。
 * 右下角 FAB 支持从手机存储导入 GPX / FIT 轨迹文件：
 * 本地解析 → 上传云端 → 写入 Room（summaries 为 Room Flow，写库后列表自动刷新）。
 */
class HistoryActivity : BaseActivity<ActivityHistoryBinding>(ActivityHistoryBinding::inflate) {

    private val viewModel: HistoryViewModel by viewModels()

    /** SAF 选文件（mime 过滤不可靠，选后自行校验后缀） */
    private val openDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { importTrackFile(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 列表避开状态栏（上边距）与虚拟键（下内边距）
        fitSystemBars(top = binding.historyContent, bottom = binding.historyContent)

        val adapter = HistoryAdapter { summary ->
            startActivityWithAnim(
                Intent(this, SessionDetailActivity::class.java)
                    .putExtra(SessionDetailActivity.EXTRA_SESSION_ID, summary.session.sessionId)
            )
        }
        binding.recyclerHistory.apply {
            layoutManager = LinearLayoutManager(this@HistoryActivity)
            this.adapter = adapter
        }

        binding.btnImport.setOnClickListener {
            openDocumentLauncher.launch(arrayOf("*/*"))
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.summaries.collect {
                    adapter.submitList(it)
                    binding.emptyHistory.visibility = if (it.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
                }
            }
        }
    }

    /** 导入流程：读字节 → 校验后缀 → 本地解析 → 上传 → 写 Room */
    private fun importTrackFile(uri: Uri) {
        binding.btnImport.isEnabled = false
        binding.btnImport.isVisible = false
        binding.importProgress.isVisible = true
        lifecycleScope.launch {
            val result = runCatching { doImport(uri) }.getOrElse { it.message ?: it.toString() }
            binding.btnImport.isEnabled = true
            binding.btnImport.isVisible = true
            binding.importProgress.isVisible = false
            toast(result)
        }
    }

    private suspend fun doImport(uri: Uri): String = withContext(Dispatchers.IO) {
        // 原始文件名（服务器按后缀判断格式）
        var displayName = "track.gpx"
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    .takeIf { it >= 0 }?.let { displayName = cursor.getString(it) ?: displayName }
            }
        }
        val lower = displayName.lowercase()
        if (!lower.endsWith(".gpx") && !lower.endsWith(".fit")) {
            return@withContext getString(R.string.import_unsupported)
        }

        // 读取文件字节 + 本地解析（失败则中止，不上传）
        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return@withContext getString(R.string.import_read_failed)
        val track = runCatching {
            bytes.inputStream().use {
                if (lower.endsWith(".gpx")) TrackImporter.parseGpx(it) else TrackImporter.parseFit(it)
            }
        }.getOrElse {
            return@withContext getString(
                R.string.import_parse_failed, it.message ?: getString(R.string.import_parse_generic)
            )
        }

        // 上传云端
        val body = bytes.toRequestBody("application/octet-stream".toMediaTypeOrNull())
        val part = MultipartBody.Part.createFormData("file", displayName, body)
        val resp = runCatching { ApiClient.api.importTrack(part) }.getOrElse {
            return@withContext getString(
                R.string.import_upload_failed, it.message ?: getString(R.string.import_network_error)
            )
        }
        if (!resp.isSuccessful) {
            return@withContext getString(
                R.string.import_upload_failed, extractError(resp) ?: "HTTP ${resp.code()}"
            )
        }
        val importResp: ImportTrackResponse? = resp.body()

        // 写 Room：服务器 sessionId 为空时本地生成；已存在则跳过避免唯一键冲突
        val dao = AppDatabase.get(applicationContext).trackingDao()
        val sessionId = importResp?.sessionId?.takeIf { it.isNotBlank() }
            ?: "imp-${java.util.UUID.randomUUID()}"
        if (dao.getSessionById(sessionId) == null) {
            val sportType = importResp?.sportType
                ?.takeIf { it in listOf("run", "trail", "bike") }
                ?: track.sportType
            dao.insertSession(
                SessionEntity(
                    sessionId = sessionId,
                    sportType = sportType,
                    startTime = track.points.first().timestamp,
                    endTime = track.points.last().timestamp,
                    isActive = false
                )
            )
            for (p in track.points) {
                dao.insertPoint(
                    TrackPointEntity(
                        sessionId = sessionId,
                        lat = p.lat,
                        lng = p.lng,
                        altitude = p.altitude ?: 0.0,
                        speed = p.speed ?: 0f,
                        accuracy = 0f,
                        heartRate = p.heartRate,
                        timestamp = p.timestamp,
                        uploaded = true // 已上传，不参与断网补传
                    )
                )
            }
        }
        getString(R.string.import_success_fmt, track.points.size)
    }

    /** 从 4xx 响应的 JSON errorBody 里取 error 字段 */
    private fun extractError(resp: retrofit2.Response<ImportTrackResponse>): String? = runCatching {
        resp.errorBody()?.string()?.let { json ->
            Gson().fromJson(json, JsonObject::class.java)?.get("error")?.asString
        }
    }.getOrNull()

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
