package com.jonnyzzz.mcpSteroid.execution

import kotlinx.serialization.Serializable

@Serializable
data class ApplyPatchRequest(val hunks: List<ApplyPatchHunk>)

@Serializable
data class ApplyPatchHunk(val filePath: String, val oldString: String, val newString: String)
