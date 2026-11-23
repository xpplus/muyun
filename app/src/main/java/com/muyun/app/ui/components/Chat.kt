package com.muyun.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 聊天显示组件（阶段 F1 · ui/components）
 *
 * 从 MainActivity 抽出：
 *  - ChatBubble：用户/助手/执行结果气泡，支持复制、删除、长文本选中
 *  - MarkdownText：轻量 Markdown 渲染（标题/列表/表格/代码/粗体）
 *  - MarkdownTable / InlineMarkdown：表格与行内样式
 *  - AssistChipBox：模型选择小胶囊
 */
@Composable
fun ChatBubble(m: AiMsg, onDelete: () -> Unit = {}) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val isUser = m.role == "user"
    val isAction = m.role == "system"
    val bg = when { isUser -> UserBubble; isAction -> ActionBubble; else -> AiBubble }
    val label = when { isUser -> "我"; isAction -> "执行结果"; else -> "牧云小助手" }
    val textColor = if (isUser) Color.White else Color(0xFFDCE3F0)

    fun copyAll() {
        val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("msg", m.content))
    }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier.widthIn(max = 330.dp)
                .background(bg, RoundedCornerShape(
                    topStart = 16.dp, topEnd = 16.dp,
                    bottomStart = if (isUser) 16.dp else 4.dp,
                    bottomEnd = if (isUser) 4.dp else 16.dp,
                ))
                .padding(horizontal = 12.dp, vertical = 9.dp)
        ) {
            if (!isUser) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(label, color = if (isAction) Color(0xFF7FB3FF) else Accent,
                        fontSize = 10.sp, maxLines = 1, modifier = Modifier.weight(1f))
                    Text("复制", color = Gray, fontSize = 11.sp,
                        modifier = Modifier.clickable { copyAll() }.padding(start = 8.dp))
                    Text("删", color = Gray, fontSize = 11.sp,
                        modifier = Modifier.clickable { onDelete() }.padding(start = 10.dp))
                }
            }
            if (!isUser) Spacer(Modifier.height(3.dp))
            androidx.compose.foundation.text.selection.SelectionContainer {
                if (isUser) Text(m.content, color = textColor, fontSize = 13.sp, lineHeight = 19.sp)
                else MarkdownText(m.content, textColor)
            }
        }
    }
}

@Composable
fun MarkdownText(srcRaw: String, color: Color) {
    val src = srcRaw
        .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("</?(p|div|span|b|i|strong|em|ul|ol|li|table|tr|td|th)[^>]*>", RegexOption.IGNORE_CASE), "")
    val lines = src.replace("\r\n", "\n").split("\n")
    var i = 0
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        while (i < lines.size) {
            val raw = lines[i]
            val line = raw.trimEnd()
            when {
                line.trimStart().startsWith("```") -> {
                    val buf = StringBuilder()
                    i++
                    while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                        buf.append(lines[i]).append("\n"); i++
                    }
                    if (i < lines.size) i++
                    Surface(color = Color(0xFF0C1522), shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()) {
                        Text(buf.toString().trimEnd(), color = Color(0xFF9BE38C), fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace, lineHeight = 17.sp,
                            modifier = Modifier.padding(8.dp).horizontalScroll(rememberScrollState()))
                    }
                }
                line.trimStart().startsWith("|") -> {
                    val rows = mutableListOf<List<String>>()
                    while (i < lines.size && lines[i].trimStart().startsWith("|")) {
                        val cells = lines[i].trim().trim('|').split("|").map { it.trim() }
                        if (!cells.all { it.matches(Regex(":?-{2,}:?")) }) rows.add(cells)
                        i++
                    }
                    MarkdownTable(rows, color)
                }
                Regex("^\\s*([-*]|\\d+\\.)\\s+.*").containsMatchIn(line) -> {
                    val content = line.trimStart().replaceFirst(Regex("^([-*]|\\d+\\.)\\s+"), "")
                    Row {
                        Text("• ", color = Accent, fontSize = 13.sp)
                        InlineMarkdown(content, color)
                    }
                    i++
                }
                line.isBlank() -> i++
                else -> { InlineMarkdown(line, color); i++ }
            }
        }
    }
}

@Composable
fun MarkdownTable(rows: List<List<String>>, color: Color) {
    if (rows.isEmpty()) return
    val cols = rows.maxOf { it.size }
    Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        rows.forEachIndexed { idx, row ->
            Row(Modifier.background(if (idx == 0) Color(0xFF22304A) else Color(0xFF141C29))) {
                for (c in 0 until cols) {
                    val cell = row.getOrNull(c) ?: ""
                    Box(Modifier.width(110.dp).padding(horizontal = 6.dp, vertical = 5.dp)) {
                        InlineMarkdown(cell, if (idx == 0) Color.White else color, bold = idx == 0)
                    }
                }
            }
            HorizontalDivider(color = Color(0xFF2A3548))
        }
    }
}

@Composable
fun InlineMarkdown(text: String, color: Color, bold: Boolean = false) {
    val ann = androidx.compose.ui.text.buildAnnotatedString {
        val re = Regex("(\\*\\*[^*]+\\*\\*|`[^`]+`)")
        var last = 0
        re.findAll(text).forEach { m ->
            append(text.substring(last, m.range.first))
            val t = m.value
            if (t.startsWith("**")) {
                withStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(t.removeSurrounding("**"))
                }
            } else {
                withStyle(androidx.compose.ui.text.SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFF9BE38C),
                    background = Color(0xFF0C1522),
                )) { append(t.removeSurrounding("`")) }
            }
            last = m.range.last + 1
        }
        append(text.substring(last))
    }
    Text(ann, color = color, fontSize = 13.sp, lineHeight = 19.sp,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
}

@Composable
fun AssistChipBox(text: String, onClick: () -> Unit) {
    Surface(color = CardBg, shape = RoundedCornerShape(50), modifier = Modifier.clickable { onClick() }) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("🧠", fontSize = 12.sp)
            Spacer(Modifier.width(6.dp))
            Text(text, color = Color(0xFFDCE3F0), fontSize = 12.sp, maxLines = 1)
            Text(" ▾", color = Gray, fontSize = 11.sp)
        }
    }
}

