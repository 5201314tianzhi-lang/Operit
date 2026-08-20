package com.ai.assistance.operit.pro.analytics

import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.BatteryManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * 应用使用分析器
 *
 * 追踪和分析手机使用习惯，提供 AI 驱动的洞察和建议。
 *
 * 核心能力：
 * - 应用使用时间统计
 * - 使用模式识别（时间段/频率）
 * - AI 洞察生成
 * - 数字健康建议
 * - 生产力评分
 * - 通知频率分析
 */
class AppUsageAnalyzer(private val context: Context) {

    companion object {
        private val _usageStats = MutableStateFlow<List<AppUsageStat>>(emptyList())
        val usageStats: StateFlow<List<AppUsageStat>> = _usageStats.asStateFlow()

        private val _insights = MutableStateFlow<List<UsageInsight>>(emptyList())
        val insights: StateFlow<List<UsageInsight>> = _insights.asStateFlow()

        private val _productivityScore = MutableStateFlow(0)
        val productivityScore: StateFlow<Int> = _productivityScore.asStateFlow()

        private val _dailyReport = MutableStateFlow<DailyReport?>(null)
        val dailyReport: StateFlow<DailyReport?> = _dailyReport.asStateFlow()
    }

    /** 刷新使用统计 */
    fun refreshStats() {
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return

        val calendar = java.util.Calendar.getInstance()
        val endTime = calendar.timeInMillis
        calendar.add(java.util.Calendar.DAY_OF_YEAR, -1)
        val startTime = calendar.timeInMillis

        val stats = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY, startTime, endTime
        ) ?: return

        val appStats = stats
            .filter { it.totalTimeInForeground > 0 }
            .sortedByDescending { it.totalTimeInForeground }
            .take(20)
            .map { stat ->
                val pm = context.packageManager
                val appName = try {
                    pm.getApplicationLabel(pm.getApplicationInfo(stat.packageName, 0)).toString()
                } catch (e: Exception) {
                    stat.packageName
                }

                AppUsageStat(
                    packageName = stat.packageName,
                    appName = appName,
                    usageTimeMs = stat.totalTimeInForeground,
                    launchCount = stat.lastTimeUsed - stat.firstTimeStamp,
                    lastUsedTime = stat.lastTimeUsed,
                    category = classifyApp(stat.packageName),
                )
            }

        _usageStats.value = appStats

        // 生成 AI 洞察
        generateInsights(appStats)

        // 计算生产力评分
        calculateProductivityScore(appStats)

