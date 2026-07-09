package com.example.torrentstreamer

data class AudioTrackInfo(
    val groupIndex: Int,
    val trackIndex: Int,
    val language: String,
    val label: String,
    val isSelected: Boolean,
    val isSupported: Boolean = true // Визначає, чи підтримує пристрій цей аудіокодек апаратно
)