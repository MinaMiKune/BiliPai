package com.android.purebilibili.core.ui.performance

import androidx.metrics.performance.FrameData
import androidx.metrics.performance.StateInfo
import com.android.purebilibili.core.ui.adaptive.MotionTier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 性能契约：`onFrame` 由 JankStats 每帧回调驱动（`MainActivity.onStart` → `onStop` 之间
 * 持续调用，120Hz 设备上每秒 120 次），因此该路径必须是「稳态零分配、零 StateFlow 写入」。
 *
 * 行为断言仍在 [RuntimeVisualGuardTrackerTest]；这里只固定每帧成本相关的约束。
 */
class RuntimeVisualGuardTrackerPerfTest {

    @Test
    fun steadyStateFrames_doNotRepublishDecision() {
        // 窗口未结算时累加只改计数，决策不可能变化，因此不应每帧重算决策并写一次
        // StateFlow（否则每秒 120 次决策对象分配 + 赋值，还会额外唤醒组合侧收集）。
        val tracker = RuntimeVisualGuardTracker()

        repeat(12) { tracker.onFrame(tracked = true, isJank = false, nowMs = 100L) }
        val decisionWhileWindowOpen = tracker.decision.value
        repeat(120) { tracker.onFrame(tracked = true, isJank = false, nowMs = 200L) }

        assertSame(decisionWhileWindowOpen, tracker.decision.value)
    }

    @Test
    fun windowCloseStillPublishesUpdatedDecision() {
        // 延迟发布不等于不发布：窗口结算若改变了决策，必须立刻对外可见。
        val tracker = RuntimeVisualGuardTracker()

        repeat(2) { round ->
            repeat(12) { index ->
                tracker.onFrame(
                    frameData = FrameData(
                        frameStartNanos = index.toLong(),
                        frameDurationUiNanos = 20_000_000L,
                        isJank = true,
                        states = listOf(StateInfo("home:feed:recommend", "Active")),
                    ),
                    nowMs = 100L * (round + 1),
                )
            }
            tracker.onFrame(
                frameData = FrameData(12L, 8_000_000L, false, emptyList()),
                nowMs = 100L * (round + 1),
            )
        }

        assertTrue(tracker.decision.value.downgraded)
        assertEquals(MotionTier.Reduced, tracker.decision.value.effectiveMotionTier)
    }

    @Test
    fun windowClosesOnFrameWithOnlyUntrackedStates() {
        // 收窗判定从「每帧构造 key 集合」改成「帧号比较」，语义必须完全一致：
        // 帧内只带未跟踪的 state 时，本信号窗口仍要在该帧结算。
        val tracker = RuntimeVisualGuardTracker()

        repeat(2) { round ->
            repeat(12) { index ->
                tracker.onFrame(
                    frameData = FrameData(
                        frameStartNanos = index.toLong(),
                        frameDurationUiNanos = 20_000_000L,
                        isJank = true,
                        states = listOf(StateInfo("home:feed:recommend", "Scrolling=true")),
                    ),
                    nowMs = 100L * (round + 1),
                )
            }
            tracker.onFrame(
                frameData = FrameData(
                    frameStartNanos = 12L,
                    frameDurationUiNanos = 8_000_000L,
                    isJank = false,
                    states = listOf(StateInfo("home:current_category", "recommend")),
                ),
                nowMs = 100L * (round + 1),
            )
        }

        // 同一信号两个高掉帧窗口 → 第二次结算把全局降到 Reduced。
        assertTrue(tracker.decision.value.downgraded)
        assertEquals(MotionTier.Reduced, tracker.decision.value.effectiveMotionTier)
    }
}
