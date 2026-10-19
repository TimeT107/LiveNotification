package com.example.livenotification.bubble

import com.example.livenotification.R

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.Person as CorePerson
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.IconCompat

/**
 * 把 QQ / 微信的通知接成**原生气泡**（Android Bubbles）。
 *
 * ============ 这一版做了什么 ============
 *
 * 1. 渠道静音：原来 IMPORTANCE_HIGH + 默认铃声 = 微信响一次、本应用再响一次。
 *    现在渠道不带声音/震动，但仍然保持 HIGH（气泡照常弹），不再重复打扰。
 * 2. 过滤群汇总通知：微信/QQ 未读时会额外发一条 FLAG_GROUP_SUMMARY 的
 *    “N个联系人发来M条消息”，原来它会变成一个叫“微信”的假气泡。
 * 3. 气泡用原通知的大图标（联系人头像），终于能区分是谁了。
 * 4. 会话历史聚合：按会话累积最近 10 条消息，而不是每次只显示最后一条。
 * 5. 移除竞态护栏：原通知被划掉时才撤销气泡，且只在它确实是该会话**最新**
 *    的那条通知时才撤销，避免“微信取消旧通知 → 气泡一闪即逝”。
 * 6. 真回复：从原通知里取出挂 RemoteInput 的回复动作，在气泡内直接回消息，
 *    而不是点“回复”却跳到应用首页。
 *
 * 之前几个版本踩过的坑（MUTABLE 的 PendingIntent、API 30 才有双参构造、
 * EXTRA_MESSAGES 是 Bundle 数组）在下面就地保留说明。
 */
class NotifyListenerService : NotificationListenerService() {

    companion object {
        private const val TAG = "BubbleNotify"

        /** 监听服务当前是否真的连着。保活服务靠它判断要不要重连。 */
        @Volatile
        var connected: Boolean = false
            private set

        @Volatile
        private var instance: NotifyListenerService? = null

        /**
         * 自愈：监听服务"活着但断开"时补救。
         *
         * 常驻前台服务把进程顶住之后，系统不会因为进程死亡而重建监听服务，
         * 一旦断开就会一直僵着 —— 表现就是"没有气泡、原应用通知照常弹"。
         */
        /** 已经尝试过几次常规重连（决定要不要升级成 root 强治） */
        @Volatile
        private var healAttempts = 0

        fun ensureConnected(context: android.content.Context) {
            if (connected) {
                healAttempts = 0
                return
            }
            // 权限被用户/系统收回了，重连没意义
            val enabled = androidx.core.app.NotificationManagerCompat
                .getEnabledListenerPackages(context).contains(context.packageName)
            if (!enabled) {
                Log.w(TAG, "通知访问权限已被关闭，无法自动恢复")
                return
            }

            healAttempts++
            if (healAttempts == 1) {
                // 先走常规重连（免费、无副作用）
                runCatching {
                    Log.w(TAG, "监听服务未连接，先 requestRebind")
                    NotificationListenerService.requestRebind(
                        ComponentName(context, NotifyListenerService::class.java)
                    )
                }.onFailure { Log.w(TAG, "requestRebind 失败", it) }
                return
            }

            // 常规重连救不回来（NMS 卡在 already bound），升级成 root 强制重注册
            Log.w(TAG, "常规重连无效，改用 root 强制重新注册（第 " + healAttempts + " 次巡检）")
            RootHealer.healListenerAsync(context)
            if (healAttempts >= 3) healAttempts = 0
        }

        /**
         * 渠道 ID 特意带版本后缀。
         * 系统渠道的“声音 / 震动”属性一旦创建就不能靠重新 createNotificationChannel 改掉，
         * 换一个 ID 才能让静音配置在老用户设备上真正生效。
         */
        const val CHANNEL_ID = "bubble_channel_v2"
        private const val LEGACY_CHANNEL_ID = "bubble_channel"
        private const val CHANNEL_NAME = "气泡通知"


        /**
         * 调试开关：是否把 com.android.shell 发的通知也接成气泡。
         *
         * `adb shell cmd notification post` 是以 shell 身份发通知的，本地调试时
         * 临时改成 true，就能不靠别人发微信把整条链路跑通：
         *
         *   adb shell cmd notification post -S messaging --conversation "张三" \
         *       --message "张三:在吗" wx_test "新消息"
         *
         * 正式包保持 false —— 否则任何能执行 adb shell 的人都能往你屏幕上弹气泡。
         */
        private const val DEBUG_ACCEPT_SHELL_NOTIFICATIONS = false


        // 「是否自动展开气泡」「消息来时是否弹横幅」是用户可调项，
        // 已经搬到 BubbleSettings（主界面有开关），这里不再写死。

        /** 每个会话保留的消息条数（MessagingStyle 自身的上限是 25） */
        private const val MAX_MESSAGES = 10

        /** 同时维护的会话数上限，超出按最久未使用淘汰 */
        private const val MAX_CONVERSATIONS = 32

        /** EXTRA_MESSAGES 里每条的字段名，与平台 MessagingStyle 写入 Bundle 时一致 */
        private const val KEY_TEXT = "text"
        private const val KEY_SENDER = "sender"
        private const val KEY_SENDER_PERSON = "sender_person"
        private const val KEY_TIME = "time"
    }

