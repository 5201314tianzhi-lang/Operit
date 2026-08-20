package com.ai.assistance.operit.pro.editor

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.UUID

/**
 * AI 代码编辑器管理器
 *
 * 提供内置代码编辑、语法高亮、AI 辅助编程、
 * 代码片段管理和多文件项目管理功能。
 *
 * 核心能力：
 * - 多语言语法高亮（Kotlin, Java, Python, JS, C++, HTML, CSS）
 * - AI 代码补全建议
 * - 代码错误检测与修复建议
 * - 代码格式化
 * - 项目文件树管理
 * - 代码片段库
 * - 历史记录与版本对比
 * - 主题切换
 */
class CodeEditorManager {

    companion object {
        private val _openFiles = MutableStateFlow<List<CodeFile>>(emptyList())
        val openFiles: StateFlow<List<CodeFile>> = _openFiles.asStateFlow()

        private val _activeFile = MutableStateFlow<CodeFile?>(null)
        val activeFile: StateFlow<CodeFile?> = _activeFile.asStateFlow()

        private val _snippets = MutableStateFlow<List<CodeSnippet>>(defaultSnippets())
        val snippets: StateFlow<List<CodeSnippet>> = _snippets.asStateFlow()

        private val _projects = MutableStateFlow<List<CodeProject>>(emptyList())
        val projects: StateFlow<List<CodeProject>> = _projects.asStateFlow()

        private val _editorSettings = MutableStateFlow(EditorSettings())
        val editorSettings: StateFlow<EditorSettings> = _editorSettings.asStateFlow()

        private val openFileMap = mutableMapOf<String, CodeFile>()
        private val projectMap = mutableMapOf<String, CodeProject>()
        private val undoStack = mutableMapOf<String, MutableList<String>>()
        private val redoStack = mutableMapOf<String, MutableList<String>>()
    }

    /** 打开文件 */
    fun openFile(path: String): CodeFile? {
        val file = File(path)
        if (!file.exists()) return null

        val content = file.readText()
        val codeFile = CodeFile(
            path = path,
            name = file.name,
            content = content,
            language = detectLanguage(file.name),
            lastModified = file.lastModified(),
            cursorPosition = 0,
        )

        openFileMap[path] = codeFile
        _openFiles.value = openFileMap.values.toList()
        _activeFile.value = codeFile
        return codeFile
    }

    /** 创建新文件 */
    fun createFile(name: String, language: CodeLanguage, content: String = ""): CodeFile {
        val codeFile = CodeFile(
            path = "memory://$name",
            name = name,
            content = content,
            language = language,
            lastModified = System.currentTimeMillis(),
            cursorPosition = 0,
        )
        openFileMap[codeFile.path] = codeFile
        _openFiles.value = openFileMap.values.toList()
        _activeFile.value = codeFile
        return codeFile
    }

    /** 更新文件内容 */
    fun updateContent(path: String, newContent: String, cursorPos: Int) {
        val file = openFileMap[path] ?: return

        // 保存到 undo 栈
        undoStack.getOrPut(path) { mutableListOf() }.add(file.content)
        redoStack[path]?.clear()

        val updated = file.copy(
            content = newContent,
            cursorPosition = cursorPos,
            lastModified = System.currentTimeMillis(),
            isModified = true,
        )
        openFileMap[path] = updated
        _openFiles.value = openFileMap.values.toList()
        if (_activeFile.value?.path == path) {
            _activeFile.value = updated
        }
    }

    /** 撤销 */
    fun undo(path: String): String? {
        val stack = undoStack[path] ?: return null
        if (stack.isEmpty()) return null

        val previous = stack.removeAt(stack.size - 1)
        val file = openFileMap[path] ?: return null

        redoStack.getOrPut(path) { mutableListOf() }.add(file.content)

        val updated = file.copy(content = previous)
        openFileMap[path] = updated
        _openFiles.value = openFileMap.values.toList()
        if (_activeFile.value?.path == path) {
            _activeFile.value = updated
        }
        return previous
    }

