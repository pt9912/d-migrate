package dev.dmigrate.server.application.error

data class ValidationViolation(val field: String, val reason: String)