    // ------------------------------------------------------------------
    // 会话状态
    // ------------------------------------------------------------------

    private data class ChatMessage(
        val text: String,
        val sender: String?,
        val timestamp: Long
    )

    private class Conversation(val shortcutId: String) {
        var pkg: String = ""
        var title: String = ""
        var notificationId: Int = 0
        var postTime: Long = 0L
        var isGroup: Boolean = false
        var icon: Icon? = null
        var replyAction: Notification.Action? = null
        var contentIntent: PendingIntent? = null

        /** 源应用自己会不会弹横幅（由 ranking 判断），用来决定我们要不要补一条 */
        var sourceHeadsUp: Boolean = false

        /** 已经推送过的快捷方式签名（标题 + 图标），避免重复推送触发系统限流 */
        var pushedSignature: String? = null

        val messages = ArrayList<ChatMessage>()
    }

    /** key = shortcutId，accessOrder = true 即 LRU：迭代顺序就是最久未使用在前 */
    private val conversations = LinkedHashMap<String, Conversation>(16, 0.75f, true)

    /**
     * 我们自己主动撤掉的原通知 key。
     * 撤销会让系统回调 onNotificationRemoved，要能区分"我们干的"和"用户划掉的"，
     * 否则刚撤掉源通知就会顺手把自己的气泡也杀掉。
     */
    private val selfCancelledKeys = HashSet<String>()