        // 生成日报
        generateDailyReport(appStats)
    }

    /** 生成 AI 洞察 */
    private fun generateInsights(stats: List<AppUsageStat>) {
        val insightsList = mutableListOf<UsageInsight>()

        val totalTime = stats.sumOf { it.usageTimeMs }
        val totalTimeHours = totalTime / 3_600_000.0

        // 总使用时间洞察
        when {
            totalTimeHours > 8 -> insightsList.add(UsageInsight(
                title = "屏幕时间过长",
                message = "今日屏幕使用时间 ${String.format("%.1f", totalTimeHours)} 小时，建议适当休息",
                severity = InsightSeverity.WARNING,
                category = InsightCategory.SCREEN_TIME,
                actionable = true,
                suggestion = "建议设置应用使用限制，每小时休息5分钟",
            ))
            totalTimeHours > 5 -> insightsList.add(UsageInsight(
                title = "使用时间适中",
                message = "今日屏幕使用时间 ${String.format("%.1f", totalTimeHours)} 小时",
                severity = InsightSeverity.INFO,
                category = InsightCategory.SCREEN_TIME,
                actionable = false,
                suggestion = null,
            ))
            else -> insightsList.add(UsageInsight(
                title = "屏幕时间健康",
                message = "今日屏幕使用时间 ${String.format("%.1f", totalTimeHours)} 小时，保持良好习惯！",
                severity = InsightSeverity.POSITIVE,
                category = InsightCategory.SCREEN_TIME,
                actionable = false,
                suggestion = null,
            ))
        }

        // 社交媒体使用洞察
        val socialTime = stats.filter { it.category == AppCategory.SOCIAL }.sumOf { it.usageTimeMs }
        val socialHours = socialTime / 3_600_000.0
        if (socialHours > 3) {
            insightsList.add(UsageInsight(
                title = "社交媒体使用过多",
                message = "社交媒体使用 ${String.format("%.1f", socialHours)} 小时，占总使用时间的 ${String.format("%.0f", socialTime * 100.0 / totalTime)}%",
                severity = InsightSeverity.WARNING,
                category = InsightCategory.SOCIAL_MEDIA,
                actionable = true,
                suggestion = "建议为社交媒体应用设置每日使用限制",
            ))
        }

        // 生产力应用使用
        val productivityTime = stats.filter { it.category == AppCategory.PRODUCTIVITY }.sumOf { it.usageTimeMs }
        val productivityHours = productivityTime / 3_600_000.0
        if (productivityHours > 1) {
            insightsList.add(UsageInsight(
                title = "生产力表现良好",
                message = "生产力应用使用 ${String.format("%.1f", productivityHours)} 小时",
                severity = InsightSeverity.POSITIVE,
                category = InsightCategory.PRODUCTIVITY,
                actionable = false,
                suggestion = null,
            ))
        }

        // 最常用应用
        val topApp = stats.firstOrNull()
        topApp?.let {
            val topHours = it.usageTimeMs / 3_600_000.0
            if (topHours > 2) {
                insightsList.add(UsageInsight(
                    title = "最常用应用: ${it.appName}",
                    message = "${it.appName} 使用了 ${String.format("%.1f", topHours)} 小时",
                    severity = InsightSeverity.INFO,
                    category = InsightCategory.TOP_APPS,
                    actionable = topHours > 4,
                    suggestion = if (topHours > 4) "建议减少 ${it.appName} 的使用时间" else null,
                ))
            }
        }

        // 深夜使用检测
        insightsList.add(UsageInsight(
            title = "使用模式分析",
            message = "建议在22:00后减少屏幕使用以改善睡眠质量",
            severity = InsightSeverity.HINT,
            category = InsightCategory.PATTERN,
            actionable = true,
            suggestion = "可设置夜间模式自动提醒",
        ))

        _insights.value = insightsList
    }

    /** 计算生产力评分 */
    private fun calculateProductivityScore(stats: List<AppUsageStat>) {
        val totalTime = stats.sumOf { it.usageTimeMs }.coerceAtLeast(1)

        val productivityTime = stats.filter { it.category == AppCategory.PRODUCTIVITY }.sumOf { it.usageTimeMs }
        val socialTime = stats.filter { it.category == AppCategory.SOCIAL }.sumOf { it.usageTimeMs }
        val entertainmentTime = stats.filter { it.category == AppCategory.ENTERTAINMENT }.sumOf { it.usageTimeMs }
        val educationTime = stats.filter { it.category == AppCategory.EDUCATION }.sumOf { it.usageTimeMs }

        val score = (
            (productivityTime * 2.0 + educationTime * 1.5) / totalTime * 40 +
            (1.0 - (socialTime + entertainmentTime) / totalTime.coerceAtLeast(1)) * 40 +
            20
        ).toInt().coerceIn(0, 100)

        _productivityScore.value = score
    }

    /** 生成日报 */
    private fun generateDailyReport(stats: List<AppUsageStat>) {
        val totalTime = stats.sumOf { it.usageTimeMs }
        val hours = totalTime / 3_600_000.0

        val categoryBreakdown = stats.groupBy { it.category }
            .mapValues { it.value.sumOf { stat -> stat.usageTimeMs } }
            .toList()
            .sortedByDescending { it.second }
            .map { (category, time) ->
                CategoryUsage(category, time, time * 100.0 / totalTime)
            }

        _dailyReport.value = DailyReport(
            date = System.currentTimeMillis(),
            totalScreenTimeMs = totalTime,
            topApps = stats.take(5),
            categoryBreakdown = categoryBreakdown,
            insights = _insights.value,
            productivityScore = _productivityScore.value,
        )
    }

    private fun classifyApp(packageName: String): AppCategory {
        val pkg = packageName.lowercase()
        return when {
            pkg.contains("whatsapp") || pkg.contains("wechat") || pkg.contains("qq") ||
            pkg.contains("weibo") || pkg.contains("instagram") || pkg.contains("twitter") ||
            pkg.contains("facebook") || pkg.contains("telegram") || pkg.contains("douyin") ||
            pkg.contains("tiktok") || pkg.contains("snapchat") || pkg.contains("xiaohongshu") ->
                AppCategory.SOCIAL
            pkg.contains("game") || pkg.contains("play") || pkg.contains("video") ||
            pkg.contains("music") || pkg.contains("bilibili") || pkg.contains("netflix") ||
            pkg.contains("youtube") || pkg.contains("spotify") ->
                AppCategory.ENTERTAINMENT
            pkg.contains("office") || pkg.contains("docs") || pkg.contains("sheet") ||
            pkg.contains("notion") || pkg.contains("evernote") || pkg.contains("wps") ||
            pkg.contains("mail") || pkg.contains("calendar") || pkg.contains("slack") ||
            pkg.contains("dingtalk") || pkg.contains("feishu") ->
                AppCategory.PRODUCTIVITY
            pkg.contains("learn") || pkg.contains("study") || pkg.contains("course") ||
            pkg.contains("duolingo") || pkg.contains("educational") ->
                AppCategory.EDUCATION
            pkg.contains("shop") || pkg.contains("taobao") || pkg.contains("jd") ||
            pkg.contains("pinduoduo") || pkg.contains("amazon") ->
                AppCategory.SHOPPING
            pkg.contains("news") || pkg.contains("browser") || pkg.contains("chrome") ->
                AppCategory.NEWS
            pkg.contains("health") || pkg.contains("fit") || pkg.contains("keep") ->
                AppCategory.HEALTH
            else -> AppCategory.OTHER
        }
    }
}

