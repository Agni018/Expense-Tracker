package com.expensetracker.app

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

val IST: ZoneId = ZoneId.of("Asia/Kolkata")

fun at(year: Int, month: Int, day: Int, hour: Int = 10, minute: Int = 0): Long =
    LocalDateTime.of(year, month, day, hour, minute).atZone(IST).toInstant().toEpochMilli()

fun dateOf(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(IST).toLocalDate()
