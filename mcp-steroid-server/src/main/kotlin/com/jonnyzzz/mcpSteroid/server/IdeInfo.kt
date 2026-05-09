package com.jonnyzzz.mcpSteroid.server

import kotlinx.serialization.Serializable

@Serializable
data class IdeInfo(
    val name: String,
    val version: String,
    val build: String
)