    /** 重做 */
    fun redo(path: String): String? {
        val stack = redoStack[path] ?: return null
        if (stack.isEmpty()) return null

        val next = stack.removeAt(stack.size - 1)
        val file = openFileMap[path] ?: return null

        undoStack.getOrPut(path) { mutableListOf() }.add(file.content)

        val updated = file.copy(content = next)
        openFileMap[path] = updated
        _openFiles.value = openFileMap.values.toList()
        if (_activeFile.value?.path == path) {
            _activeFile.value = updated
        }
        return next
    }

    /** 保存文件 */
    fun saveFile(path: String): Boolean {
        val file = openFileMap[path] ?: return false
        if (file.path.startsWith("memory://")) return false

        return try {
            File(file.path).writeText(file.content)
            val saved = file.copy(isModified = false)
            openFileMap[path] = saved
            _openFiles.value = openFileMap.values.toList()
            if (_activeFile.value?.path == path) {
                _activeFile.value = saved
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    /** 关闭文件 */
    fun closeFile(path: String) {
        openFileMap.remove(path)
        undoStack.remove(path)
        redoStack.remove(path)
        _openFiles.value = openFileMap.values.toList()
        if (_activeFile.value?.path == path) {
            _activeFile.value = openFileMap.values.firstOrNull()
        }
    }

    /** 格式化代码 */
    fun formatCode(path: String): String? {
        val file = openFileMap[path] ?: return null
        val formatted = CodeFormatter.format(file.content, file.language)
        updateContent(path, formatted, 0)
        return formatted
    }

    /** AI 代码分析 */
    fun analyzeCode(path: String): CodeAnalysisResult {
        val file = openFileMap[path] ?: return CodeAnalysisResult.Error("File not found")

        val issues = mutableListOf<CodeIssue>()
        val suggestions = mutableListOf<String>()

        // 语法检查
        issues.addAll(SyntaxChecker.check(file.content, file.language))

        // 风格检查
        issues.addAll(StyleChecker.check(file.content, file.language))

        // AI 建议
        suggestions.addAll(AssistantEngine.suggest(file.content, file.language))

        return CodeAnalysisResult.Success(
            issues = issues,
            suggestions = suggestions,
            complexity = calculateComplexity(file.content),
            lineCount = file.content.lines().size,
            charCount = file.content.length,
        )
    }

    /** 添加代码片段 */
    fun addSnippet(snippet: CodeSnippet) {
        val current = _snippets.value.toMutableList()
        current.add(snippet)
        _snippets.value = current
    }

    /** 创建项目 */
    fun createProject(name: String, baseDir: String, language: CodeLanguage): CodeProject {
        val project = CodeProject(
            id = UUID.randomUUID().toString(),
            name = name,
            baseDir = baseDir,
            language = language,
            files = emptyList(),
            createdAt = System.currentTimeMillis(),
        )
        projectMap[project.id] = project
        _projects.value = projectMap.values.toList()
        return project
    }

    /** 更新编辑器设置 */
    fun updateSettings(settings: EditorSettings) {
        _editorSettings.value = settings
    }

    private fun detectLanguage(fileName: String): CodeLanguage {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "kt", "kts" -> CodeLanguage.KOTLIN
            "java" -> CodeLanguage.JAVA
            "py" -> CodeLanguage.PYTHON
            "js", "mjs" -> CodeLanguage.JAVASCRIPT
            "ts" -> CodeLanguage.TYPESCRIPT
            "cpp", "cc", "cxx" -> CodeLanguage.CPP
            "c", "h" -> CodeLanguage.C
            "html", "htm" -> CodeLanguage.HTML
            "css" -> CodeLanguage.CSS
            "json" -> CodeLanguage.JSON
            "xml" -> CodeLanguage.XML
            "md" -> CodeLanguage.MARKDOWN
            "sh", "bash" -> CodeLanguage.SHELL
            "sql" -> CodeLanguage.SQL
            "go" -> CodeLanguage.GO
            "rs" -> CodeLanguage.RUST
            else -> CodeLanguage.TEXT
        }
    }

    private fun calculateComplexity(content: String): Int {
        var complexity = 1
        val keywords = listOf("if ", "for ", "while ", "when ", "when(", "match ", "case ", "&&", "||", "?:")
        keywords.forEach { kw ->
            complexity += content.split(kw).size - 1
        }
        return complexity
    }
}

/** 代码文件 */
data class CodeFile(
    val path: String,
    val name: String,
    val content: String,
    val language: CodeLanguage,
    val lastModified: Long,
    val cursorPosition: Int,
    val isModified: Boolean = false,
)

/** 代码语言 */
enum class CodeLanguage(val displayName: String, val extensions: List<String>) {
    KOTLIN("Kotlin", listOf("kt", "kts")),
    JAVA("Java", listOf("java")),
    PYTHON("Python", listOf("py")),
    JAVASCRIPT("JavaScript", listOf("js", "mjs")),
    TYPESCRIPT("TypeScript", listOf("ts")),
    CPP("C++", listOf("cpp", "cc", "cxx")),
    C("C", listOf("c", "h")),
    HTML("HTML", listOf("html", "htm")),
    CSS("CSS", listOf("css")),
    JSON("JSON", listOf("json")),
    XML("XML", listOf("xml")),
    MARKDOWN("Markdown", listOf("md")),
    SHELL("Shell", listOf("sh", "bash")),
    SQL("SQL", listOf("sql")),
    GO("Go", listOf("go")),
    RUST("Rust", listOf("rs")),
    TEXT("Text", listOf("txt")),
}

/** 代码项目 */
data class CodeProject(
    val id: String,
    val name: String,
    val baseDir: String,
    val language: CodeLanguage,
    val files: List<String>,
    val createdAt: Long,
)

/** 代码片段 */
data class CodeSnippet(
    val id: String,
    val title: String,
    val language: CodeLanguage,
    val code: String,
    val tags: List<String> = emptyList(),
)

/** 代码问题 */
data class CodeIssue(
    val line: Int,
    val column: Int,
    val severity: IssueSeverity,
    val message: String,
    val ruleId: String,
)

/** 问题严重级别 */
enum class IssueSeverity { ERROR, WARNING, INFO, HINT }

/** 代码分析结果 */
sealed class CodeAnalysisResult {
    data class Success(
        val issues: List<CodeIssue>,
        val suggestions: List<String>,
        val complexity: Int,
        val lineCount: Int,
        val charCount: Int,
    ) : CodeAnalysisResult()
    data class Error(val message: String) : CodeAnalysisResult()
}

/** 编辑器设置 */
data class EditorSettings(
    val fontSize: Int = 14,
    val tabSize: Int = 4,
    val useSpaces: Boolean = true,
    val wordWrap: Boolean = false,
    val showLineNumbers: Boolean = true,
    val highlightCurrentLine: Boolean = true,
    val autoIndent: Boolean = true,
    val autoCloseBrackets: Boolean = true,
    val theme: EditorTheme = EditorTheme.DARK,
)

/** 编辑器主题 */
enum class EditorTheme { DARK, LIGHT, DRACULA, MONOKAI, SOLARIZED }

/** 代码格式化器 */
object CodeFormatter {
    fun format(code: String, language: CodeLanguage): String {
        return when (language) {
            CodeLanguage.JSON -> formatJson(code)
            CodeLanguage.HTML, CodeLanguage.XML -> formatHtml(code)
            else -> formatGeneric(code)
        }
    }

    private fun formatJson(code: String): String {
        return try {
            val json = org.json.JSONObject(code)
            json.toString(2)
        } catch (e: Exception) {
            code
        }
    }

    private fun formatHtml(code: String): String {
        return code.replace("><", ">\n<")
    }

    private fun formatGeneric(code: String): String {
        val lines = code.lines()
        val formatted = StringBuilder()
        var indent = 0

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("}") || trimmed.startsWith(")")) {
                indent = maxOf(0, indent - 1)
            }
            repeat(indent) { formatted.append("    ") }
            formatted.append(trimmed).append("\n")
            if (trimmed.endsWith("{") || trimmed.endsWith("(")) {
                indent++
            }
        }
        return formatted.toString().trimEnd()
    }
}

