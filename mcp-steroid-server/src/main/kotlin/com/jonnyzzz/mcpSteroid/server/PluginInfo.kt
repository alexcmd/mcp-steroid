package com.jonnyzzz.mcpSteroid.server

import kotlinx.serialization.Serializable

@Serializable
data class PluginInfo(
    val id: String,
    val name: String,
    val version: String
)
