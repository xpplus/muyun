package com.muyun.app.feature.terminal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.muyun.app.StdioSsh
import com.muyun.app.TermSlot

/**
 * 终端标签状态（阶段 C2 第一步 · feature/terminal）
 *
 * 只管理"有哪些标签、当前选哪个、标签内容"，不负责 SSH 连接。
 * 连接逻辑仍在 MainVm（因为要联动开机、前台服务、密钥等）。
 */
class TermSession {

    var terms by mutableStateOf<List<TermSlot>>(emptyList())
        private set

    var activeTermId by mutableStateOf<String?>(null)
        private set

    val active: TermSlot? get() = terms.firstOrNull { it.id == activeTermId }
    val activeText: String get() = active?.text ?: ""
    val activeState: String get() = active?.state ?: "未连接"

    // ---------------- 连接簿记 ----------------
    private val connections = mutableMapOf<String, StdioSsh>()
    private val local = mutableSetOf<String>()

    fun register(id: String, ssh: StdioSsh) { connections[id] = ssh }
    fun connection(id: String): StdioSsh? = connections[id]
    fun removeConnection(id: String): StdioSsh? = connections.remove(id)
    fun markLocal(id: String) { local += id }
    fun unmarkLocal(id: String): Boolean = local.remove(id)
    fun isLocal(id: String) = id in local
    fun anyAlive(): Boolean = connections.values.any { it.isAlive() }
    fun dropTab(id: String) {
        connections.remove(id)
        local.remove(id)
        remove(id)
    }

    fun newSlot(title: String, machineId: String): String {
        val id = "t" + System.currentTimeMillis()
        terms = TermStore.add(terms, TermSlot(id = id, title = title, machineId = machineId))
        activeTermId = id
        return id
    }

    fun update(id: String, f: (TermSlot) -> TermSlot) {
        terms = TermStore.update(terms, id, f)
    }

    fun append(id: String, chunk: String) {
        update(id) { it.copy(text = TermStore.appendText(it.text, chunk)) }
    }

    fun select(id: String) { activeTermId = id }

    /** 删除标签，返回删除后应激活的标签 id */
    fun remove(id: String): String? {
        terms = TermStore.remove(terms, id)
        val next = TermStore.nextActive(terms, id, activeTermId)
        activeTermId = next
        return next
    }
}
