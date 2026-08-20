package com.ai.assistance.operit.pro.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 智能通知 AI 服务
 *
 * 实时监听系统通知，通过 AI 分析通知内容，提供智能回复建议、
 * 自动分类、优先级排序和主动提醒功能。
 *
 * 核心能力：
 * - 实时通知捕获与解析
 * - AI 驱动的通知分类（工作/社交/促销/系统）
 * - 智能回复建议生成
 * - 通知优先级排序
 * - 重要通知主动提醒
 * - 通知历史记录与统计
 */
class SmartNotificationService : NotificationListenerService() {

    companion object {
        const val CHANNEL_ID = "smart_notification_ai"
        const val CHANNEL_NAME = "智能通知AI"
        const val MAX_HISTORY_SIZE = 500

        private val _notificationHistory = MutableStateFlow<List<SmartNotification>>(emptyList())
        val notificationHistory: StateFlow<List<SmartNotification>> = _notificationHistory

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning

        private val _pendingActions = ConcurrentLinkedQueue<NotificationAction>()

        fun getPendingActions(): List<NotificationAction> = _pendingActions.toList()
        fun clearPendingActions() { _pendingActions.clear() }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onListenerConnected() {
        super.onListenerConnected()
        _isRunning.value = true
        createNotificationChannel()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        _isRunning.value = false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        sbn ?: return

        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return

        val title = extras.getString("android.title") ?: ""
        val text = extras.getString("android.text") ?: ""
        val bigText = extras.getCharSequence("android.bigText")?.toString() ?: text
        val packageName = sbn.packageName ?: ""

        // 跳过自身通知
        if (packageName.contains("operit")) return

        val smartNotification = SmartNotification(
            id = sbn.id,
            packageName = packageName,
            title = title,
            text = bigText,
            timestamp = System.currentTimeMillis(),
            category = NotificationClassifier.classify(packageName, title, text),
            priority = NotificationPriorityAnalyzer.analyze(title, text, packageName),
            suggestedReplies = SmartReplyGenerator.generate(packageName, title, text),
            isImportant = PriorityRules.isImportant(packageName, title, text),
        )

        // 添加到历史
        val currentHistory = _notificationHistory.value.toMutableList()
        currentHistory.add(0, smartNotification)
        if (currentHistory.size > MAX_HISTORY_SIZE) {
            currentHistory.removeAt(currentHistory.size - 1)
        }
        _notificationHistory.value = currentHistory

        // 重要通知主动提醒
        if (smartNotification.isImportant) {
            showAiNotification(smartNotification)
        }

        // 添加待处理动作
        if (smartNotification.suggestedReplies.isNotEmpty()) {
            _pendingActions.add(
                NotificationAction(
                    notificationId = smartNotification.id,
                    packageName = packageName,
                    suggestedReplies = smartNotification.suggestedReplies,
                    timestamp = System.currentTimeMillis(),
                )
            )
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        // 从历史中标记为已读
        sbn?.let {
            val currentHistory = _notificationHistory.value.toMutableList()
            currentHistory.find { it.id == sbn.id }?.let { notif ->
                val index = currentHistory.indexOf(notif)
                currentHistory[index] = notif.copy(isRead = true)
                _notificationHistory.value = currentHistory
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "AI智能通知分析与服务"
                enableVibration(true)
                enableLights(true)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun showAiNotification(notification: SmartNotification) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val replyText = if (notification.suggestedReplies.isNotEmpty()) {
            "建议回复: ${notification.suggestedReplies.first()}"
        } else {
            notification.text.take(50)
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("[AI] ${notification.title}")
            .setContentText(replyText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(replyText))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)

        manager.notify(notification.id + 10000, builder.build())
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        _isRunning.value = false
    }
}

/** 智能通知数据模型 */
data class SmartNotification(
    val id: Int,
    val packageName: String,
    val title: String,
    val text: String,
    val timestamp: Long,
    val category: NotificationCategory,
    val priority: NotificationPriority,
    val suggestedReplies: List<String>,
    val isImportant: Boolean = false,
    val isRead: Boolean = false,
    val aiSummary: String? = null,
)

/** 通知分类 */
enum class NotificationCategory(val displayName: String) {
    WORK("工作"),
    SOCIAL("社交"),
    SHOPPING("购物"),
    PROMOTION("促销"),
    SYSTEM("系统"),
    NEWS("新闻"),
    FINANCE("金融"),
    HEALTH("健康"),
    OTHER("其他"),
}

/** 通知优先级 */
enum class NotificationPriority(val level: Int) {
    URGENT(4),
    HIGH(3),
    NORMAL(2),
    LOW(1),
    SPAM(0),
}

/** 通知动作 */
data class NotificationAction(
    val notificationId: Int,
    val packageName: String,
    val suggestedReplies: List<String>,
    val timestamp: Long,
)

/** 通知分类器 */
object NotificationClassifier {
    fun classify(packageName: String, title: String, text: String): NotificationCategory {
        val pkg = packageName.lowercase()
        val content = "$title $text".lowercase()

        return when {
            // 工作
            pkg.contains("mail") || pkg.contains("outlook") || pkg.contains("teams") ||
            pkg.contains("slack") || pkg.contains("dingtalk") || pkg.contains("feishu") ||
            pkg.contains("tencent.wework") -> NotificationCategory.WORK

            // 社交
            pkg.contains("whatsapp") || pkg.contains("telegram") || pkg.contains("messenger") ||
            pkg.contains("wechat") || pkg.contains("qq") || pkg.contains("weibo") ||
            pkg.contains("instagram") || pkg.contains("twitter") || pkg.contains("snapchat") ||
            pkg.contains("facebook") -> NotificationCategory.SOCIAL

            // 购物
            pkg.contains("taobao") || pkg.contains("jd") || pkg.contains("pinduoduo") ||
            pkg.contains("amazon") || pkg.contains("shop") || pkg.contains("tmall") ->
                NotificationCategory.SHOPPING

            // 促销
            content.contains("优惠") || content.contains("折扣") || content.contains("promotion") ||
            content.contains("discount") || content.contains("sale") || content.contains("限时") ->
                NotificationCategory.PROMOTION

            // 新闻
            pkg.contains("news") || content.contains("breaking") || content.contains("新闻") ->
                NotificationCategory.NEWS

            // 金融
            pkg.contains("bank") || pkg.contains("pay") || pkg.contains("alipay") ||
            pkg.contains("wechatpay") || content.contains("余额") || content.contains("转账") ||
            content.contains("账单") -> NotificationCategory.FINANCE

            // 健康
            pkg.contains("health") || pkg.contains("fit") || content.contains("步数") ||
            content.contains("心率") || content.contains("运动") -> NotificationCategory.HEALTH

            // 系统
            pkg.contains("android") || pkg.contains("system") || pkg.contains("com.google") ->
                NotificationCategory.SYSTEM

            else -> NotificationCategory.OTHER
        }
    }
}

/** 优先级分析器 */
object NotificationPriorityAnalyzer {
    fun analyze(title: String, text: String, packageName: String): NotificationPriority {
        val content = "$title $text".lowercase()

        return when {
            content.contains("紧急") || content.contains("urgent") || content.contains("重要") ||
            content.contains("important") || content.contains("验证码") || content.contains("verification") ||
            content.contains("验证") -> NotificationPriority.URGENT

            content.contains("请回复") || content.contains("请确认") || content.contains("meeting") ||
            content.contains("会议") || content.contains("deadline") || content.contains("截止") ->
                NotificationPriority.HIGH

            content.contains("优惠") || content.contains("promotion") || content.contains("广告") ->
                NotificationPriority.SPAM

            else -> NotificationPriority.NORMAL
        }
    }
}

/** 智能回复生成器 */
object SmartReplyGenerator {
    fun generate(packageName: String, title: String, text: String): List<String> {
        val content = text.lowercase()
        val replies = mutableListOf<String>()

        // 验证码类
        if (content.contains("验证码") || content.contains("verification") || content.contains("code")) {
            val code = Regex("\\d{4,6}").find(content)?.value
            if (code != null) {
                replies.add("验证码: $code")
            }
        }

        // 即时通讯类
        if (packageName.contains("wechat") || packageName.contains("whatsapp") ||
            packageName.contains("telegram") || packageName.contains("qq")) {
            when {
                content.contains("在吗") || content.contains("你好") || content.contains("hello") ->
                    replies.addAll(listOf("在的，请说", "你好，有什么可以帮你的？"))
                content.contains("谢谢") || content.contains("thanks") ->
                    replies.addAll(listOf("不客气", "不用谢"))
                content.contains("再见") || content.contains("bye") ->
                    replies.add("再见！")
                content.contains("什么时候") || content.contains("几点") ->
                    replies.add("稍等我确认一下时间")
            }
        }

        // 工作类
        if (packageName.contains("mail") || packageName.contains("dingtalk")) {
            if (content.contains("会议") || content.contains("meeting")) {
                replies.addAll(listOf("收到，我会准时参加", "好的，已记下会议时间"))
            }
            if (content.contains("请确认") || content.contains("please confirm")) {
                replies.add("已确认，谢谢")
            }
        }

        // 通用回复
        if (replies.isEmpty()) {
            replies.add("收到")
        }

        return replies
    }
}

/** 优先级规则 */
object PriorityRules {
    private val importantKeywords = setOf(
        "验证码", "紧急", "重要", "urgent", "important", "verification",
        "账单", "余额", "转账", "deadline", "截止", "到期", "expired",
        "会议", "meeting", "面试", "interview",
    )

    private val importantPackages = setOf(
        "com.android.phone", "com.android.messaging",
        "com.tencent.mm", "com.mobilectrl.dingtalk",
    )

    fun isImportant(packageName: String, title: String, text: String): Boolean {
        val content = "$title $text".lowercase()
        return importantKeywords.any { content.contains(it.lowercase()) } ||
            importantPackages.any { packageName.contains(it) }
    }
}
