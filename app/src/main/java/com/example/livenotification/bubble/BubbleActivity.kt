package com.example.livenotification.bubble

import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.example.livenotification.ui.theme.LiveNotificationDemoTheme
import kotlinx.coroutines.flow.first

/**
 * 气泡展开后的界面（Compose 版）。
 *
 * 与原来的 XML 版逻辑一致（真回复 / 实时刷新 / 打开应用），只是把 UI 换成了
 * 主软件同一套 Material3 设计语言：与 App 其它页面一致的颜色、字体与控件。
 *
 * 职责：
 *  1. **真回复**：原通知 actions 里通常挂着带 RemoteInput 的回复动作，把它的
 *     PendingIntent + resultKey 透传进来，用户输入后用 RemoteInput.addResultsToIntent
 *     塞进 fillIn Intent，再 send 原应用的 PendingIntent（微信/QQ 当自己的直接回复收）。
 *  2. **实时刷新**：这个 Activity 是"气泡展开"的宿主，同一会话又来了新消息时，
 *     系统不会重建 Activity，所以服务端广播 ACTION_BUBBLE_UPDATED，这里就地刷新。
 */
class BubbleActivity : ComponentActivity() {

    companion object {
        private const val TAG = "BubbleNotify"

        const val EXTRA_PACKAGE_NAME = "package_name"
        const val EXTRA_TITLE = "title"
        const val EXTRA_TEXT = "text"
        const val EXTRA_SHORTCUT_ID = "shortcut_id"

        /** 原通知里那条"回复"动作的 PendingIntent（源应用创建，我们只负责发送） */
        const val EXTRA_REPLY_PENDING_INTENT = "reply_pending_intent"

        /** RemoteInput 的结果 key，回复结果要挂在这个 key 上 */
        const val EXTRA_REPLY_RESULT_KEY = "reply_result_key"

        /** 服务端在会话有新消息时发的广播 */
        const val ACTION_BUBBLE_UPDATED = "com.example.livenotification.action.BUBBLE_UPDATED"
    }

    private var sourcePackage: String = ""
    private var shortcutId: String? = null

    private val titleState = mutableStateOf("")
    private val textState = mutableStateOf("")
    private val iconState = mutableStateOf<ImageBitmap?>(null)
    private val canReplyState = mutableStateOf(false)

    private var replyPendingIntent: PendingIntent? = null
    private var replyResultKey: String? = null

