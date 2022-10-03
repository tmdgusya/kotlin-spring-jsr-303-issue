# Kotlin JSR-303 Issue

Kotlin Spring 을 통해 개발하다보면 생각보다 Spring 에서 Kotlin 스럽게 사용하기 위해 몇가지 Custom 을 해줘야 하는 상황들이 생깁니다. 
아래와 같이 Kotlin Coroutines 는 Spring 5.3 부터 지원되는 Spec 으로 Controller 에서 suspend method 를 이용하는것을 가능하게 해줍니다. 

## suspend modifier 와 hibernate validation Issue

따라서 기존의 Validation 들을 그대로 내비둔채 suspend modifier 를 붙여주게 되면 실제로 테스트 해볼때 아래와 같은 에러를 마주하게 됩니다. 

<img width="1607" alt="image" src="https://user-images.githubusercontent.com/57784077/193463970-69eae666-59b7-4f60-9e8a-0ee00d017468.png">

이유는 **hibernate-validation 이 Coroutines 를 Support 하지 않기 때문**입니다. 기본적으로 suspend modifier 가 붙게 되면 Continuation 을 Parameter 로 전달하게 되는데요. 
따라서 Library 에서 이 Parameter 를 제대로 Support 해주지 않게 된다면 아래와 같이 Parameters 의 길이의 Continuation 을 찾으려 하기에 IndexOutOfBoundException 이 발생하게 됩니다. 
말로하면 어려우니 아래 코드를 한번 같이 보시죠.

```kotlin
@RestController
@Validated
class TestController {

    @GetMapping("/test2")
    fun test2(@RequestParam @Min(0) no: Int) = "Success $no" // 1번 코드

    @GetMapping("/test")
    suspend fun test(@RequestParam @Min(0) no: Int) = withContext(Dispatchers.Default) { // 2번 코드
        return@withContext "Success $no"
    }

    @ExceptionHandler(ConstraintViolationException::class)
    fun exceptionHandler(e: ConstraintViolationException): String? {
        return e.message
    }
}
```

위의 1번 코드와 2번 코드는 사실상 동일하지만, suspend modifier 를 썼냐 안썼냐의 차이인데요. 둘다 잘 Validation 이 작동해야 할것 같지만 앞서 말했듯이 suspend modifier 를 붙인 경우만 잘 작동하지 않습니다. 
테스트를 위해 `test2?no=-1` 로 먼져 요청을 보내보겠습니다. 

<img width="603" alt="image" src="https://user-images.githubusercontent.com/57784077/193464450-c31aabd9-bf3c-4fb2-867e-9c68ff357bf1.png">

Validation 이 잘 작동하여 원하는 메세지를 얻었음을 확인할 수 있습니다. 다시끔 suspend modifier 가 붙은 `test?no=-1` 로 보내면 Error 를 마주하게 되는데요. 

<img width="1607" alt="image" src="https://user-images.githubusercontent.com/57784077/193463970-69eae666-59b7-4f60-9e8a-0ee00d017468.png">

이를 해결하기 위한 코드를 작성해 봅시다.

```kotlin
@Configuration(proxyBeanMethods = false)
class CoroutineConfiguration {

    @Primary
    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    fun validatorForKotlin(): CustomLocalValidatorFactoryBean {
        val factoryBean = CustomLocalValidatorFactoryBean()
        factoryBean.messageInterpolator = MessageInterpolatorFactory().`object`
        return factoryBean
    }
}

class CustomLocalValidatorFactoryBean : LocalValidatorFactoryBean() {
    override fun getClockProvider(): ClockProvider = DefaultClockProvider.INSTANCE

    override fun postProcessConfiguration(configuration: javax.validation.Configuration<*>) {
        super.postProcessConfiguration(configuration)

        val discoverer = PrioritizedParameterNameDiscoverer()
        discoverer.addDiscoverer(SuspendAwareKotlinParameterNameDiscoverer())
        discoverer.addDiscoverer(StandardReflectionParameterNameDiscoverer())
        discoverer.addDiscoverer(LocalVariableTableParameterNameDiscoverer())

        val defaultProvider = configuration.defaultParameterNameProvider
        configuration.parameterNameProvider(object : ParameterNameProvider {
            override fun getParameterNames(constructor: Constructor<*>): List<String> {
                val paramNames: Array<String>? = discoverer.getParameterNames(constructor)
                return paramNames?.toList() ?: defaultProvider.getParameterNames(constructor)
            }

            override fun getParameterNames(method: Method): List<String> {
                val paramNames: Array<String>? = discoverer.getParameterNames(method)
                return paramNames?.toList() ?: defaultProvider.getParameterNames(method)
            }
        })
    }
}

class SuspendAwareKotlinParameterNameDiscoverer : ParameterNameDiscoverer {

    private val defaultProvider = KotlinReflectionParameterNameDiscoverer()

    override fun getParameterNames(constructor: Constructor<*>): Array<String>? =
        defaultProvider.getParameterNames(constructor)

    override fun getParameterNames(method: Method): Array<String>? {
        val defaultNames = defaultProvider.getParameterNames(method) ?: return null
        val function = method.kotlinFunction
        return if (function != null && function.isSuspend) {
            defaultNames + ""
        } else defaultNames
    }
}
```