    /** 超出上限时淘汰最久未使用的会话（只丢状态，不动已经弹出的气泡） */
    private fun trimConversations() {
        if (conversations.size <= MAX_CONVERSATIONS) return
        val iterator = conversations.entries.iterator()
        while (conversations.size > MAX_CONVERSATIONS && iterator.hasNext()) {
            iterator.next()
            iterator.remove()
        }
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "NotifyListenerService.onCreate")
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java)

        // 清掉旧渠道，避免设置里留着一个会响铃的僵尸渠道
        runCatching { nm.deleteNotificationChannel(LEGACY_CHANNEL_ID) }
            .onFailure { Log.w(TAG, "删除旧渠道失败", it) }

        // 气泡要求渠道不能被屏蔽；HIGH 保证能弹气泡，但声音/震动全部关掉，
        // 因为“响”这件事微信自己已经做过了，我们只负责显示气泡。
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "用于显示 QQ 和微信的气泡通知（静音，不重复打扰）"
            setShowBadge(true)
            setSound(null, null)
            enableVibration(false)
            enableLights(false)
            // 显式允许气泡：部分 ROM（ColorOS 等）对新建渠道默认不放开气泡，
            // 此时 areBubblesEnabled/canBubble 为 false → 服务会降级成普通通知并跳过拦截。
            // 原工程依赖用户手动去系统设置里开；合并进本 App（新包名）后没有继承那个授权，
            // 所以这里在创建渠道时就显式打开（用户仍可在系统设置里关掉）。
            setAllowBubbles(true)
        }
        nm.createNotificationChannel(channel)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        connected = true
        Log.i(TAG, "监听服务已连接")
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        connected = false
        super.onDestroy()
    }

    /**
     * 被系统断开后重试重连。
     * 只重试有限次数：真连不上通常是权限被收回，狂重连没意义。
     */
    override fun onListenerDisconnected() {
        connected = false
        Log.w(TAG, "监听服务断开，准备重连")
        retryRebind(1)
    }

    private fun retryRebind(attempt: Int) {
        if (attempt > 3) {
            Log.e(TAG, "重连 3 次都失败，等下次前台再试")
            return
        }
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (connected) return@postDelayed
            Log.w(TAG, "requestRebind 第 " + attempt + " 次")
            runCatching {
                requestRebind(ComponentName(this, NotifyListenerService::class.java))
            }.onFailure { Log.w(TAG, "requestRebind 失败", it) }
            retryRebind(attempt + 1)
        }, 2000L * attempt)
    }

    // ------------------------------------------------------------------
    // 通知解析
    // ------------------------------------------------------------------

    private data class NotifContent(
        val conversation: String,
        val messages: List<ChatMessage>,
        val fallbackText: String?,
        val replyAction: Notification.Action?,
        val largeIcon: Icon?,
        val isGroup: Boolean,
        val meName: String,

        /** 原通知的点击意图——点我们的横幅时直接跳到微信/QQ 的那个会话 */
        val contentIntent: PendingIntent?,

        /** 这是不是一条"确定的会话消息"（MessagingStyle / 有会话名 / 有回复动作 / 有快捷方式） */
        val confidentConversation: Boolean,

        /** 是不是聚合通知（标题就是应用名，比如"QQ"、"微信"，代表多条会话的合集） */
        val isAggregate: Boolean
    )

    /**
     * 取出“会话名 / 消息列表 / 回复动作 / 头像”。
     *
     * QQ 和微信大量使用 Notification.MessagingStyle，正文**不在** EXTRA_TEXT，
     * 而在 EXTRA_MESSAGES —— 那是一个 Parcelable[]，元素是 Bundle
     * （平台把 MessagingStyle.Message 序列化成 Bundle，Message 本身并不是 Parcelable）。
     */
    private fun parse(sbn: StatusBarNotification): NotifContent? {
        val n = sbn.notification
        val extras = n.extras

        val conversationTitle = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
            ?.toString()?.takeIf { it.isNotBlank() }
        val genericTitle = extras.getCharSequence(Notification.EXTRA_TITLE)
            ?.toString()?.takeIf { it.isNotBlank() }
        val conversation = conversationTitle ?: genericTitle ?: ""

        val messages = ArrayList<ChatMessage>()
        val raw = extras.parcelableArray(Notification.EXTRA_MESSAGES)
        if (raw != null) {
            for (item in raw) {
                val bundle = item as? Bundle ?: continue
                val text = bundle.getCharSequence(KEY_TEXT)
                    ?.toString()?.takeIf { it.isNotBlank() } ?: continue
                val sender = bundle.getCharSequence(KEY_SENDER)
                    ?.toString()?.takeIf { it.isNotBlank() }
                    ?: bundle.parcelable(KEY_SENDER_PERSON, Person::class.java)
                        ?.name?.toString()?.takeIf { it.isNotBlank() }
                val time = bundle.getLong(KEY_TIME, 0L).takeIf { it > 0L } ?: n.`when`
                messages.add(ChatMessage(text, sender, time))
            }
            Log.d(TAG, "MessagingStyle 消息 ${raw.size} 条，解析出 ${messages.size} 条")
        }

        val fallback = extras.getCharSequence(Notification.EXTRA_TEXT)
            ?.toString()?.takeIf { it.isNotBlank() }

        if (messages.isEmpty() && fallback == null) {
            Log.w(TAG, "跳过：正文为空（pkg=${sbn.packageName} 有EXTRA_MESSAGES=${raw != null}）")
            return null
        }

        val me = extras.parcelable(Notification.EXTRA_MESSAGING_PERSON, Person::class.java)
            ?.name?.toString()?.takeIf { it.isNotBlank() } ?: "我"

        val replyAction = findReplyAction(n)

        // 只有这几样之一才算"确定的会话消息"：
        //   MessagingStyle / 带 EXTRA_MESSAGES / 有会话标题 / 带回复动作 / 带快捷方式
        // 光有 EXTRA_TITLE + EXTRA_TEXT 的通知（"微信支付"、QQ 的聚合"QQ"）不算。
        val isMessagingStyle = extras.getString(Notification.EXTRA_TEMPLATE)
            ?.endsWith("MessagingStyle") == true
        val confidentConversation = isMessagingStyle ||
            messages.isNotEmpty() ||
            conversationTitle != null ||
            replyAction != null ||
            !n.shortcutId.isNullOrEmpty()

        // 标题恰好等于应用名 = 聚合通知（"QQ"、"微信"），它代表的是多条会话的合集，
        // 既不该变气泡，更不能拦截 —— 拦了就等于把用户所有 QQ 消息吞掉。
        val appLabel = runCatching {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getApplicationInfo(
                    sbn.packageName,
                    PackageManager.ApplicationInfoFlags.of(0L)
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getApplicationInfo(sbn.packageName, 0)
            }
            packageManager.getApplicationLabel(info).toString()
        }.getOrNull().orEmpty()
        val isAggregate = appLabel.isNotEmpty() && genericTitle == appLabel

        // 聚合通知（QQ 就是用一条标题为 "QQ" 的通知播报最新一条消息，正文 "联系人:内容"）：
        // 按正文还原出会话名，否则所有 QQ 消息会挤进同一个"QQ"气泡里。
        var effectiveConversation = conversation
        var effectiveFallback = fallback
        if (isAggregate) {
            splitSenderLine(fallback)?.let { (sender, body) ->
                effectiveConversation = sender
                effectiveFallback = body
            }
        }

        return NotifContent(
            conversation = effectiveConversation.ifEmpty {
                messages.lastOrNull()?.sender ?: "新消息"
            },
            messages = messages,
            fallbackText = effectiveFallback,
            replyAction = replyAction,
            // Notification 里有个废弃的公开字段 largeIcon（Parcelable），Kotlin 的
            // n.largeIcon 会命中那个字段而不是 getLargeIcon()，所以这里显式调 getter。
            largeIcon = n.getLargeIcon()
                ?: extras.parcelable(Notification.EXTRA_LARGE_ICON_BIG, Icon::class.java),
            isGroup = extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false),
            meName = me,
            contentIntent = n.contentIntent,
            confidentConversation = confidentConversation,
            isAggregate = isAggregate
        )
    }

    /**
     * 把 "联系人:内容" 这样的聚合播报正文拆成 (联系人, 内容)。
     * 拆不出来返回 null。
     */
    private fun splitSenderLine(text: String?): Pair<String, String>? {
        if (text.isNullOrBlank()) return null
        val idx = text.indexOfFirst { it == ':' || it == '：' }
        if (idx <= 0 || idx >= text.length - 1 || idx > 24) return null
        val sender = text.substring(0, idx).trim()
        val body = text.substring(idx + 1).trim()
        if (sender.isEmpty() || body.isEmpty()) return null
        // "[6条]微信支付" 这种带方括号前缀的是聚合播报，不是某个联系人
        if (sender.any { it == '[' || it == ']' || it == '【' || it == '】' }) return null
        return sender to body
    }

    /**
     * 找原通知里挂 RemoteInput 的“回复”动作。
     * 只认可以自由输入的（纯快捷短语的回复先不接管）。
     */
    private fun findReplyAction(n: Notification): Notification.Action? =
        n.actions?.firstOrNull { action ->
            action.remoteInputs?.any { it.allowFreeFormInput } == true
        }

    // ------------------------------------------------------------------
    // 稳定 ID
    // ------------------------------------------------------------------

    /**
     * 生成**稳定**的气泡 ID。
     *
     * 原来用 conversationTitle.hashCode()，而微信群名常带人数（“群聊(3)”），
     * 人数一变 ID 就变 → 同一会话被当成新会话，多出一个气泡。
     *
     * 优先级：通知自带的 shortcutId（系统已认定的会话标识）→ sbn.key → 兜底哈希。
     */
    private fun stableShortcutId(sbn: StatusBarNotification, conversation: String): String {
        val own = sbn.notification.shortcutId
        if (!own.isNullOrEmpty()) return "bubble_" + own
        sbn.key?.takeIf { it.isNotEmpty() }?.let { return "bubble_k_" + it.hashCode() }
        return "bubble_" + sbn.packageName.hashCode() + "_" + conversation.hashCode()
    }

    /**
     * 气泡通知自己的 id。
     * 用**会话**而不是原通知 id 推导：同一条会话的原通知 id 会变（微信重发通知），
     * 用原 id 会导致气泡重复出现、旧的没人收拾。
     */
    private fun bubbleId(conv: Conversation): Int =
        ("bubble:" + conv.pkg + "#" + conv.shortcutId).hashCode()

    // ------------------------------------------------------------------
    // 收到通知
    // ------------------------------------------------------------------

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!isTarget(sbn)) return
        // 接管所有白名单通知：能解析成气泡的就建气泡再撤原通知；
        // 解析不出内容（没标题没正文）的原样留着，不动它。
        val interceptEnabled =
            BubbleSettings.interceptSource(this) && BubbleSettings.bubblesAllowed(this)

        if (isGroupSummary(sbn) || sbn.isOngoing) {
            // 汇总/常驻通知本身不需要气泡，但既然要全量接管，就别让它留在通知栏里
            Log.i(TAG, "汇总/常驻通知：清理掉 key=${sbn.key}")
            if (interceptEnabled) interceptSourceNotification(sbn)
            return
        }

        val content = parse(sbn) ?: return
        Log.i(
            TAG,
            "接管 pkg=${sbn.packageName} 会话=${content.conversation} " +
                "聚合=${content.isAggregate} 确定会话=${content.confidentConversation}"
        )

        val shortcutId = stableShortcutId(sbn, content.conversation)

        val conv = conversations[shortcutId] ?: Conversation(shortcutId).also {
            conversations[shortcutId] = it
            trimConversations()
        }
        conv.pkg = sbn.packageName
        conv.title = content.conversation
        conv.notificationId = sbn.id
        conv.postTime = sbn.postTime
        conv.sourceHeadsUp = sourceWillShowHeadsUp(sbn)
        if (content.isGroup) conv.isGroup = true
        content.replyAction?.let { conv.replyAction = it }
        content.contentIntent?.let { conv.contentIntent = it }
        // 头像只认第一次拿到的：同一会话的头像基本不变，
        // 而每次消息都换一个 Bitmap 会让下面的快捷方式签名永远在变。
        if (conv.icon == null) content.largeIcon?.let { conv.icon = it }

        val incoming = if (content.messages.isNotEmpty()) {
            content.messages
        } else {
            val text = content.fallbackText ?: return
            val last = conv.messages.lastOrNull()
            // 同一条兜底文案被反复重发时不要堆叠
            if (last != null && last.text == text && last.sender == null) {
                emptyList()
            } else {
                listOf(ChatMessage(text, null, sbn.postTime))
            }
        }
        val merged = mergeHistory(conv.messages, incoming)
        conv.messages.clear()
        conv.messages.addAll(merged)

        Log.i(
            TAG,
            "收到消息 pkg=${sbn.packageName} 会话=${content.conversation} " +
                "本轮=${incoming.size} 条 累计=${conv.messages.size} 条 " +
                "有回复动作=${conv.replyAction != null}"
        )

        val avatar = conv.icon?.toBitmap()
        pushShortcutIfNeeded(conv, avatar)
        val bubblePosted = showBubble(conv, avatar, content.meName)

        // 通知接管：气泡真的发出来了才去撤源通知，
        // 否则一旦气泡这边失败，就等于把用户的消息彻底弄丢了。
        // 气泡真的发出来了，才把原通知撤掉 —— 保证任何一条消息都有气泡承载，
        // 不会出现"原通知没了、气泡也没出来"的情况。
        if (bubblePosted && interceptEnabled) {
            interceptSourceNotification(sbn)
        }
    }

    /**
     * 把新来的消息并入已有历史。
     *
     * 微信重发通知时通常会把整段会话历史一起带上，所以先找“旧历史的尾巴”
     * 和“新消息的开头”的重叠部分，避免同一批消息被重复追加。
     */
    private fun mergeHistory(
        old: List<ChatMessage>,
        incoming: List<ChatMessage>
    ): List<ChatMessage> {
        // 必须返回新列表：调用方拿到结果后会先 clear 再 addAll，
        // 直接返回 old 会被连坐清空，历史就没了。
        if (incoming.isEmpty()) return old.toList()
        for (k in minOf(old.size, incoming.size) downTo 1) {
            if (old.subList(old.size - k, old.size) == incoming.subList(0, k)) {
                return (old + incoming.subList(k, incoming.size)).takeLast(MAX_MESSAGES)
            }
        }
        return (old + incoming).takeLast(MAX_MESSAGES)
    }

    /**
     * 原通知被划掉 / 清除时，把对应气泡也撤掉。
     *
     * 护栏：只有当被移除的这条确实是该会话**当前最新**的那条通知时才撤销。
     * 微信/QQ 更新会话通知时经常“先取消旧的、再发新的”，没有护栏的话
     * 新气泡会被旧通知的移除事件顺手干掉，表现为气泡一闪即逝。
     */
    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (!isTarget(sbn)) return
        // 我们自己拦截掉的不算"用户划掉通知"，别把气泡也撤了
        if (sbn.key != null && selfCancelledKeys.remove(sbn.key)) {
            Log.d(TAG, "跳过：这是我们自己拦截的 key=${sbn.key}")
            return
        }

        val conv = conversations.values.firstOrNull {
            it.pkg == sbn.packageName && it.notificationId == sbn.id
        } ?: return

        if (sbn.postTime < conv.postTime) {
            Log.d(TAG, "忽略过期的移除事件 key=${sbn.key}")
            return
        }

        runCatching {
            getSystemService(NotificationManager::class.java).cancel(bubbleId(conv))
            Log.i(TAG, "原通知已移除，同时撤掉气泡 pkg=${sbn.packageName} id=${sbn.id}")
        }.onFailure { Log.w(TAG, "撤销气泡失败", it) }
    }

    // ------------------------------------------------------------------
    // 气泡
    // ------------------------------------------------------------------

    private fun pushShortcutIfNeeded(conv: Conversation, avatar: Bitmap?) {
        // 签名里不能放 Bitmap 本身：每次都是新对象，identityHashCode 必然变化，
        // 结果是每条消息都推一次动态快捷方式，撞上系统后台限流后气泡就再也不出现。
        val signature = conv.title + "|" + (avatar != null)
        if (conv.pushedSignature == signature) return
        if (createShortcut(conv.shortcutId, conv.title, conv.pkg, avatar)) {
            conv.pushedSignature = signature
        }
    }

    private fun createShortcut(
        shortcutId: String,
        conversation: String,
        originalPkg: String,
        avatar: Bitmap?
    ): Boolean {
        val targetIntent = Intent(this, BubbleActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra(BubbleActivity.EXTRA_PACKAGE_NAME, originalPkg)
            putExtra(BubbleActivity.EXTRA_TITLE, conversation)
            putExtra(BubbleActivity.EXTRA_SHORTCUT_ID, shortcutId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        val icon = avatar
            ?.let { runCatching { IconCompat.createWithBitmap(it) }.getOrNull() }
            ?: IconCompat.createWithResource(this, R.drawable.ic_bubble)

        val shortcut = ShortcutInfoCompat.Builder(this, shortcutId)
            .setShortLabel(conversation)
            .setLongLabel(conversation)
            .setIcon(icon)
            .setIntent(targetIntent)
            .setLongLived(true)                       // 气泡要求：必须是长期快捷方式
            // ShortcutInfoCompat 用的是 androidx 的 Person，
            // 而 MessagingStyle 用的是 android.app.Person —— 两者不是同一个类
            .setPerson(
                CorePerson.Builder()
                    .setName(conversation)
                    .setImportant(true)
                    .build()
            )
            .build()

        // 失败要打出来：动态快捷方式推不上去，气泡根本不会出现，但界面不会报错
        return runCatching { ShortcutManagerCompat.pushDynamicShortcut(this, shortcut) }
            .onFailure { Log.e(TAG, "推送动态快捷方式失败（气泡不会出现）", it) }
            .isSuccess
    }

    /**
     * 通知接管：把源应用那条通知从通知栏撤掉，只留我们的气泡。
     *
     * 先记账再取消 —— 取消会触发 onNotificationRemoved，
     * 不记账的话我们会把刚建好的气泡当成"源通知被划掉"顺手撤掉。
     */
    private fun interceptSourceNotification(sbn: StatusBarNotification) {
        val key = sbn.key ?: return
        if (selfCancelledKeys.size > 64) selfCancelledKeys.clear()
        selfCancelledKeys.add(key)
        runCatching { cancelNotification(key) }
            .onSuccess { Log.i(TAG, "已拦截源通知 key=$key") }
            .onFailure {
                selfCancelledKeys.remove(key)
                Log.w(TAG, "拦截源通知失败 key=$key", it)
            }
    }

    /**
     * 源应用自己会不会弹横幅（heads-up）？
     *
     * 依据是系统给这条通知的 ranking：只有渠道重要性 >= HIGH 才会以横幅形式出现。
     * 判断不出来时返回 false —— 宁可多弹一条，也不要两边都不弹、消息彻底没提示。
     */
    private fun sourceWillShowHeadsUp(sbn: StatusBarNotification): Boolean {
        val rankingMap = currentRanking ?: return false
        val ranking = Ranking()
        if (!rankingMap.getRanking(sbn.key, ranking)) return false
        // 渠道重要性优先；拿不到渠道就退回应用级重要性
        val importance = ranking.channel?.importance ?: ranking.importance
        return importance >= NotificationManager.IMPORTANCE_HIGH
    }

    /** 把会话历史拼成展开面板里的正文 */
    private fun renderMessages(conv: Conversation): String =
        conv.messages.joinToString("\n") { m ->
            if (m.sender.isNullOrBlank() || m.sender == conv.title) m.text
            else m.sender + "：" + m.text
        }

    private fun showBubble(conv: Conversation, avatar: Bitmap?, meName: String): Boolean {
        // 三种情况会把通知行抑制掉（只留气泡）：
        //   1. 用户关掉了横幅（安静模式）
        //   2. 源应用自己会弹横幅，我们不再重复弹（去重）
        // 但气泡本身不可用时绝不能抑制，否则消息会彻底看不见。
        val headsUp = BubbleSettings.headsUp(this)
        val bubblesOk = BubbleSettings.bubblesAllowed(this)
        // 接管模式下源通知会被撤掉，它那条横幅也没了，所以必须由我们来弹
        val intercept = BubbleSettings.interceptSource(this) && bubblesOk
        val sourceHeadsUp =
            !intercept && conv.sourceHeadsUp && BubbleSettings.dedupeHeadsUp(this)
        val suppressShadeRow = (!headsUp || sourceHeadsUp) && bubblesOk
        Log.d(
            TAG,
            "横幅决策: 用户开关=${headsUp} 源应用会弹=${sourceHeadsUp} 接管=${intercept} " +
                "气泡可用=${bubblesOk} → 抑制通知行=${suppressShadeRow}"
        )
        if (!bubblesOk && (!headsUp || sourceHeadsUp)) {
            Log.w(TAG, "气泡被系统/用户禁用，改为保留通知栏那一行 pkg=${conv.pkg}")
        }
        val autoExpand = BubbleSettings.autoExpand(this)

        // 气泡展开的目标：
        //   默认 = 原应用自己的界面。用**我们自建**的 MUTABLE PendingIntent 指向源应用的
        //          launcher Activity —— 系统展开气泡时由 SystemUI 代发，BAL 检查的是
        //          我们而不是源应用，所以不会被 "Background activity launch blocked" 拦掉；
        //          源应用的 Activity 会作为气泡的 task 启动，直接渲染在气泡窗口里。
        //   关闭 = 我们自己的消息面板（历史 + 回复 + 打开应用）。
        // 横屏显示 + 竖屏应用时，气泡窗口会被拉成宽扁、把应用的布局横向撑开（错位），
        // 这种情况改用我们自己的面板 —— 面板是简单纵向布局，任何比例都不变形。
        val fitsBubbleWindow = BubbleGeometry.appFitsBubbleWindow(this, conv.pkg)
        // 横屏 + 竖屏应用：给该应用打开 FORCE_NON_RESIZE_APP，系统就会在宽气泡窗口里
        // 按它自己的竖屏比例 letterbox 显示（两侧加黑边），而不是把布局横向撑开。
        // 拿不到 root 时才退回我们自己的面板，避免畸形拉伸。
        var useSourceApp = BubbleSettings.expandSourceApp(this) && bubblesOk
        if (useSourceApp && !fitsBubbleWindow) {
            val compatOk = AppCompatMode.ensureNonResizeable(conv.pkg)
            Log.i(
                TAG,
                "气泡内容判定 pkg=" + conv.pkg + " 横屏显示=true 横屏应用=false compat=" + compatOk
            )
            if (!compatOk) useSourceApp = false
        } else {
            Log.i(
                TAG,
                "气泡内容判定 pkg=" + conv.pkg +
                    " 横屏显示=" + BubbleGeometry.isDisplayLandscape(this) +
                    " 横屏应用=" + BubbleGeometry.isLandscapeApp(this, conv.pkg) +
                    " → 用原应用界面=" + useSourceApp
            )
        }
        val sourceLaunch = if (useSourceApp) {
            packageManager.getLaunchIntentForPackage(conv.pkg)
        } else {
            null
        }
        val bubbleIntent = (sourceLaunch ?: Intent(this, BubbleActivity::class.java)).apply {
            putExtra(BubbleActivity.EXTRA_PACKAGE_NAME, conv.pkg)
            putExtra(BubbleActivity.EXTRA_TITLE, conv.title)
            putExtra(BubbleActivity.EXTRA_TEXT, renderMessages(conv))
            putExtra(BubbleActivity.EXTRA_SHORTCUT_ID, conv.shortcutId)
            conv.replyAction?.let { action ->
                putExtra(BubbleActivity.EXTRA_REPLY_PENDING_INTENT, action.actionIntent)
                putExtra(
                    BubbleActivity.EXTRA_REPLY_RESULT_KEY,
                    action.remoteInputs?.firstOrNull { it.allowFreeFormInput }?.resultKey
                )
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        // PendingIntent 的 requestCode 用“包名 + 会话”推导：QQ 的 id=5 和微信的 id=5 会撞，
        // 用原通知 id 还会随重发而变，导致点击跳错会话。
        val requestCode = (conv.pkg + "#" + conv.shortcutId).hashCode()

        // ============ 气泡的 PendingIntent **必须 MUTABLE** ============
        // 用 FLAG_IMMUTABLE 时 notify() 会被系统拒绝：
        //   IllegalArgumentException: PendingIntents attached to bubbles must be mutable
        // 系统展开/收起气泡时需要改写它（补 launched-from-bubble 之类的信息）。
        // 这是气泡专属要求，普通通知仍应用 IMMUTABLE。
        val bubblePendingIntent = PendingIntent.getActivity(
            this,
            requestCode,
            bubbleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        val icon = avatar
            ?.let { runCatching { Icon.createWithBitmap(it) }.getOrNull() }
            ?: Icon.createWithResource(this, R.drawable.ic_bubble)

        // 横屏应用（游戏）用 16:9 的矮窗口，否则竖屏窄高窗口会把画面压成上下留黑的中条
        val desiredHeight = BubbleGeometry.desiredHeightDp(this, conv.pkg)

        // BubbleMetadata.Builder(PendingIntent, Icon) 是 API 30 才有的；
        // minSdk 是 29，直接调用会在 Android 10 上 NoSuchMethodError。
        val bubbleData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Notification.BubbleMetadata.Builder(bubblePendingIntent, icon)
                .setDesiredHeight(desiredHeight)
                .setAutoExpandBubble(autoExpand)
                .setSuppressNotification(suppressShadeRow)
                .build()
        } else {
            // Android 10（API 29）上 BubbleMetadata.Builder 只有**无参**构造：
            // Builder(String) 是 API 30 才加的（查 platforms/.../data/api-versions.xml 确认），
            // 所以原来那版在 Android 10 上收到消息会直接 NoSuchMethodError。
            // API 29 的气泡靠「通知的 shortcutId + metadata 的 intent」关联。
            @Suppress("DEPRECATION")
            Notification.BubbleMetadata.Builder()
                .setIcon(icon)
                .setIntent(bubblePendingIntent)
                .setDesiredHeight(desiredHeight)
                .setAutoExpandBubble(autoExpand)
                .setSuppressNotification(suppressShadeRow)
                .build()
        }

        val nm = getSystemService(NotificationManager::class.java)
        val id = bubbleId(conv)

        // 先试带回复动作的版本；源应用的回复 PendingIntent 不满足系统对 RemoteInput
        // 的要求时会被拒绝，这时降级成不带动作的通知，气泡不能丢。
        val posted = runCatching {
            nm.notify(
                id,
                buildBubbleNotification(conv, bubbleData, bubblePendingIntent, meName, true)
            )
        }.fold(
            onSuccess = { true },
            onFailure = { t ->
                Log.w(TAG, "带上原通知的回复动作失败，降级重发", t)
                runCatching {
                    nm.notify(
                        id,
                        buildBubbleNotification(conv, bubbleData, bubblePendingIntent, meName, false)
                    )
                }.onFailure { Log.e(TAG, "气泡通知发送失败", it) }.isSuccess
            }
        )

        // 系统不会因为通知更新去重建已经展开的 Activity，所以主动广播一次：
        // 用户正开着这个气泡时，新消息要能当场看到。
        broadcastBubbleUpdate(conv)
        return posted
    }

    private fun broadcastBubbleUpdate(conv: Conversation) {
        val intent = Intent(BubbleActivity.ACTION_BUBBLE_UPDATED).apply {
            setPackage(packageName)
            putExtra(BubbleActivity.EXTRA_SHORTCUT_ID, conv.shortcutId)
            putExtra(BubbleActivity.EXTRA_TITLE, conv.title)
            putExtra(BubbleActivity.EXTRA_TEXT, renderMessages(conv))
            conv.replyAction?.let { action ->
                putExtra(BubbleActivity.EXTRA_REPLY_PENDING_INTENT, action.actionIntent)
                putExtra(
                    BubbleActivity.EXTRA_REPLY_RESULT_KEY,
                    action.remoteInputs?.firstOrNull { it.allowFreeFormInput }?.resultKey
                )
            }
        }
        runCatching { sendBroadcast(intent) }
            .onFailure { Log.w(TAG, "广播气泡更新失败", it) }
    }

    private fun buildBubbleNotification(
        conv: Conversation,
        bubbleData: Notification.BubbleMetadata,
        bubblePendingIntent: PendingIntent,
        meName: String,
        withReplyAction: Boolean
    ): Notification {
        // MessagingStyle 才是真正的“会话”：系统据此归到会话区，
        // 气泡里也能正确显示发送者。
        val me = Person.Builder().setName(meName).setImportant(true).build()
        val style = Notification.MessagingStyle(me).setConversationTitle(conv.title)
        if (conv.isGroup) style.setGroupConversation(true)

        for (m in conv.messages) {
            val who = Person.Builder()
                .setName(m.sender ?: conv.title)
                .setImportant(true)
                .build()
            style.addMessage(m.text, m.timestamp, who)
        }

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.bubble_ic_notification)
            .setStyle(style)
            .setBubbleMetadata(bubbleData)
            .setShortcutId(conv.shortcutId)
            // 点横幅优先走原应用的意图（直达那个会话），拿不到才退化成打开气泡
            .setContentIntent(conv.contentIntent ?: bubblePendingIntent)
            .setWhen(conv.messages.lastOrNull()?.timestamp ?: conv.postTime)
            .setShowWhen(true)
            .setAutoCancel(true)

        if (withReplyAction) {
            conv.replyAction?.let { builder.addAction(it) }
        }
        return builder.build()
    }

    // ------------------------------------------------------------------
    // 杂项
    // ------------------------------------------------------------------

    /**
     * 只处理白名单里的应用。白名单由用户在设置里勾选，默认是微信 + QQ。
     */
    private fun isTarget(sbn: StatusBarNotification): Boolean {
        // 绝不处理自己的通知，否则气泡会被自己再处理一遍形成自激
        if (sbn.packageName == packageName) return false
        if (BubbleSettings.isWhitelisted(this, sbn.packageName)) return true
        return DEBUG_ACCEPT_SHELL_NOTIFICATIONS && sbn.packageName == "com.android.shell"
    }

    private fun isGroupSummary(sbn: StatusBarNotification): Boolean =
        (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0

    /** Icon 可能是 Bitmap / Uri / 别的应用包里的资源，统一画成 Bitmap 最稳 */
    private fun Icon.toBitmap(): Bitmap? {
        val drawable = runCatching { loadDrawable(this@NotifyListenerService) }
            .onFailure { Log.w(TAG, "读取原通知大图标失败", it) }
            .getOrNull() ?: return null
        return runCatching {
            val w = drawable.intrinsicWidth.coerceIn(1, 512)
            val h = drawable.intrinsicHeight.coerceIn(1, 512)
            val bitmap = createBitmap(w, h)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, w, h)
            drawable.draw(canvas)
            bitmap
        }.onFailure { Log.w(TAG, "绘制原通知大图标失败", it) }.getOrNull()
    }

    @Suppress("DEPRECATION")
    private fun Bundle.parcelableArray(key: String): Array<Parcelable>? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableArray(key, Parcelable::class.java)
        } else {
            getParcelableArray(key)
        }

    @Suppress("DEPRECATION", "UNCHECKED_CAST")
    private fun <T : Parcelable> Bundle.parcelable(key: String, clazz: Class<T>): T? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelable(key, clazz)
        } else {
            getParcelable(key) as? T
        }
}