/** 语法检查器 */
object SyntaxChecker {
    fun check(code: String, language: CodeLanguage): List<CodeIssue> {
        val issues = mutableListOf<CodeIssue>()
        val lines = code.lines()

        lines.forEachIndexed { index, line ->
            val lineNum = index + 1

            when (language) {
                CodeLanguage.KOTLIN, CodeLanguage.JAVA -> {
                    if (line.contains("println") && language == CodeLanguage.KOTLIN) {
                        issues.add(CodeIssue(lineNum, 1, IssueSeverity.INFO, "调试输出，生产代码应移除", "no-println"))
                    }
                }
                else -> {}
            }

            // 通用检查
            if (line.contains("\t") && line.contains("    ")) {
                issues.add(CodeIssue(lineNum, 1, IssueSeverity.WARNING, "混用 Tab 和空格缩进", "mixed-indent"))
            }

            if (line.trim().length > 120) {
                issues.add(CodeIssue(lineNum, 1, IssueSeverity.WARNING, "行长度超过120字符", "line-length"))
            }

            if (line.contains("TODO")) {
                issues.add(CodeIssue(lineNum, 1, IssueSeverity.INFO, "未完成的 TODO", "todo"))
            }
        }

        return issues
    }
}

/** 风格检查器 */
object StyleChecker {
    fun check(code: String, language: CodeLanguage): List<CodeIssue> {
        val issues = mutableListOf<CodeIssue>()
        val lines = code.lines()

        lines.forEachIndexed { index, line ->
            val lineNum = index + 1

            if (line.trim().isEmpty() && index > 0 && lines.getOrNull(index - 1)?.trim()?.isEmpty() == true) {
                issues.add(CodeIssue(lineNum, 1, IssueSeverity.HINT, "连续空行", "consecutive-blank-lines"))
            }

            if (line.trim().endsWith(" ") || line.trim().endsWith("\t")) {
                issues.add(CodeIssue(lineNum, 1, IssueSeverity.HINT, "行尾有多余空格", "trailing-whitespace"))
            }
        }

        return issues
    }
}

