package com.muyun.app.feature.files

import com.muyun.app.FilePane

/**
 * 须弥双栏的路径计算（阶段 4.4 第一步）
 *
 * 之前"进入子目录/返回上级/默认目录"的拼接逻辑在 UI 和 MainVm 里重复了 4 份，
 * 而且写法不一致（有的 endsWith("/") 判断，有的没有），
 * 直接导致过"单击文件夹没反应/打开错路径"的问题。
 *
 * 这里统一成唯一实现。
 */
object PaneNav {

    /** 进入子项后的路径（本机与远端规则不同：本机要用绝对路径） */
    fun childPath(owner: String, base: String, name: String): String =
        if (owner == FilePane.LOCAL) {
            java.io.File(base, name).absolutePath
        } else {
            FileOps.joinPath(base, name)
        }

    /** 上级目录 */
    fun parentPath(owner: String, path: String): String =
        if (owner == FilePane.LOCAL) {
            java.io.File(path).parent ?: path
        } else {
            FileOps.parentOf(path)
        }

    /** 首次连接时的默认目录 */
    fun defaultPath(owner: String, isRootUser: Boolean): String = when {
        owner == FilePane.LOCAL ->
            android.os.Environment.getExternalStorageDirectory().absolutePath
        owner.startsWith("cs|") -> "/workspaces"
        isRootUser -> "/root"
        else -> "/"
    }
}
