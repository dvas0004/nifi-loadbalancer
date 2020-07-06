package me.davidvassallo.processors.LoadBalancer

data class AttributeBucket (
        val hash: String,
        var destination: String,
        var lastSeen: Long
)