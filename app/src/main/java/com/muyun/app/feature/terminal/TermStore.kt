package com.muyun.app.feature.terminal

import com.muyun.app.TermSlot

/**
 * 终端标签簿记（阶段 C 第一步 · feature/terminal）
 *
 * 纯函数，不持有状态：
 *  - 追加输出（清洗 ANSI + 限制最大长度）
 *  - 新增/更新/删除标签
 *  - 计算下一个活动标签
 */
object TermStore {

    /** 单个标签最多保留的字符数（防止长跑命令吃内存） */
    const val MAX_CHARS = 20_000

    /** 追加输出：清洗控制序列并截断 */
    fun appendText(old: String, chunk: String, limit: Int = MAX_CHARS): String =
        (old + Ansi.clean(chunk)).takeLast(limit)

    fun add(terms: List<TermSlot>, slot: TermSlot): List<TermSlot> = terms + slot

    fun update(terms: List<TermSlot>, id: String, f: (TermSlot) -> TermSlot): List<TermSlot> =
        terms.map { if (it.id == id) f(it) else it }

    fun remove(terms: List<TermSlot>, id: String): List<TermSlot> =
        terms.filterNot { it.id == id }

    /** 关闭当前标签后应该激活哪个 */
    fun nextActive(terms: List<TermSlot>, closedId: String, currentActive: String?): String? =
        if (currentActive == closedId) terms.lastOrNull()?.id else currentActive
}