(틀린 내용이 있을 수 있으니 있다면 댓글로 피드백 부탁드립니다~!)
참고로, 위의 코드는 모두 [ISSUE](https://github.com/spring-projects/spring-framework/issues/23499) 의 코드를 복사한 것이다.

일단 그래도 조금이라도 코드를 알아보자, `LocalValidatorFactoryBean` 의 경우 BootStrap 시에 JSR-303 의 기능을 확장할 수 있는 Interface 이다. 
우리의 목표는 일단 Suspend Modifier 의 Continuation Parameter 를 식별할 수 있는 대상으로 만드는 것 이다. `PrioritizedParameterNameDiscoverer` 는 
Parameter 의 Name 을 찾을 수 있는 ParameterNameDiscoverer 에 적절하게 위임해주는 Component 로 addDiscover 를 통해서 ParameterNameDiscoverer 를 등록할 수 있다.

suspend modifier 가 붙은 함수를 Java code 로 Decompile 해보면 아래처럼 받는 인자쪽이 변하게 된다. 이를 어떻게 해결하였는지 아래에서 결과적으로 설명하겠다.

```kotlin
fun test(no: Int, continuation: Continuation)
```

이 상황에서 continuation Parameter 를 제대로 찾지 못하는게 문제로 실제로 Debug 를 돌려서 PrioritizedParameterNameDiscoverer.java 쪽 getParameters() 를 보면, 
**Parameter 가 No 만 존재한다고 나오게 되고 이로 인해 Continuation 이 있는 인덱스(parameter.length - 1) 를 사용 하려고 하자 Error 가 발생하게 되는 것**이다.
위에서 설명한대로 Continuation 이 붙게 되는 경우가 문제이므로 **해당 경우 "" 를 붙여주어 아래 사진 처럼 ["no", ""] 를 만들어 주는 것**이다.

이제 해결은 됬으니 한번 요청을 보내 실제로 잘 동작하는지 살펴보자

## 첫번째 이슈 동작 결과

<img width="721" alt="image" src="https://user-images.githubusercontent.com/57784077/193465764-e16f13c3-4277-41ab-b1e7-45da54b10b2b.png">

이제 문제없이 잘 동작하는걸 확인할 수 있다. 하지만 두번째 이슈가 있는데 요건 좀 복잡하다. 
아래 글에서 함께 보도록 하자

## @NotNull Issue

Kotlin 에서 javax 의 `@NotNull(message = "")` Annotation 을 붙이게 되면 아래와 같이 사용하면 완벽할 것만 같다.

```kotlin
@Validated
data class User(
    @field:NotNull(message = "사용자의 이름은 필수 입력 값 입니다.")
    val name: String,
    @field:Min(0)
    val age: Int
)
```

```json
{
    "age": 10
}
```

그래서 직접 검증하기 위해 위와 같은 JSON 형태로 Postman 에서 요청을 날려보게 되면 예상하지 못한 에러를 마주하게 된다.

<img width="1613" alt="image" src="https://user-images.githubusercontent.com/57784077/193465971-55da578f-fe6d-4eee-b9b6-b2b532cbe5f4.png">

왜 이런 문제가 발생하게 될까?

### 문제 찾기

일단 Kotlin 에서 Json 형태의 Body 를 Object 로 변환할때, Jackson Library 를 이용해서 진행하게 된다. 
이 과정에서 Json 을 먼져 Object 로 생성하고 그 이후 Validation 을 진행하게 된다. 이 과정에서 Json 을 Object 로 만드는 과정이 선행과정이므로 
앞쪽에서 name 이 null 을 허용하지 않는 타입임에도 불구하고, Null 을 넣으려고 해서 KotlinMissingParameterException 예외가 발생하게 됩니다.

해결방법은 여러가지가 있습니다. 아래와 같이 objectMapper 에 `nullIsSameAsDefault = true` 값을 넣어주는 방법도 있는데요. 
하지만 이 방법도 Default 값이 있는 Type 인 String 이나 Long 등등의 값에만 이용하고 객체에는 사용이 불가능해서, 완전한 해결책은 아닙니다. 

그리고 저는 좋지 않은 해결법이라고 생각하는 `?(Nullable)` 을 붙이는 방법이 있습니다. 하지만 이 방법의 가장 큰 문제는 사용하는 측에서 `?` 에 대한 조건을 계속해서 체크하거나, 
일련의 NotNullable 하게 Return 해주는 getter 와 같은 수단이 필요하게 됩니다. 거추장 스럽고 Kotlin 스럽지 못하다고 생각하여 좋은 Solution 은 아니라고 생각합니다. 

```kotlin
@Validated
data class User(
    // 좋은 Solution 일까..?
    @field:NotNull(message = "사용자의 이름은 필수 입력 값 입니다.")
    val name: String? = null,
    @field:Min(0)
    val age: Int
) {
    fun getName(): String {
        return name ?: throw Error("!!!")
    }
}
```

#### MissingParameterException Handler 작성

**참고로 아래 방법이 좋은 방법인지는 고민이 필요합니다.** 

MissingParameterException 에서는 Parameter 정보를 이용할 수 있는데, 그 정보를 통해 Class 의 이름과 에러가난 Parameter 의 이름을 아래처럼 추출 할 수 있다. 

```kotlin
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
```

코드를 간략하게 설명하자면 `e.parameter.name` 을 통해 에러가 발생한 parameter 의 이름을 알아 낸뒤에, `Class.forName()` 을 통해 Deserialization 의 TargetClass 를 동적으로 Load 합니다. 
만약 동적으로 로드가 실패했다면 아래 로직을 태울 수 없으므로 MissingKotlinParameterException 를 리턴하고, 성공했다면 field 의 NotNull Annotation 의 message 정보를 가져와서 Return 해주게 됩니다. 

위의 코드를 적용한 뒤 한번 name 을 제거한 뒤 Request 를 날려보도록 하겠습니다.

```kotlin
@Validated
data class User(
    @field:NotNull(message = "사용자의 이름은 필수 입력 값 입니다.")
    val name: String,
    @field:Min(0)
    val age: Int
)
```

<img width="835" alt="image" src="https://user-images.githubusercontent.com/57784077/193511830-1a67c73e-faf8-4025-97fb-80158f7495e5.png">

이제 우리가 원하는 ErrorMessage 가 잘 등록되는 것을 확인할 수 있습니다.

### Class.forName(...) 과 GC

사실 가장 마음에 걸렸던건 ClassLoader 를 통해 동적으로 Class 를 Load 할때, 해당 Class 가 GC 의 Target 이 되냐 안되냐 였습니다. 
그래서 Visual JVM 을 킨 뒤, 여러번 Request 를 수행해봤으니 Load 된 Classes 수가 변하지 않는 모습을 확인할 수 있었습니다. 
따라서, 이미 Load 된 것의 Cache 를 이용하는 건가? 라는 생각이 들긴했는데.. 요 부분은 좀 더 공부를 해볼 필요가 있는것 같습니다.
(만약 아시는 분 있으시다면 댓글로 답변 부탁드립니다.)

![image](https://user-images.githubusercontent.com/57784077/193514081-d167f8bd-a53e-4107-9d6e-0e7cbae2ef6a.png)