    /** 会话更新广播：同一会话来了新消息时，把展开中的内容就地刷新 */
    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent ?: return
            // 只认自己这条会话：一个 Activity 只代表一个气泡
            if (intent.getStringExtra(EXTRA_SHORTCUT_ID) != shortcutId) return
            Log.d(TAG, "气泡内容刷新：${intent.getStringExtra(EXTRA_TITLE)}")
            applyContent(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sourcePackage = intent.getStringExtra(EXTRA_PACKAGE_NAME).orEmpty()
        if (sourcePackage.isNullOrEmpty()) {
            // 桌面长按图标点进来的快捷方式没有正文，直接关掉，别留个空白页
            finish()
            return
        }
        shortcutId = intent.getStringExtra(EXTRA_SHORTCUT_ID)

        // 标题旁边挂上原应用的图标，一眼看出这是微信还是 QQ 的气泡
        runCatching {
            val drawable = packageManager.getApplicationIcon(sourcePackage)
            iconState.value = drawable.toBitmap(48, 48).asImageBitmap()
        }.onFailure { Log.w(TAG, "读取原应用图标失败 pkg=$sourcePackage", it) }

        applyContent(intent)

        setContent {
            LiveNotificationDemoTheme {
                BubbleChatScreen(
                    title = titleState.value,
                    message = textState.value,
                    appIcon = iconState.value,
                    canReply = canReplyState.value,
                    onClose = { finish() },
                    onOpenApp = { openChatApp(sourcePackage) },
                    onSendReply = { text -> sendReply(text) },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(ACTION_BUBBLE_UPDATED)
        // targetSdk 34+ 注册非系统广播必须显式声明导出性，这里是应用内广播
        ContextCompat.registerReceiver(
            this, updateReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStop() {
        runCatching { unregisterReceiver(updateReceiver) }
        super.onStop()
    }

    /** 会话标题 + 消息正文（正文是多条历史拼起来的） */
    private fun applyContent(source: Intent) {
        titleState.value = source.getStringExtra(EXTRA_TITLE).orEmpty()
        textState.value = source.getStringExtra(EXTRA_TEXT).orEmpty()
        source.parcelable(EXTRA_REPLY_PENDING_INTENT, PendingIntent::class.java)
            ?.let { replyPendingIntent = it }
        source.getStringExtra(EXTRA_REPLY_RESULT_KEY)?.takeIf { it.isNotEmpty() }
            ?.let { replyResultKey = it }
        canReplyState.value =
            replyPendingIntent != null && !replyResultKey.isNullOrEmpty()
    }

    private fun sendReply(text: String) {
        val pendingIntent = replyPendingIntent ?: return
        val resultKey = replyResultKey ?: return
        if (text.isEmpty()) return

        val fillIn = Intent()
        val results = Bundle().apply { putCharSequence(resultKey, text) }
        // 只要 resultKey 一致就行，这里重建一个 RemoteInput 只是为了让平台把结果
        // 按正确的 key 挂到 fillIn Intent 上。
        RemoteInput.addResultsToIntent(
            arrayOf(RemoteInput.Builder(resultKey).build()),
            fillIn,
            results
        )
        RemoteInput.setResultsSource(fillIn, RemoteInput.SOURCE_FREE_FORM_INPUT)

        try {
            pendingIntent.send(this, 0, fillIn)
            Toast.makeText(this, "已发送", Toast.LENGTH_SHORT).show()
            finish()
        } catch (e: PendingIntent.CanceledException) {
            Log.w(TAG, "回复失败：回复动作已失效", e)
            Toast.makeText(this, "发送失败，会话可能已过期", Toast.LENGTH_SHORT).show()
            openChatApp(sourcePackage)
        } catch (t: Throwable) {
            Log.w(TAG, "回复失败", t)
            Toast.makeText(this, "发送失败，会话可能已过期", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 打开原应用。
     *
     * 在**我们的 Activity 里** send 别人的 PendingIntent 会被 Android 12+ 的 BAL
     * 拒绝（且静默丢弃），所以这里只用 launcher 入口打开应用。
     * 真正"在气泡里显示原应用界面"的路径见 BubbleSettings.expandSourceApp。
     */
    private fun openChatApp(pkg: String) {
        if (pkg.isEmpty()) {
            finish()
            return
        }
        val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
        if (launchIntent == null) {
            Toast.makeText(this, "未找到该应用", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        launchIntent.flags =
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        startActivity(launchIntent)
        finish()
    }

    @Suppress("DEPRECATION", "UNCHECKED_CAST")
    private fun <T : Parcelable> Intent.parcelable(key: String, clazz: Class<T>): T? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(key, clazz)
        } else {
            getParcelableExtra(key) as? T
        }
}

@Composable
private fun BubbleChatScreen(
    title: String,
    message: String,
    appIcon: ImageBitmap?,
    canReply: Boolean,
    onClose: () -> Unit,
    onOpenApp: () -> Unit,
    onSendReply: (String) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            // 顶部：原应用图标 + 标题 + 关闭
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (appIcon != null) {
                    Image(
                        bitmap = appIcon,
                        contentDescription = "原应用图标",
                        modifier = Modifier.size(28.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = "关闭")
                }
            }

            HorizontalDivider()

            // 中间：消息内容（可滚动，新消息在底部）
            val scrollState = rememberScrollState()
            LaunchedEffect(message) {
                snapshotFlow { scrollState.maxValue }.first { it > 0 }
                scrollState.animateScrollTo(scrollState.maxValue)
            }
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(scrollState)
                    .padding(vertical = 12.dp),
            )

            // 回复行：源应用没提供 RemoteInput 时整行隐藏
            if (canReply) {
                var input by remember { mutableStateOf("") }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("输入回复…") },
                        maxLines = 3,
                    )
                    Spacer(Modifier.width(8.dp))
                    FilledTonalButton(
                        onClick = {
                            if (input.isNotBlank()) {
                                onSendReply(input.trim())
                                input = ""
                            }
                        }
                    ) { Text("发送") }
                }
                Spacer(Modifier.height(8.dp))
            }

            // 底部：打开原应用
            Button(onClick = onOpenApp, modifier = Modifier.fillMaxWidth()) {
                Text("打开应用")
            }
        }
    }
}
