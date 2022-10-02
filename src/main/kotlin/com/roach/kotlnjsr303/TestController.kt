package com.roach.kotlnjsr303

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import javax.validation.ConstraintViolationException
import javax.validation.constraints.Min

@RestController
@Validated
class TestController {

    @GetMapping("/test2")
    fun test2(@RequestParam @Min(0) no: Int) = "Success $no"

    @GetMapping("/test")
    suspend fun test(@RequestParam @Min(0) no: Int) = withContext(Dispatchers.Default) {
        return@withContext "Success $no"
    }

    @ExceptionHandler(ConstraintViolationException::class)
    fun exceptionHandler(e: ConstraintViolationException): String? {
        return e.message
    }
}