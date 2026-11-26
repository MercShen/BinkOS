package com.github.gezimos.inkos.helper

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.lang.reflect.Method

/**
 * 大我（Bigme）墨水屏刷新动画接口。
 *
 * 调用链（厂商系统内置，非公开接口，靠反射 + HiddenApiBypass 穿 hidden API 限制）：
 *
 * ```
 * xrz.framework.manager.XrzEinkManager(context)
 *   -> getDisplayPolicyManager()
 *      -> setEinkAnimationTimeout(int mode, long durationMs)
 * ```
 *
 * mode 是「面板坐标系」的擦除方向：1=从左到右 2=从右到左 3=从上到下 4=从下到上，
 * 屏幕旋转后需要按 [PANEL_MODES] 补偿。动画只在 App 刷新波形为 Regal(180) 或
 * HD256(0x800000B1) 时可见，因此 [init] 里会把本包的刷新模式拉到 Regal（与大我自家
 * 阅读器一致）。
 *
 * 所有动画都只用这一个**临时**接口下发（方向擦除 1–4、波纹开关 9/10、0 = 无），
 * 到期由厂商服务自己恢复，**不写持久策略** —— 尤其不碰 e-ink center 里「每应用动画」
 * 那个字段（`appEinkAnimation`），否则等于替用户把它常开，之后每次屏幕变化都会播动画。
 *
 * 非大我设备 init() 失败后所有调用都是空操作。
 */
object BigmeEink {

    /** 手指滑动方向（屏幕坐标系），base 是 0° 旋转下的面板方向值。 */
    enum class Direction(val base: Int) {
        L2R(1), // 从左到右（手指向右）
        R2L(2), // 从右到左（手指向左）
        T2B(3), // 从上到下（手指向下）
        B2T(4); // 从下到上（手指向上）
    }

    private const val TAG = "BigmeEink"
    private const val CLASS_NAME = "xrz.framework.manager.XrzEinkManager"
    private const val ANIMATION_METHOD = "setEinkAnimationTimeout"
    private const val REGAL_MODE = 180
    private const val HD_256_MODE = 0x800000B1.toInt()
    /** 刷新波形：Regal（翻页动画可见所必需）。大我 Refresh mode 菜单里的 "Regal" = 180。 */
    const val MODE_REGAL = 180
    /** 刷新波形：大我菜单里的 "Extreme"（=179，交互跟手，但翻页动画不可见）。 */
    const val MODE_SUPER = 179
    private const val PAGE_TURN_STYLE_RIPPLE = 1
    /** 厂商动画样式：10 = 中间→圆周（打开），9 = 圆周→中间（关闭），0 = 无。 */
    private const val RIPPLE_OPEN = 10
    private const val RIPPLE_CLOSE = 9
    /** Ripple 一次界面切换要跨过 Compose 首帧与面板刷新，窗口比方向擦除长。 */
    private const val RIPPLE_DURATION_MS = 800L
    /** 下发 0 清掉临时样式时的短窗口（1ms 服务端会拒绝）。 */
    private const val RESET_DURATION_MS = 200L

    /**
     * 触发后动画模式的保持时长：必须覆盖「下发 → 面板下一次 Regal 全屏刷新（mode=b4）」的间隔。
     *
     * 擦除只会在这种整屏提交上可见（看 hwcomposer 的 `[DRMDEV] eink animation mode` 行）。
     * 2026-11-24 真机实测 b4 相对下发延迟 +0.31s / +0.35s / +0.65s / +0.68s —— 面板上一条提交
     * 还在跑，下一次只能排队；100ms / 280ms 会漏，300ms 实测开始偶发丢。
     *
     * 定值 350ms：真机上 325 / 420 / 450 / 800ms 曾稳定（300ms 偶发丢），但 2026-11-26 日常使用
     * 反馈 325ms 仍会偶发漏掉排队更久的 b4 提交，上调到 350ms，仍是稳定值里取最短，减少窗口内
     * 时钟、角标等无关刷新被带上动画的机会。若某个机型上又出现偶发丢动画，先怀疑这个窗口。
     */
    private const val DURATION_MS = 350L

    /** 手势与导航之间的时间窗：超过这个间隔就不再认为动画由该手势触发。 */
    private const val SWIPE_WINDOW_MS = 600L

    /** 连击节流：只防止同一次操作被重复下发；面板刷新的覆盖由 [DURATION_MS] 负责。 */
    private const val THROTTLE_MS = 300L

    /** 旋转 0/90/180/270 → 四个方向对应的面板模式（行 = 旋转档位，列 = Direction.base - 1）。 */
    private val PANEL_MODES = arrayOf(
        intArrayOf(1, 2, 3, 4), // 0°
        intArrayOf(3, 4, 2, 1), // 90°
        intArrayOf(2, 1, 4, 3), // 180°
        intArrayOf(4, 3, 1, 2), // 270°
    )

    @Volatile private var policy: Any? = null
    @Volatile private var animationMethod: Method? = null
    @Volatile private var policyGetter: Method? = null
    @Volatile private var refreshModeSetter: Method? = null
    @Volatile private var initialized = false
    @Volatile private var available = false