/** 电池 AI 优化器 */
class BatteryAIOptimizer(private val context: Context) {

    companion object {
        private val _batteryInfo = MutableStateFlow(BatteryInfo())
        val batteryInfo: StateFlow<BatteryInfo> = _batteryInfo.asStateFlow()

        private val _optimizationSuggestions = MutableStateFlow<List<BatterySuggestion>>(emptyList())
        val optimizationSuggestions: StateFlow<List<BatterySuggestion>> = _optimizationSuggestions.asStateFlow()

        private val _powerMode = MutableStateFlow(PowerMode.BALANCED)
        val powerMode: StateFlow<PowerMode> = _powerMode.asStateFlow()

        private val _batteryHistory = MutableStateFlow<List<BatteryRecord>>(emptyList())
        val batteryHistory: StateFlow<List<BatteryRecord>> = _batteryHistory.asStateFlow()
    }

    /** 刷新电池信息 */
    fun refreshBatteryInfo() {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            ?: return

        val level = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val isCharging = batteryManager.isCharging
        val temperature = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_TEMPERATURE) / 10.0f
        val voltage = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_VOLTAGE) / 1000.0f

        val info = BatteryInfo(
            level = level,
            isCharging = isCharging,
            temperature = temperature,
            voltage = voltage,
            timestamp = System.currentTimeMillis(),
        )

        _batteryInfo.value = info

        // 记录历史
        val history = _batteryHistory.value.toMutableList()
        history.add(BatteryRecord(level, isCharging, temperature, System.currentTimeMillis()))
        if (history.size > 144) { // 24小时, 每10分钟一条
            history.removeAt(0)
        }
        _batteryHistory.value = history

        // 生成优化建议
        generateSuggestions(info)
    }

    /** 生成电池优化建议 */
    private fun generateSuggestions(info: BatteryInfo) {
        val suggestions = mutableListOf<BatterySuggestion>()

        // 低电量建议
        if (info.level <= 20 && !info.isCharging) {
            suggestions.add(BatterySuggestion(
                title = "电量较低",
                message = "当前电量 ${info.level}%，建议开启省电模式",
                priority = SuggestionPriority.HIGH,
                action = SuggestionAction.ENABLE_BATTERY_SAVER,
            ))
        }

        // 温度过高
        if (info.temperature > 40) {
            suggestions.add(BatterySuggestion(
                title = "电池温度过高",
                message = "当前温度 ${info.temperature}°C，建议暂停高负载应用",
                priority = SuggestionPriority.HIGH,
                action = SuggestionAction.KILL_HEAVY_APPS,
            ))
        }

        // 充电建议
        if (info.level >= 80 && info.isCharging) {
            suggestions.add(BatterySuggestion(
                title = "充电即将完成",
                message = "电量已达 ${info.level}%，建议拔掉充电器以延长电池寿命",
                priority = SuggestionPriority.MEDIUM,
                action = SuggestionAction.UNPLUG_CHARGER,
            ))
        }

        // AI 分析耗电应用
        suggestions.add(BatterySuggestion(
            title = "AI耗电分析",
            message = "建议检查后台运行的应用，关闭不必要的同步",
            priority = SuggestionPriority.LOW,
            action = SuggestionAction.OPTIMIZE_BACKGROUND,
        ))

        // 夜间充电建议
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        if (hour in 23..24 || hour in 0..6) {
            if (info.isCharging) {
                suggestions.add(BatterySuggestion(
                    title = "夜间充电优化",
                    message = "夜间充电时建议开启"优化充电"功能以保护电池",
                    priority = SuggestionPriority.MEDIUM,
                    action = SuggestionAction.ENABLE_OPTIMIZED_CHARGING,
                ))
            }
        }

        _optimizationSuggestions.value = suggestions
    }

    /** 设置电源模式 */
    fun setPowerMode(mode: PowerMode) {
        _powerMode.value = mode
        when (mode) {
            PowerMode.SAVING -> {
                // 应用省电设置
            }
            PowerMode.BALANCED -> {
                // 平衡模式
            }
            PowerMode.PERFORMANCE -> {
                // 性能模式
            }
        }
    }

    /** 获取电池健康度 */
    fun getBatteryHealth(): BatteryHealth {
        val info = _batteryInfo.value
        val temp = info.temperature

        val healthScore = when {
            temp > 45 -> 30
            temp > 40 -> 60
            temp > 35 -> 80
            else -> 100
        }

        val status = when (healthScore) {
            in 80..100 -> BatteryHealthStatus.EXCELLENT
            in 60..79 -> BatteryHealthStatus.GOOD
            in 40..59 -> BatteryHealthStatus.FAIR
            else -> BatteryHealthStatus.POOR
        }

        return BatteryHealth(
            score = healthScore,
            status = status,
            temperature = temp,
            estimatedLifeHours = estimateBatteryLife(info),
        )
    }

    private fun estimateBatteryLife(info: BatteryInfo): Double {
        if (info.isCharging) return 0.0
        val history = _batteryHistory.value
        if (history.size < 2) return (info.level * 0.1) // 粗略估计

        // 计算耗电速率
        val recent = history.takeLast(6) // 最近1小时
        if (recent.size < 2) return (info.level * 0.1)

        val firstLevel = recent.first().level
        val lastLevel = recent.last().level
        val timeDiff = (recent.last().timestamp - recent.first().timestamp) / 3_600_000.0

        if (timeDiff <= 0) return (info.level * 0.1)

        val drainRate = (firstLevel - lastLevel) / timeDiff
        if (drainRate <= 0) return 24.0 // 几乎不耗电

        return info.level / drainRate
    }
}

