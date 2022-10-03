package com.roach.kotlnjsr303

import com.fasterxml.jackson.module.kotlin.MissingKotlinParameterException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import javax.validation.ConstraintViolationException
import javax.validation.constraints.Min
import javax.validation.constraints.NotNull
import kotlin.jvm.internal.Reflection
import kotlin.reflect.full.declaredMembers
import kotlin.reflect.full.findAnnotation

@RestController
@Validated
class TestController {

    @GetMapping("/test2")
    fun test2(@RequestParam @Min(0) no: Int) = "Success $no"

    @GetMapping("/test")
    suspend fun test(@RequestParam @Min(0) no: Int) = withContext(Dispatchers.Default) {
        return@withContext "Success $no"
    }

    @PostMapping("/user")
    suspend fun test(@RequestBody user: User) = withContext(Dispatchers.Default) {
        return@withContext "Success $user"
    }

    @ExceptionHandler(ConstraintViolationException::class)
    fun exceptionHandler(e: ConstraintViolationException): String? {
        return e.message
    }

    @ExceptionHandler(MissingKotlinParameterException::class)
    fun missingParameterExceptionHandler(e: MissingKotlinParameterException): String? {
        val errorParameterName = e.parameter.name
        // using java classLoader
        val errorParameterClassResult = runCatching {
            Class.forName((e.path[0].from as Class<*>).name)
        }.onFailure {
            // 실패시 Jackson KotlinParameterException 그대로 Return
            return e.message
        }

        errorParameterClassResult.getOrNull()?.let {
            for (field in it.declaredFields) {
                if (field.name == errorParameterName) {
                    val constraint = field.getAnnotation(NotNull::class.java)
                    return constraint?.message
                }
            }
        }

        return e.message
    }

    companion object {
        private val log = LoggerFactory.getLogger(TestController::class.java)
    }
}

@Validated
data class User(
    @field:NotNull(message = "사용자의 이름은 필수 입력 값 입니다.")
    val name: String,
    @field:Min(0)
    val age: Int
)