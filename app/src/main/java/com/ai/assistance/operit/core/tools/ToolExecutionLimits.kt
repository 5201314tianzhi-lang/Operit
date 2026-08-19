package com.ai.assistance.operit.core.tools

object ToolExecutionLimits {
    var MAX_FILE_READ_BYTES = 32_000
        private set
    const val DEFAULT_FILE_READ_PART_LINES = 200
    var MAX_TEXT_RESULT_LENGTH = 12_000
        private set
    var MAX_FINAL_TOOL_RESULT_MESSAGE_CHARS = MAX_FILE_READ_BYTES * 2
        private set

    fun configure(
        fileReadBytes: Int? = null,
        textResultLength: Int? = null,
        finalResultChars: Int? = null
    ) {
        fileReadBytes?.let { MAX_FILE_READ_BYTES = it }
        textResultLength?.let { MAX_TEXT_RESULT_LENGTH = it }
        finalResultChars?.let { MAX_FINAL_TOOL_RESULT_MESSAGE_CHARS = it }
    }
}