    @Volatile private var lastSwipe: Direction? = null
    @Volatile private var lastSwipeAt = 0L
    private var lastTriggerAt = 0L

    val isAvailable: Boolean get() = available

    /** 在 Application/Activity 启动时调用一次；非大我设备返回 false。 */
    fun init(context: Context): Boolean {
        if (initialized) return available
        synchronized(this) {
            if (initialized) return available
            initialized = true
            available = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    // 只豁免大我框架包，避免全局放开 hidden API
                    HiddenApiBypass.addHiddenApiExemptions("Lxrz/framework/")
                }
                val cls = Class.forName(CLASS_NAME)
                val manager = cls.getDeclaredConstructor(Context::class.java)
                    .apply { isAccessible = true }
                    .newInstance(context.applicationContext)

                val p = cls.getMethod("getDisplayPolicyManager")
                    .apply { isAccessible = true }
                    .invoke(manager)
                    ?: throw IllegalStateException("getDisplayPolicyManager() == null")

                val m = p.javaClass.getMethod(
                    ANIMATION_METHOD,
                    Int::class.javaPrimitiveType,
                    Long::class.javaPrimitiveType
                ).apply { isAccessible = true }

                policy = p
                animationMethod = m
                policyGetter = runCatching {
                    p.javaClass.getMethod("getPolicyByPackage", String::class.java)
                        .apply { isAccessible = true }
                }.getOrNull()
                refreshModeSetter = runCatching {
                    p.javaClass.getMethod(
                        "setRefreshModeForPackage",
                        String::class.java,
                        Int::class.javaPrimitiveType
                    ).apply { isAccessible = true }
                }.getOrNull()

                Log.i(TAG, "Bigme e-ink animation ready")
                true
            } catch (t: Throwable) {
                Log.i(TAG, "not a Bigme device / unavailable: ${t.javaClass.simpleName}: ${t.message}")
                false
            }
        }
        if (available) ensureAnimationVisible(context)
        return available
    }

    /**
     * 让动画真的可见：本包刷新波形必须是 Regal(180) 或 HD256。已满足则不写系统策略；
     * 否则写一次 Regal（持久，可在系统设置里改回）。
     */
    private fun ensureAnimationVisible(context: Context) {
        val getter = policyGetter ?: return
        val setter = refreshModeSetter ?: return
        try {
            val current = runCatching {
                @Suppress("UNCHECKED_CAST")
                val p = getter.invoke(policy, context.packageName)
                p?.javaClass?.getField("refreshMode")?.getInt(p)
            }.getOrNull()
            if (current == REGAL_MODE || current == HD_256_MODE) {
                Log.i(TAG, "refresh mode already animation-capable: $current")
                return
            }
            setter.invoke(policy, context.packageName, REGAL_MODE)
            Log.i(TAG, "refresh mode $current -> $REGAL_MODE for ${context.packageName}")
        } catch (t: Throwable) {
            Log.w(TAG, "ensureAnimationVisible failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /**
     * 直接改写本包的刷新波形（Regal / 极速 …）。用于「交互界面临时切极速」，
     * 切回 Regal 才能继续看到翻页动画。非大我设备返回 false。
     */
    fun setPackageRefreshMode(context: Context, mode: Int): Boolean {
        if (!init(context)) return false
        val target = policy ?: return false
        val setter = refreshModeSetter ?: return false
        return try {
            val result = setter.invoke(target, context.packageName, mode)
            Log.i(TAG, "refreshMode=$mode pkg=${context.packageName} result=$result")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "setRefreshModeForPackage($mode) failed: ${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }

    /** 记录方向手势；由手势层在识别出方向时调用，供导航那一刻取用。 */
    fun noteSwipe(direction: Direction) {
        lastSwipe = direction
        lastSwipeAt = SystemClock.uptimeMillis()
    }

    /** 最近一次手势方向（仍在时间窗内），没有则返回 null。 */
    fun recentSwipeOrNull(): Direction? = recentSwipe()

    /**
     * 打开一个界面。
     *
     * Ripple 样式：挂临时样式 10（与方向无关），**绝不回退 Directional** ——
     * 回退会让「打开/返回」在 Ripple 样式下突然变成方向式擦除。
     * Directional 样式才需要 [direction]。
     */
    fun onScreenOpened(context: Context, direction: Direction? = null) {
        val ctx = context.applicationContext
        if (isRippleStyle(ctx)) {
            triggerType(ctx, RIPPLE_OPEN, RIPPLE_DURATION_MS, "open ripple")
            return
        }
        if (direction != null) trigger(ctx, direction, "open")
    }

    /** 关闭同一个界面。Ripple 样式挂 9；Directional 样式按关闭方向播放。 */
    fun onScreenClosed(context: Context, direction: Direction? = null) {
        val ctx = context.applicationContext
        if (isRippleStyle(ctx)) {
            triggerType(ctx, RIPPLE_CLOSE, RIPPLE_DURATION_MS, "close ripple")
            return
        }
        if (direction != null) trigger(ctx, direction, "close")
    }

    private fun isRippleStyle(context: Context): Boolean = try {
        com.github.gezimos.inkos.data.Prefs(context).einkPageTurnAnimationStyle == PAGE_TURN_STYLE_RIPPLE
    } catch (_: Throwable) {
        false
    }

    /**
     * 清掉当前挂着的临时动画（切回 Directional / 关掉开关时用）。
     *
     * 走临时接口下发 0：到期厂商自己恢复原策略，**不写持久策略** —— e-ink center 里
     * 「每应用动画」保持用户自己的值，不会被这里改成 0。
     */
    fun resetTempAnimation(context: Context) {
        triggerType(context.applicationContext, 0, RESET_DURATION_MS, "reset temp animation")
    }

    /**
     * 主页收藏应用列表翻页。方向由「翻到哪一页」决定，与手指方向无关：
     * 下一页 = 新内容自下而上进入（4），上一页 = 自上而下（3）。
     *
     * 主页翻页是唯一保留 Directional 的场景；临时动画是单槽，这次翻页会直接覆盖
     * 仍挂着的 Ripple 样式，不需要额外清理。
     */
    fun onHomePageTurn(context: Context, forward: Boolean) {
        trigger(
            context.applicationContext,
            if (forward) Direction.B2T else Direction.T2B,
            if (forward) "home page next" else "home page prev",
        )
    }

    /**
     * 全刷（整屏清残影）。走厂商的 static `XrzEinkManager.forceGlobalRefresh(int)`：
     * native 侧会写 `vendor.xrz.force_global_refresh_mode` 并触发一次整屏重绘，
     * HWC 随之执行 clean。实测系统自己的全刷用的 mode 是 0xb1（=177，HD）。
     *
     * @param mode 刷新波形，建议 [MODE_HD]（177，系统全刷实测值）或 [MODE_CLEAN]（176）
     */
    fun forceGlobalRefresh(context: Context, mode: Int = MODE_HD): Boolean {
        if (!init(context)) return false
        return try {
            val clazz = Class.forName(CLASS_NAME)
            val method = clazz.getMethod("forceGlobalRefresh", Int::class.javaPrimitiveType)
                .apply { isAccessible = true }
            method.invoke(null, mode)
            Log.i(TAG, "forceGlobalRefresh($mode) invoked")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "forceGlobalRefresh failed: ${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }

    /** 系统全刷实测使用的波形（hwcomposer: handle delay clean,globalRefreshMode=b1）。 */
    const val MODE_HD = 177
    const val MODE_CLEAN = 176
    /** GC16：实测唯一能产生可见全刷的波形 */
    const val MODE_GC16 = 4

    private fun recentSwipe(): Direction? =
        lastSwipe?.takeIf { SystemClock.uptimeMillis() - lastSwipeAt <= SWIPE_WINDOW_MS }

    private fun trigger(
        context: Context,
        direction: Direction,
        reason: String,
        durationMs: Long = DURATION_MS,
    ) {
        if (!init(context)) {
            Log.d(TAG, "eink animation $reason dir=${direction.name} skipped: vendor API unavailable")
            return
        }

        val now = SystemClock.uptimeMillis()
        if (now - lastTriggerAt < THROTTLE_MS) {
            Log.d(TAG, "eink animation throttled: $reason dir=${direction.name}")
            return
        }
        lastTriggerAt = now

        val rotation = rotationOf(context)
        val mode = panelMode(direction.base, rotation)
        triggerType(
            context,
            mode,
            durationMs,
            "$reason dir=${direction.name} rotation=$rotation panelMode=$mode",
        )
    }

    /**
     * 按厂商动画样式下发**临时**窗口：1–4 = 方向擦除，9/10 = Ripple 打开/关闭，0 = 无。
     *
     * 只用临时接口（[ANIMATION_METHOD]）—— 到期厂商服务自己恢复，不写持久策略，也不碰
     * e-ink center 里「每应用动画」那个字段。单槽语义：后一次调用会覆盖前一次的方向/样式。
     */
    private fun triggerType(context: Context, type: Int, durationMs: Long, reason: String) {
        if (!init(context)) {
            Log.d(TAG, "eink animation $reason type=$type duration=${durationMs}ms skipped: vendor API unavailable")
            return
        }
        val method = animationMethod ?: return
        val target = policy ?: return
        try {
            val result = method.invoke(target, type, durationMs)
            Log.i(TAG, "eink animation $reason type=$type duration=${durationMs}ms -> $result")
        } catch (t: Throwable) {
            Log.w(TAG, "invoke($type) failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /** 0° 下的方向值 → 当前屏幕旋转下的面板模式。 */
    private fun panelMode(base: Int, rotation: Int): Int {
        val row = PANEL_MODES[((rotation % 4) + 4) % 4]
        return row[(base - 1).coerceIn(0, 3)]
    }

    private fun rotationOf(context: Context): Int {
        return try {
            @Suppress("DEPRECATION")
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            @Suppress("DEPRECATION")
            wm.defaultDisplay.rotation
        } catch (_: Throwable) {
            0
        }
    }
}