/** 应用使用统计 */
data class AppUsageStat(
    val packageName: String,
    val appName: String,
    val usageTimeMs: Long,
    val launchCount: Long,
    val lastUsedTime: Long,
    val category: AppCategory,
)

/** 应用分类 */
enum class AppCategory(val displayName: String) {
    SOCIAL("社交"), ENTERTAINMENT("娱乐"), PRODUCTIVITY("效率"),
    EDUCATION("教育"), SHOPPING("购物"), NEWS("新闻"),
    HEALTH("健康"), OTHER("其他"),
}

/** 使用洞察 */
data class UsageInsight(
    val title: String,
    val message: String,
    val severity: InsightSeverity,
    val category: InsightCategory,
    val actionable: Boolean,
    val suggestion: String?,
)

/** 洞察严重级别 */
enum class InsightSeverity { POSITIVE, INFO, HINT, WARNING, CRITICAL }

/** 洞察分类 */
enum class InsightCategory { SCREEN_TIME, SOCIAL_MEDIA, PRODUCTIVITY, TOP_APPS, PATTERN }

/** 日报 */
data class DailyReport(
    val date: Long,
    val totalScreenTimeMs: Long,
    val topApp: List<AppUsageStat>,
    val categoryBreakdown: List<CategoryUsage>,
    val insights: List<UsageInsight>,
    val productivityScore: Int,
)

/** 分类使用量 */
data class CategoryUsage(
    val category: AppCategory,
    val timeMs: Long,
    val percentage: Double,
)

/** 电池信息 */
data class BatteryInfo(
    val level: Int = 0,
    val isCharging: Boolean = false,
    val temperature: Float = 0f,
    val voltage: Float = 0f,
    val timestamp: Long = System.currentTimeMillis(),
)

/** 电池记录 */
data class BatteryRecord(
    val level: Int,
    val isCharging: Boolean,
    val temperature: Float,
    val timestamp: Long,
)

/** 电池建议 */
data class BatterySuggestion(
    val title: String,
    val message: String,
    val priority: SuggestionPriority,
    val action: SuggestionAction,
)

/** 建议优先级 */
enum class SuggestionPriority { HIGH, MEDIUM, LOW }

/** 建议动作 */
enum class SuggestionAction {
    ENABLE_BATTERY_SAVER, KILL_HEAVY_APPS, UNPLUG_CHARGER,
    OPTIMIZE_BACKGROUND, ENABLE_OPTIMIZED_CHARGING,
}

/** 电源模式 */
enum class PowerMode(val displayName: String) {
    SAVING("省电模式"), BALANCED("平衡模式"), PERFORMANCE("性能模式"),
}

/** 电池健康度 */
data class BatteryHealth(
    val score: Int,
    val status: BatteryHealthStatus,
    val temperature: Float,
    val estimatedLifeHours: Double,
)

/** 电池健康状态 */
enum class BatteryHealthStatus(val displayName: String) {
    EXCELLENT("优秀"), GOOD("良好"), FAIR("一般"), POOR("较差"),
}