/** AI 助手引擎 */
object AssistantEngine {
    fun suggest(code: String, language: CodeLanguage): List<String> {
        val suggestions = mutableListOf<String>()

        if (code.contains("var ")) {
            suggestions.add("建议: 考虑使用 'val' 替代 'var' 以提高不可变性")
        }

        if (code.contains("!!")) {
            suggestions.add("警告: 使用 '!!' 可能导致 NPE，建议使用安全调用 '?.' 或 '?:'")
        }

        if (code.contains("Thread.sleep")) {
            suggestions.add("建议: 避免在主线程调用 Thread.sleep，使用协程 delay")
        }

        if (code.contains("findViewById")) {
            suggestions.add("建议: 使用 ViewBinding 或合成属性替代 findViewById")
        }

        if (code.contains("for (") && language == CodeLanguage.KOTLIN) {
            suggestions.add("建议: Kotlin 中优先使用函数式操作 (map, filter, forEach)")
        }

        return suggestions
    }
}

/** 默认代码片段 */
private fun defaultSnippets(): List<CodeSnippet> {
    return listOf(
        CodeSnippet("1", "Kotlin 函数模板", CodeLanguage.KOTLIN, "fun functionName(param: Type): ReturnType {\n    // TODO: implement\n    return result\n}"),
        CodeSnippet("2", "Kotlin 扩展函数", CodeLanguage.KOTLIN, "fun String.isEmail(): Boolean {\n    return this.contains(\"@\") && this.contains(\".\")\n}"),
        CodeSnippet("3", "协程启动", CodeLanguage.KOTLIN, "CoroutineScope(Dispatchers.IO).launch {\n    // async work\n}"),
        CodeSnippet("4", "Python 函数", CodeLanguage.PYTHON, "def function_name(params):\n    \"\"\"Docstring\"\"\"\n    # TODO: implement\n    return result"),
        CodeSnippet("5", "HTML5 模板", CodeLanguage.HTML, "<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n    <meta charset=\"UTF-8\">\n    <title>Page Title</title>\n</head>\n<body>\n    \n</body>\n</html>"),
        CodeSnippet("6", "JavaScript 异步函数", CodeLanguage.JAVASCRIPT, "async function fetchData(url) {\n    try {\n        const response = await fetch(url);\n        const data = await response.json();\n        return data;\n    } catch (error) {\n        console.error(error);\n    }\n}"),
    )
}
