# Sentry SDK v7 Features Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add opt-in Sentry SDK v7 performance tracing, service spans, breadcrumbs, and inbound distributed trace continuation to the Grails Sentry plugin.

**Architecture:** Keep existing error capture behaviour unchanged by default. Add independent config toggles, register each feature conditionally from `SentryGrailsPlugin`, and implement runtime behaviour in small classes under `src/main/groovy/grails/plugin/sentry`. Tests use a recording Sentry transport where possible so assertions inspect captured events and transactions directly.

**Tech Stack:** Grails 3.3.8, Groovy 2.4, Spock, Sentry Java SDK 7.14.0, Logback, Spring Boot servlet filters, Spring MVC HandlerInterceptor, Spring AOP/AspectJ.

---

## Design Notes

### `SentryTracingInterceptor` — Spring HandlerInterceptor, NOT Grails Interceptor

The original spec called for a Grails Interceptor, but Grails only applies the `grails.artefact.Interceptor` trait (which provides `matchAll()`, `request`, `response`, `controllerName`, `actionName`) to classes discovered from `grails-app/interceptors/`. Classes in `src/main/groovy/` do not receive the trait. Putting the file in `grails-app/interceptors/` would prevent constructor injection of `SentryConfig` without significant extra wiring.

**Replacement:** Implement `org.springframework.web.servlet.HandlerInterceptor` and register via a `WebMvcConfigurerAdapter` bean. Spring Boot 1.5.x (used by Grails 3.3.8) automatically picks up any `WebMvcConfigurer`/`WebMvcConfigurerAdapter` bean and adds its interceptors to `RequestMappingHandlerMapping`, which Grails 3 uses for controller action dispatch. This achieves identical runtime behaviour with clean constructor injection and simple POJO-style tests.

### `RecordingTransport` — API verified for SDK 7.14.0

- `SentryEnvelopeItem.getEvent(ISerializer)` and `getTransaction(ISerializer)` exist in SDK 7.x.
- `JsonSerializer(SentryOptions)` constructor exists.
- `ITransportFactory.create(SentryOptions, RequestDetails)` exists.
- `io.sentry.transport.RateLimiter` is public.
- `ITransport.close(boolean)` is a default method — our override is fine.

---

## File Structure

- Modify `build.gradle`: add explicit Spring AOP and AspectJ dependencies for `@Aspect` service tracing.
- Modify `grails-app/conf/plugin.yml`: add six new config keys with environment variable defaults.
- Modify `src/main/groovy/grails/plugin/sentry/SentryConfig.groovy`: parse and expose new feature toggles.
- Modify `src/main/groovy/grails/plugin/sentry/SentryGrailsPlugin.groovy`: set `tracesSampleRate`, register feature beans, attach breadcrumb appenders, and keep existing appender behaviour.
- Create `src/main/groovy/grails/plugin/sentry/SentryTracingInterceptor.groovy`: Spring HandlerInterceptor that starts and finishes request transactions.
- Create `src/main/groovy/grails/plugin/sentry/SentryMvcConfigurer.groovy`: `WebMvcConfigurerAdapter` that registers `SentryTracingInterceptor` with Spring MVC.
- Create `src/main/groovy/grails/plugin/sentry/SentryServiceTracingAspect.groovy`: creates child spans around service methods when a transaction is active.
- Create `src/main/groovy/grails/plugin/sentry/SentryBreadcrumbAppender.groovy`: converts qualifying log events into Sentry breadcrumbs.
- Create `src/main/groovy/grails/plugin/sentry/SentryTracingFilter.groovy`: reads inbound trace headers and stores a `TransactionContext` request attribute.
- Create `src/test/groovy/grails/plugin/sentry/RecordingTransport.groovy`: in-memory Sentry transport for deterministic test assertions.
- Create `src/test/groovy/grails/plugin/sentry/SentryConfigSpec.groovy`: config parsing coverage.
- Create `src/test/groovy/grails/plugin/sentry/SentryTracingInterceptorSpec.groovy`: request transaction unit coverage.
- Create `src/test/groovy/grails/plugin/sentry/SentryServiceTracingAspectSpec.groovy`: service span unit coverage.
- Create `src/test/groovy/grails/plugin/sentry/SentryBreadcrumbAppenderSpec.groovy`: breadcrumb appender coverage.
- Create `src/test/groovy/grails/plugin/sentry/SentryTracingFilterSpec.groovy`: distributed tracing filter coverage.
- Modify `src/test/groovy/grails/plugin/sentry/SanityIntegrationSpec.groovy`: assert new features remain disabled by default.
- Modify `README.md`: document new config keys and limitations.

---

### Task 1: Add AOP Dependencies

**Files:**
- Modify: `build.gradle`

- [ ] **Step 1: Add compile dependencies**

In `build.gradle`, add the AOP dependencies immediately after the existing `compile 'org.springframework.boot:spring-boot-starter-actuator'` line:

```groovy
    compile 'org.springframework.boot:spring-boot-starter-actuator'
    compile 'org.springframework:spring-aop'
    compile 'org.aspectj:aspectjweaver'
```

- [ ] **Step 2: Run dependency resolution**

Run: `./gradlew.bat dependencies --configuration compileClasspath`

Expected: command exits 0 and the output includes `org.springframework:spring-aop` and `org.aspectj:aspectjweaver`.

- [ ] **Step 3: Commit**

```bash
git add build.gradle
git commit -m "build: add aop support for sentry tracing"
```

---

### Task 2: Add Feature Config Parsing

**Files:**
- Modify: `grails-app/conf/plugin.yml`
- Modify: `src/main/groovy/grails/plugin/sentry/SentryConfig.groovy`
- Test: `src/test/groovy/grails/plugin/sentry/SentryConfigSpec.groovy`

- [ ] **Step 1: Write config parsing tests**

Create `src/test/groovy/grails/plugin/sentry/SentryConfigSpec.groovy`:

```groovy
package grails.plugin.sentry

import spock.lang.Specification

class SentryConfigSpec extends Specification {

    def "new SDK v7 feature flags default to disabled values"() {
        when:
            SentryConfig config = new SentryConfig([dsn: 'https://foo:bar@example.com/123'])

        then:
            config.active
            !config.tracingEnabled
            config.tracesSampleRate == 1.0d
            config.traceServices
            !config.breadcrumbsEnabled
            config.breadcrumbLevel == 'INFO'
            !config.distributedTracingEnabled
    }

    def "new SDK v7 feature flags parse strings and numbers"() {
        when:
            SentryConfig config = new SentryConfig([
                    dsn: 'https://foo:bar@example.com/123',
                    tracingEnabled: 'true',
                    tracesSampleRate: '0.25',
                    traceServices: 'false',
                    breadcrumbsEnabled: 'true',
                    breadcrumbLevel: 'warn',
                    distributedTracingEnabled: 'true'
            ])

        then:
            config.tracingEnabled
            config.tracesSampleRate == 0.25d
            !config.traceServices
            config.breadcrumbsEnabled
            config.breadcrumbLevel == 'WARN'
            config.distributedTracingEnabled
    }

    def "tracing disabled suppresses service tracing regardless of traceServices"() {
        when:
            SentryConfig config = new SentryConfig([
                    dsn: 'https://foo:bar@example.com/123',
                    tracingEnabled: 'false',
                    traceServices: 'true'
            ])

        then:
            !config.tracingEnabled
            !config.serviceTracingActive
    }

    def "tracing enabled and traceServices enabled activates service tracing"() {
        when:
            SentryConfig config = new SentryConfig([
                    dsn: 'https://foo:bar@example.com/123',
                    tracingEnabled: true,
                    traceServices: true
            ])

        then:
            config.serviceTracingActive
    }
}
```

- [ ] **Step 2: Run the focused test to verify it fails**

Run: `./gradlew.bat test --tests "grails.plugin.sentry.SentryConfigSpec"`

Expected: FAIL because `tracingEnabled`, `tracesSampleRate`, `traceServices`, `breadcrumbsEnabled`, `breadcrumbLevel`, `distributedTracingEnabled`, and `serviceTracingActive` do not exist yet.

- [ ] **Step 3: Add plugin defaults**

In `grails-app/conf/plugin.yml`, replace the sentry config block with:

```yaml
grails:
    plugin:
        sentry:
            dsn: ${SENTRY_DSN:}
            active: ${SENTRY_ACTIVE:true}
            serverName: ${SENTRY_SERVER_NAME:}
            logClassName: ${SENTRY_LOG_CLASS_NAME:true}
            logHttpRequest: ${SENTRY_LOG_HTTP_REQUEST:true}
            disableMDCInsertingServletFilter: ${SENTRY_DISABLE_MDC:false}
            springSecurityUser: ${SENTRY_SPRING_SECURITY_ENABLED:false}
            tracingEnabled: ${SENTRY_TRACING_ENABLED:false}
            tracesSampleRate: ${SENTRY_TRACES_SAMPLE_RATE:1.0}
            traceServices: ${SENTRY_TRACE_SERVICES:true}
            breadcrumbsEnabled: ${SENTRY_BREADCRUMBS_ENABLED:false}
            breadcrumbLevel: ${SENTRY_BREADCRUMB_LEVEL:INFO}
            distributedTracingEnabled: ${SENTRY_DISTRIBUTED_TRACING_ENABLED:false}
            springSecurityUserProperties:
                email: username
```

- [ ] **Step 4: Add `SentryConfig` fields and parsing**

In `src/main/groovy/grails/plugin/sentry/SentryConfig.groovy`, add this helper method inside the class (after the constructor, before the field declarations):

```groovy
    private static boolean asBooleanValue(Object value, boolean defaultValue = false) {
        if (value == null) {
            return defaultValue
        }

        value.toString().equalsIgnoreCase('true')
    }
```

Add this parsing block after the existing `sanitizeStackTrace` parsing line inside the constructor:

```groovy
        tracingEnabled = asBooleanValue(config.tracingEnabled, tracingEnabled)

        if (config.tracesSampleRate != null && config.tracesSampleRate.toString()) {
            tracesSampleRate = Double.parseDouble(config.tracesSampleRate.toString())
        }

        traceServices = asBooleanValue(config.traceServices, traceServices)
        breadcrumbsEnabled = asBooleanValue(config.breadcrumbsEnabled, breadcrumbsEnabled)

        if (config.breadcrumbLevel) {
            breadcrumbLevel = config.breadcrumbLevel.toString().toUpperCase()
        }

        distributedTracingEnabled = asBooleanValue(config.distributedTracingEnabled, distributedTracingEnabled)
```

Add these fields after the existing `boolean sanitizeStackTrace = false` line:

```groovy
    boolean tracingEnabled = false
    double tracesSampleRate = 1.0d
    boolean traceServices = true
    boolean breadcrumbsEnabled = false
    String breadcrumbLevel = 'INFO'
    boolean distributedTracingEnabled = false

    boolean getServiceTracingActive() {
        tracingEnabled && traceServices
    }
```

- [ ] **Step 5: Run the focused test to verify it passes**

Run: `./gradlew.bat test --tests "grails.plugin.sentry.SentryConfigSpec"`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add grails-app/conf/plugin.yml src/main/groovy/grails/plugin/sentry/SentryConfig.groovy src/test/groovy/grails/plugin/sentry/SentryConfigSpec.groovy
git commit -m "feat: add sentry v7 feature config"
```

---

### Task 3: Add Recording Transport Test Helper

**Files:**
- Create: `src/test/groovy/grails/plugin/sentry/RecordingTransport.groovy`

- [ ] **Step 1: Create the test transport helper**

Create `src/test/groovy/grails/plugin/sentry/RecordingTransport.groovy`:

```groovy
package grails.plugin.sentry

import io.sentry.Hint
import io.sentry.ITransportFactory
import io.sentry.JsonSerializer
import io.sentry.RequestDetails
import io.sentry.SentryEnvelope
import io.sentry.SentryEnvelopeItem
import io.sentry.SentryEvent
import io.sentry.SentryItemType
import io.sentry.SentryOptions
import io.sentry.protocol.SentryTransaction
import io.sentry.transport.ITransport
import io.sentry.transport.RateLimiter

class RecordingTransport implements ITransport {

    static final List<SentryEnvelope> envelopes = Collections.synchronizedList([])

    static void reset() {
        envelopes.clear()
    }

    static List<SentryEnvelopeItem> items(SentryItemType type) {
        envelopes.collectMany { SentryEnvelope envelope ->
            envelope.items.findAll { SentryEnvelopeItem item ->
                item.header.type == type
            }
        }
    }

    static List<SentryEvent> events(SentryOptions options) {
        JsonSerializer serializer = new JsonSerializer(options)
        items(SentryItemType.Event).collect { SentryEnvelopeItem item ->
            item.getEvent(serializer)
        }
    }

    static List<SentryTransaction> transactions(SentryOptions options) {
        JsonSerializer serializer = new JsonSerializer(options)
        items(SentryItemType.Transaction).collect { SentryEnvelopeItem item ->
            item.getTransaction(serializer)
        }
    }

    @Override
    void send(SentryEnvelope envelope, Hint hint) {
        envelopes << envelope
    }

    @Override
    boolean isHealthy() {
        true
    }

    @Override
    void flush(long timeoutMillis) {
    }

    @Override
    RateLimiter getRateLimiter() {
        null
    }

    @Override
    void close() {
    }

    @Override
    void close(boolean isRestarting) {
    }
}

class RecordingTransportFactory implements ITransportFactory {

    @Override
    ITransport create(SentryOptions options, RequestDetails requestDetails) {
        new RecordingTransport()
    }
}
```

- [ ] **Step 2: Run compile to verify helper APIs**

Run: `./gradlew.bat testClasses`

Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add src/test/groovy/grails/plugin/sentry/RecordingTransport.groovy
git commit -m "test: add recording sentry transport"
```

---

### Task 4: Add Request Transaction Interceptor

**Files:**
- Create: `src/main/groovy/grails/plugin/sentry/SentryTracingInterceptor.groovy`
- Create: `src/main/groovy/grails/plugin/sentry/SentryMvcConfigurer.groovy`
- Test: `src/test/groovy/grails/plugin/sentry/SentryTracingInterceptorSpec.groovy`

> **Note:** This is a Spring `HandlerInterceptor`, not a Grails artefact interceptor. The Grails `Interceptor` trait (which provides `matchAll()`, `before()`, `afterView()`) is only applied by Grails to classes in `grails-app/interceptors/`. Keeping this class in `src/main/groovy/` with constructor injection of `SentryConfig` requires the Spring MVC approach. `SentryMvcConfigurer` registers it via `WebMvcConfigurerAdapter`, which Spring Boot 1.5.x automatically wires into `RequestMappingHandlerMapping`.

- [ ] **Step 1: Write failing interceptor tests**

Create `src/test/groovy/grails/plugin/sentry/SentryTracingInterceptorSpec.groovy`:

```groovy
package grails.plugin.sentry

import io.sentry.ITransaction
import io.sentry.Sentry
import io.sentry.SentryOptions
import io.sentry.SpanStatus
import io.sentry.TransactionContext
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import spock.lang.Specification

class SentryTracingInterceptorSpec extends Specification {

    SentryOptions options

    def setup() {
        RecordingTransport.reset()
        Sentry.close()
        options = new SentryOptions()
        options.dsn = 'https://foo:bar@example.com/123'
        options.tracesSampleRate = 1.0d
        options.transportFactory = new RecordingTransportFactory()
        Sentry.init(options)
    }

    def cleanup() {
        Sentry.close()
        RecordingTransport.reset()
    }

    def "preHandle starts and binds a request transaction"() {
        given:
            SentryTracingInterceptor interceptor = new SentryTracingInterceptor(new SentryConfig([
                    dsn: 'https://foo:bar@example.com/123',
                    tracingEnabled: true
            ]))
            MockHttpServletRequest request = new MockHttpServletRequest()
            request.setAttribute('org.grails.CONTROLLER_NAME_ATTRIBUTE', 'book')
            request.setAttribute('org.grails.ACTION_NAME_ATTRIBUTE', 'show')
            MockHttpServletResponse response = new MockHttpServletResponse()

        expect:
            interceptor.preHandle(request, response, null)

        and:
            request.getAttribute(SentryTracingInterceptor.TRANSACTION_ATTRIBUTE) instanceof ITransaction
            Sentry.getSpan() != null
    }

    def "afterCompletion finishes the transaction with HTTP status"() {
        given:
            SentryTracingInterceptor interceptor = new SentryTracingInterceptor(new SentryConfig([
                    dsn: 'https://foo:bar@example.com/123',
                    tracingEnabled: true
            ]))
            MockHttpServletRequest request = new MockHttpServletRequest()
            request.setAttribute('org.grails.CONTROLLER_NAME_ATTRIBUTE', 'book')
            request.setAttribute('org.grails.ACTION_NAME_ATTRIBUTE', 'show')
            MockHttpServletResponse response = new MockHttpServletResponse()

        when:
            interceptor.preHandle(request, response, null)
            response.status = 500
            interceptor.afterCompletion(request, response, null, null)
            Sentry.flush(5000)

        then:
            RecordingTransport.transactions(options)*.transaction == ['book/show']
            RecordingTransport.transactions(options)*.status == [SpanStatus.INTERNAL_ERROR]
    }

    def "preHandle uses distributed transaction context when present"() {
        given:
            SentryTracingInterceptor interceptor = new SentryTracingInterceptor(new SentryConfig([
                    dsn: 'https://foo:bar@example.com/123',
                    tracingEnabled: true,
                    distributedTracingEnabled: true
            ]))
            MockHttpServletRequest request = new MockHttpServletRequest()
            request.setAttribute('org.grails.CONTROLLER_NAME_ATTRIBUTE', 'book')
            request.setAttribute('org.grails.ACTION_NAME_ATTRIBUTE', 'show')
            MockHttpServletResponse response = new MockHttpServletResponse()
            request.setAttribute(
                    SentryTracingFilter.TRANSACTION_CONTEXT_ATTRIBUTE,
                    new TransactionContext('upstream/request', 'http.server')
            )

        when:
            interceptor.preHandle(request, response, null)
            interceptor.afterCompletion(request, response, null, null)
            Sentry.flush(5000)

        then:
            RecordingTransport.transactions(options)*.transaction == ['upstream/request']
    }

    def "disabled tracing leaves request untouched"() {
        given:
            SentryTracingInterceptor interceptor = new SentryTracingInterceptor(new SentryConfig([
                    dsn: 'https://foo:bar@example.com/123',
                    tracingEnabled: false
            ]))
            MockHttpServletRequest request = new MockHttpServletRequest()
            MockHttpServletResponse response = new MockHttpServletResponse()

        expect:
            interceptor.preHandle(request, response, null)
            request.getAttribute(SentryTracingInterceptor.TRANSACTION_ATTRIBUTE) == null
    }
}
```

- [ ] **Step 2: Run interceptor tests to verify they fail**

Run: `./gradlew.bat test --tests "grails.plugin.sentry.SentryTracingInterceptorSpec"`

Expected: FAIL because `SentryTracingInterceptor` and `SentryTracingFilter.TRANSACTION_CONTEXT_ATTRIBUTE` do not exist yet.

- [ ] **Step 3: Implement `SentryTracingInterceptor`**

Create `src/main/groovy/grails/plugin/sentry/SentryTracingInterceptor.groovy`:

```groovy
package grails.plugin.sentry

import io.sentry.ITransaction
import io.sentry.Sentry
import io.sentry.SpanStatus
import io.sentry.TransactionContext
import io.sentry.TransactionOptions
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.ModelAndView

import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

class SentryTracingInterceptor implements HandlerInterceptor {

    static final String TRANSACTION_ATTRIBUTE = 'sentry.transaction'

    SentryConfig config

    SentryTracingInterceptor(SentryConfig config) {
        this.config = config
    }

    @Override
    boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!config?.tracingEnabled) {
            return true
        }

        TransactionOptions txOptions = new TransactionOptions()
        txOptions.bindToScope = true

        ITransaction transaction
        TransactionContext context = request.getAttribute(SentryTracingFilter.TRANSACTION_CONTEXT_ATTRIBUTE) as TransactionContext
        if (config.distributedTracingEnabled && context) {
            transaction = Sentry.startTransaction(context, txOptions)
        } else {
            transaction = Sentry.startTransaction(transactionName(request), 'http.server', txOptions)
        }

        request.setAttribute(TRANSACTION_ATTRIBUTE, transaction)
        Sentry.setTransaction(transaction.name)

        true
    }

    @Override
    void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler, ModelAndView modelAndView) {
    }

    @Override
    void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        ITransaction transaction = request.getAttribute(TRANSACTION_ATTRIBUTE) as ITransaction
        if (!transaction || transaction.finished) {
            return
        }

        transaction.finish(statusFor(response.status))
    }

    private static String transactionName(HttpServletRequest request) {
        String controller = request.getAttribute('org.grails.CONTROLLER_NAME_ATTRIBUTE') as String ?: 'unknown'
        String action = request.getAttribute('org.grails.ACTION_NAME_ATTRIBUTE') as String ?: 'index'
        "${controller}/${action}"
    }

    private static SpanStatus statusFor(int status) {
        if (status < 400) {
            return SpanStatus.OK
        }
        SpanStatus.fromHttpStatusCode(status, SpanStatus.INTERNAL_ERROR)
    }
}
```

- [ ] **Step 4: Implement `SentryMvcConfigurer`**

Create `src/main/groovy/grails/plugin/sentry/SentryMvcConfigurer.groovy`:

```groovy
package grails.plugin.sentry

import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurerAdapter

class SentryMvcConfigurer extends WebMvcConfigurerAdapter {

    SentryTracingInterceptor tracingInterceptor

    @Override
    void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(tracingInterceptor)
    }
}
```

- [ ] **Step 5: Run interceptor tests to verify they pass**

Run: `./gradlew.bat test --tests "grails.plugin.sentry.SentryTracingInterceptorSpec"`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/groovy/grails/plugin/sentry/SentryTracingInterceptor.groovy src/main/groovy/grails/plugin/sentry/SentryMvcConfigurer.groovy src/test/groovy/grails/plugin/sentry/SentryTracingInterceptorSpec.groovy
git commit -m "feat: add sentry request tracing interceptor"
```

---

### Task 5: Add Service Span Aspect

**Files:**
- Create: `src/main/groovy/grails/plugin/sentry/SentryServiceTracingAspect.groovy`
- Test: `src/test/groovy/grails/plugin/sentry/SentryServiceTracingAspectSpec.groovy`

- [ ] **Step 1: Write failing aspect tests**

Create `src/test/groovy/grails/plugin/sentry/SentryServiceTracingAspectSpec.groovy`:

```groovy
package grails.plugin.sentry

import io.sentry.ITransaction
import io.sentry.Sentry
import io.sentry.SentryOptions
import io.sentry.SpanStatus
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.Signature
import spock.lang.Specification

class SentryServiceTracingAspectSpec extends Specification {

    SentryOptions options

    def setup() {
        RecordingTransport.reset()
        Sentry.close()
        options = new SentryOptions()
        options.dsn = 'https://foo:bar@example.com/123'
        options.tracesSampleRate = 1.0d
        options.transportFactory = new RecordingTransportFactory()
        Sentry.init(options)
    }

    def cleanup() {
        Sentry.close()
        RecordingTransport.reset()
    }

    def "creates child span around service method when transaction is active"() {
        given:
            ITransaction transaction = Sentry.startTransaction('book/show', 'http.server')
            Sentry.configureScope { scope -> scope.span = transaction }
            SentryServiceTracingAspect aspect = new SentryServiceTracingAspect()
            ProceedingJoinPoint joinPoint = joinPointFor(new BookService(), 'loadBook', 'ok')

        when:
            def result = aspect.traceServiceMethod(joinPoint)
            transaction.finish()
            Sentry.flush(5000)

        then:
            result == 'ok'
            RecordingTransport.transactions(options).first().spans*.op == ['grails.service']
            RecordingTransport.transactions(options).first().spans*.description == ['BookService.loadBook']
    }

    def "does not create span when no transaction is active"() {
        given:
            SentryServiceTracingAspect aspect = new SentryServiceTracingAspect()
            ProceedingJoinPoint joinPoint = joinPointFor(new BookService(), 'loadBook', 'ok')

        expect:
            aspect.traceServiceMethod(joinPoint) == 'ok'
            RecordingTransport.transactions(options).empty
    }

    def "marks child span internal error and rethrows"() {
        given:
            ITransaction transaction = Sentry.startTransaction('book/show', 'http.server')
            Sentry.configureScope { scope -> scope.span = transaction }
            RuntimeException failure = new RuntimeException('boom')
            SentryServiceTracingAspect aspect = new SentryServiceTracingAspect()
            ProceedingJoinPoint joinPoint = failingJoinPointFor(new BookService(), 'loadBook', failure)

        when:
            aspect.traceServiceMethod(joinPoint)

        then:
            RuntimeException ex = thrown()
            ex.is(failure)

        when:
            transaction.finish()
            Sentry.flush(5000)

        then:
            RecordingTransport.transactions(options).first().spans.first().status == SpanStatus.INTERNAL_ERROR
    }

    private static ProceedingJoinPoint joinPointFor(Object target, String methodName, Object result) {
        Mock(ProceedingJoinPoint) {
            getTarget() >> target
            getSignature() >> signature(methodName)
            proceed() >> result
        }
    }

    private static ProceedingJoinPoint failingJoinPointFor(Object target, String methodName, Throwable failure) {
        Mock(ProceedingJoinPoint) {
            getTarget() >> target
            getSignature() >> signature(methodName)
            proceed() >> { throw failure }
        }
    }

    private static Signature signature(String methodName) {
        Mock(Signature) {
            getName() >> methodName
        }
    }

    static class BookService {
    }
}
```

- [ ] **Step 2: Run aspect tests to verify they fail**

Run: `./gradlew.bat test --tests "grails.plugin.sentry.SentryServiceTracingAspectSpec"`

Expected: FAIL because `SentryServiceTracingAspect` does not exist yet.

- [ ] **Step 3: Implement `SentryServiceTracingAspect`**

Create `src/main/groovy/grails/plugin/sentry/SentryServiceTracingAspect.groovy`:

```groovy
package grails.plugin.sentry

import io.sentry.ISpan
import io.sentry.Sentry
import io.sentry.SpanStatus
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect

@Aspect
class SentryServiceTracingAspect {

    @Around('execution(public * *(..)) && (@within(grails.transaction.Transactional) || @within(org.springframework.transaction.annotation.Transactional) || within(grails.app.services..*))')
    Object traceServiceMethod(ProceedingJoinPoint joinPoint) throws Throwable {
        ISpan activeSpan = Sentry.getSpan()
        if (!activeSpan) {
            return joinPoint.proceed()
        }

        ISpan span = activeSpan.startChild('grails.service', spanName(joinPoint))
        try {
            return joinPoint.proceed()
        } catch (Throwable throwable) {
            span.throwable = throwable
            span.status = SpanStatus.INTERNAL_ERROR
            throw throwable
        } finally {
            span.finish()
        }
    }

    private static String spanName(ProceedingJoinPoint joinPoint) {
        "${joinPoint.target?.class?.simpleName ?: 'UnknownService'}.${joinPoint.signature.name}"
    }
}
```

- [ ] **Step 4: Run aspect tests to verify they pass**

Run: `./gradlew.bat test --tests "grails.plugin.sentry.SentryServiceTracingAspectSpec"`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/groovy/grails/plugin/sentry/SentryServiceTracingAspect.groovy src/test/groovy/grails/plugin/sentry/SentryServiceTracingAspectSpec.groovy
git commit -m "feat: add sentry service span aspect"
```

---

### Task 6: Add Breadcrumb Appender

**Files:**
- Create: `src/main/groovy/grails/plugin/sentry/SentryBreadcrumbAppender.groovy`
- Test: `src/test/groovy/grails/plugin/sentry/SentryBreadcrumbAppenderSpec.groovy`

- [ ] **Step 1: Write failing breadcrumb appender tests**

Create `src/test/groovy/grails/plugin/sentry/SentryBreadcrumbAppenderSpec.groovy`:

```groovy
package grails.plugin.sentry

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import io.sentry.Sentry
import io.sentry.SentryOptions
import io.sentry.SentryLevel
import org.slf4j.LoggerFactory
import spock.lang.Specification

class SentryBreadcrumbAppenderSpec extends Specification {

    SentryOptions options
    Logger logger
    SentryBreadcrumbAppender breadcrumbAppender
    GrailsLogbackSentryAppender sentryAppender

    def setup() {
        RecordingTransport.reset()
        Sentry.close()
        options = new SentryOptions()
        options.dsn = 'https://foo:bar@example.com/123'
        options.transportFactory = new RecordingTransportFactory()
        Sentry.init(options)

        LoggerContext context = LoggerFactory.getILoggerFactory() as LoggerContext
        logger = context.getLogger('breadcrumb-test')
        logger.detachAndStopAllAppenders()
        logger.additive = false

        SentryConfig config = new SentryConfig([
                dsn: 'https://foo:bar@example.com/123',
                breadcrumbsEnabled: true,
                breadcrumbLevel: 'INFO',
                levels: ['ERROR']
        ])
        breadcrumbAppender = new SentryBreadcrumbAppender(config)
        breadcrumbAppender.context = context
        breadcrumbAppender.start()
        logger.addAppender(breadcrumbAppender)

        sentryAppender = new GrailsLogbackSentryAppender(config)
        sentryAppender.context = context
        sentryAppender.start()
        logger.addAppender(sentryAppender)
    }

    def cleanup() {
        logger?.detachAndStopAllAppenders()
        Sentry.close()
        RecordingTransport.reset()
    }

    def "qualifying log event becomes breadcrumb on later error event"() {
        when:
            logger.info('Loading {}', 'book')
            logger.error('Failed', new RuntimeException('boom'))
            Sentry.flush(5000)

        then:
            def event = RecordingTransport.events(options).first()
            event.breadcrumbs*.message == ['Loading book']
            event.breadcrumbs*.category == ['breadcrumb-test']
            event.breadcrumbs*.level == [SentryLevel.INFO]
    }

    def "events below breadcrumb level are ignored"() {
        given:
            breadcrumbAppender.config = new SentryConfig([
                    dsn: 'https://foo:bar@example.com/123',
                    breadcrumbsEnabled: true,
                    breadcrumbLevel: 'WARN',
                    levels: ['ERROR']
            ])

        when:
            logger.info('Ignored breadcrumb')
            logger.warn('Recorded breadcrumb')
            logger.error('Failed', new RuntimeException('boom'))
            Sentry.flush(5000)

        then:
            RecordingTransport.events(options).first().breadcrumbs*.message == ['Recorded breadcrumb']
    }

    def "logback level maps to sentry breadcrumb level"() {
        expect:
            SentryBreadcrumbAppender.toSentryLevel(Level.DEBUG) == SentryLevel.DEBUG
            SentryBreadcrumbAppender.toSentryLevel(Level.INFO) == SentryLevel.INFO
            SentryBreadcrumbAppender.toSentryLevel(Level.WARN) == SentryLevel.WARNING
            SentryBreadcrumbAppender.toSentryLevel(Level.ERROR) == SentryLevel.ERROR
    }
}
```

- [ ] **Step 2: Run breadcrumb tests to verify they fail**

Run: `./gradlew.bat test --tests "grails.plugin.sentry.SentryBreadcrumbAppenderSpec"`

Expected: FAIL because `SentryBreadcrumbAppender` does not exist yet.

- [ ] **Step 3: Implement `SentryBreadcrumbAppender`**

Create `src/main/groovy/grails/plugin/sentry/SentryBreadcrumbAppender.groovy`:

```groovy
package grails.plugin.sentry

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import groovy.transform.CompileStatic
import io.sentry.Breadcrumb
import io.sentry.Sentry
import io.sentry.SentryLevel

@CompileStatic
class SentryBreadcrumbAppender extends AppenderBase<ILoggingEvent> {

    SentryConfig config

    SentryBreadcrumbAppender(SentryConfig config) {
        this.config = config
    }

    @Override
    protected void append(ILoggingEvent event) {
        if (!config?.active || !config.breadcrumbsEnabled) {
            return
        }

        if (!event.level.isGreaterOrEqual(Level.toLevel(config.breadcrumbLevel, Level.INFO))) {
            return
        }

        Breadcrumb breadcrumb = new Breadcrumb()
        breadcrumb.category = event.loggerName
        breadcrumb.message = event.formattedMessage
        breadcrumb.level = toSentryLevel(event.level)
        breadcrumb.type = 'default'

        Sentry.addBreadcrumb(breadcrumb)
    }

    static SentryLevel toSentryLevel(Level level) {
        if (level == Level.ERROR) {
            return SentryLevel.ERROR
        }
        if (level == Level.WARN) {
            return SentryLevel.WARNING
        }
        if (level == Level.DEBUG || level == Level.TRACE) {
            return SentryLevel.DEBUG
        }
        SentryLevel.INFO
    }
}
```

- [ ] **Step 4: Run breadcrumb tests to verify they pass**

Run: `./gradlew.bat test --tests "grails.plugin.sentry.SentryBreadcrumbAppenderSpec"`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/groovy/grails/plugin/sentry/SentryBreadcrumbAppender.groovy src/test/groovy/grails/plugin/sentry/SentryBreadcrumbAppenderSpec.groovy
git commit -m "feat: add sentry breadcrumb appender"
```

---

### Task 7: Add Distributed Tracing Filter

**Files:**
- Create: `src/main/groovy/grails/plugin/sentry/SentryTracingFilter.groovy`
- Test: `src/test/groovy/grails/plugin/sentry/SentryTracingFilterSpec.groovy`

- [ ] **Step 1: Write failing filter tests**

Create `src/test/groovy/grails/plugin/sentry/SentryTracingFilterSpec.groovy`:

```groovy
package grails.plugin.sentry

import io.sentry.TransactionContext
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import spock.lang.Specification

class SentryTracingFilterSpec extends Specification {

    def "stores transaction context from inbound trace headers"() {
        given:
            SentryTracingFilter filter = new SentryTracingFilter()
            MockHttpServletRequest request = new MockHttpServletRequest()
            MockHttpServletResponse response = new MockHttpServletResponse()
            MockFilterChain chain = new MockFilterChain()
            request.addHeader('sentry-trace', '771a43a4192642f0b136d5159a501700-6e8f22c393e68f19-1')
            request.addHeader('baggage', 'sentry-trace_id=771a43a4192642f0b136d5159a501700,sentry-public_key=public')

        when:
            filter.doFilter(request, response, chain)

        then:
            TransactionContext context = request.getAttribute(SentryTracingFilter.TRANSACTION_CONTEXT_ATTRIBUTE) as TransactionContext
            context != null
            context.traceId.toString() == '771a43a4192642f0b136d5159a501700'
            context.parentSpanId.toString() == '6e8f22c393e68f19'
    }

    def "continues filter chain when headers are absent"() {
        given:
            SentryTracingFilter filter = new SentryTracingFilter()
            MockHttpServletRequest request = new MockHttpServletRequest()
            MockHttpServletResponse response = new MockHttpServletResponse()
            MockFilterChain chain = new MockFilterChain()

        when:
            filter.doFilter(request, response, chain)

        then:
            request.getAttribute(SentryTracingFilter.TRANSACTION_CONTEXT_ATTRIBUTE) == null
            chain.request.is(request)
            chain.response.is(response)
    }
}
```

- [ ] **Step 2: Run filter tests to verify they fail**

Run: `./gradlew.bat test --tests "grails.plugin.sentry.SentryTracingFilterSpec"`

Expected: FAIL because `SentryTracingFilter` does not exist yet.

- [ ] **Step 3: Implement `SentryTracingFilter`**

Create `src/main/groovy/grails/plugin/sentry/SentryTracingFilter.groovy`:

```groovy
package grails.plugin.sentry

import groovy.transform.CompileStatic
import io.sentry.Sentry
import io.sentry.TransactionContext

import javax.servlet.Filter
import javax.servlet.FilterChain
import javax.servlet.ServletRequest
import javax.servlet.ServletResponse
import javax.servlet.http.HttpServletRequest

@CompileStatic
class SentryTracingFilter implements Filter {

    static final String TRANSACTION_CONTEXT_ATTRIBUTE = 'sentry.transactionContext'

    @Override
    void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) {
        HttpServletRequest httpRequest = request as HttpServletRequest
        String sentryTrace = httpRequest.getHeader('sentry-trace')

        if (sentryTrace) {
            List<String> baggage = Collections.list(httpRequest.getHeaders('baggage')) as List<String>
            TransactionContext context = Sentry.continueTrace(sentryTrace, baggage)
            request.setAttribute(TRANSACTION_CONTEXT_ATTRIBUTE, context)
        }

        chain.doFilter(request, response)
    }
}
```

- [ ] **Step 4: Run filter tests to verify they pass**

Run: `./gradlew.bat test --tests "grails.plugin.sentry.SentryTracingFilterSpec"`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/groovy/grails/plugin/sentry/SentryTracingFilter.groovy src/test/groovy/grails/plugin/sentry/SentryTracingFilterSpec.groovy
git commit -m "feat: add sentry distributed tracing filter"
```

---

### Task 8: Wire Feature Beans Into the Plugin

**Files:**
- Modify: `src/main/groovy/grails/plugin/sentry/SentryGrailsPlugin.groovy`
- Modify: `src/test/groovy/grails/plugin/sentry/SanityIntegrationSpec.groovy`

- [ ] **Step 1: Add regression assertions to sanity spec**

In `src/test/groovy/grails/plugin/sentry/SanityIntegrationSpec.groovy`, add these properties under the existing `GrailsLogbackSentryAppender sentryAppender` line:

```groovy
    def grailsApplication
    def applicationContext
```

Add this new test before `def "everything works"()`:

```groovy
    def "new SDK v7 features are disabled by default"() {
        when:
            SentryConfig config = new SentryConfig(grailsApplication.config.grails.plugin.sentry)

        then:
            !config.tracingEnabled
            !config.breadcrumbsEnabled
            !config.distributedTracingEnabled
            !applicationContext.containsBean('sentryTracingInterceptor')
            !applicationContext.containsBean('sentryMvcConfigurer')
            !applicationContext.containsBean('sentryServiceTracingAspect')
            !applicationContext.containsBean('sentryBreadcrumbAppender')
            !applicationContext.containsBean('sentryTracingFilter')
    }
```

- [ ] **Step 2: Run sanity spec to verify it fails**

Run: `./gradlew.bat test --tests "grails.plugin.sentry.SanityIntegrationSpec"`

Expected: FAIL until config defaults and bean wiring are both correct.

- [ ] **Step 3: Add imports to `SentryGrailsPlugin`**

Add these imports at the top of `SentryGrailsPlugin.groovy`:

```groovy
import org.springframework.aop.aspectj.annotation.AnnotationAwareAspectJAutoProxyCreator
import org.springframework.core.Ordered
```

- [ ] **Step 4: Set tracing sample rate during Sentry init**

Inside the existing `Sentry.init { SentryOptions options ->` block, immediately after `options.dsn = pluginConfig.dsn.toString()`, add:

```groovy
                        if (pluginConfig.tracingEnabled) {
                            options.tracesSampleRate = pluginConfig.tracesSampleRate
                        }
```

- [ ] **Step 5: Register feature beans in `doWithSpring`**

In `doWithSpring()`, immediately after `sentryAppender(GrailsLogbackSentryAppender, pluginConfig)`, add:

```groovy
                if (pluginConfig.tracingEnabled) {
                    sentryTracingInterceptor(SentryTracingInterceptor, pluginConfig)
                    sentryMvcConfigurer(SentryMvcConfigurer) {
                        tracingInterceptor = ref('sentryTracingInterceptor')
                    }
                }

                if (pluginConfig.serviceTracingActive) {
                    sentryAspectAutoProxyCreator(AnnotationAwareAspectJAutoProxyCreator) {
                        proxyTargetClass = true
                    }
                    sentryServiceTracingAspect(SentryServiceTracingAspect)
                }

                if (pluginConfig.breadcrumbsEnabled) {
                    sentryBreadcrumbAppender(SentryBreadcrumbAppender, pluginConfig)
                }

                if (pluginConfig.distributedTracingEnabled) {
                    sentryTracingFilter(FilterRegistrationBean) {
                        filter = bean(SentryTracingFilter)
                        urlPatterns = ['/*']
                        order = Ordered.HIGHEST_PRECEDENCE + 10
                    }
                }
```

- [ ] **Step 6: Attach both Logback appenders**

Replace the appender attachment block in `doWithApplicationContext()`:

```groovy
        GrailsLogbackSentryAppender appender = applicationContext.getBean(GrailsLogbackSentryAppender)
        if (appender) {
            LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory()
            if (pluginConfig.loggers) {
                pluginConfig.loggers.each { String logger ->
                    loggerContext.getLogger(logger).addAppender(appender)
                }
            } else {
                loggerContext.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(appender)
            }
            appender.setContext(loggerContext)
            appender.start()
        }
```

with:

```groovy
        LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory()

        GrailsLogbackSentryAppender appender = applicationContext.getBean(GrailsLogbackSentryAppender)
        if (appender) {
            attachAppender(loggerContext, pluginConfig, appender)
        }

        if (pluginConfig.breadcrumbsEnabled) {
            SentryBreadcrumbAppender breadcrumbAppender = applicationContext.getBean(SentryBreadcrumbAppender)
            if (breadcrumbAppender) {
                attachAppender(loggerContext, pluginConfig, breadcrumbAppender)
            }
        }
```

Add this helper method before `getSentryConfig()`:

```groovy
    private static void attachAppender(LoggerContext loggerContext, SentryConfig pluginConfig, def appender) {
        appender.setContext(loggerContext)
        appender.start()

        if (pluginConfig.loggers) {
            pluginConfig.loggers.each { String logger ->
                loggerContext.getLogger(logger).addAppender(appender)
            }
        } else {
            loggerContext.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(appender)
        }
    }
```

- [ ] **Step 7: Run wiring regression**

Run: `./gradlew.bat test --tests "grails.plugin.sentry.SanityIntegrationSpec"`

Expected: PASS.

- [ ] **Step 8: Run all focused feature tests**

Run:

```bash
./gradlew.bat test --tests "grails.plugin.sentry.SentryConfigSpec" --tests "grails.plugin.sentry.SentryTracingInterceptorSpec" --tests "grails.plugin.sentry.SentryServiceTracingAspectSpec" --tests "grails.plugin.sentry.SentryBreadcrumbAppenderSpec" --tests "grails.plugin.sentry.SentryTracingFilterSpec" --tests "grails.plugin.sentry.SanityIntegrationSpec"
```

Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add src/main/groovy/grails/plugin/sentry/SentryGrailsPlugin.groovy src/test/groovy/grails/plugin/sentry/SanityIntegrationSpec.groovy
git commit -m "feat: wire sentry v7 feature beans"
```

---

### Task 9: Document New Config

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Add README config keys**

In `README.md`, add this section near the existing configuration documentation:

````markdown
### SDK v7 tracing and breadcrumbs

These features are opt-in and disabled by default:

```yaml
grails:
    plugin:
        sentry:
            tracingEnabled: false
            tracesSampleRate: 1.0
            traceServices: true
            breadcrumbsEnabled: false
            breadcrumbLevel: INFO
            distributedTracingEnabled: false
```

`tracingEnabled` wraps each Grails controller action in a Sentry transaction via a Spring `HandlerInterceptor`. The transaction name is `controllerName/actionName`, and `tracesSampleRate` is passed to the Sentry Java SDK when the client initialises.

`traceServices` creates child spans for Grails service methods when `tracingEnabled` is also true. It does not create standalone transactions for background work.

`breadcrumbsEnabled` records qualifying Logback messages as Sentry breadcrumbs. `breadcrumbLevel` accepts Logback levels such as `DEBUG`, `INFO`, `WARN`, and `ERROR`.

`distributedTracingEnabled` installs a servlet filter that reads inbound `sentry-trace` and `baggage` headers. When request tracing is also enabled, the controller transaction continues the inbound trace.

The plugin does not add outbound HTTP propagation, JDBC instrumentation, or request body capture.
````

- [ ] **Step 2: Verify README has no stale contradiction**

Run: `grep -n "tracingEnabled\|breadcrumbsEnabled\|distributedTracingEnabled\|sentry-spring\|logHttpRequest" README.md`

Expected: output includes the new section and does not claim that performance tracing is unavailable.

- [ ] **Step 3: Commit**

```bash
git add README.md
git commit -m "docs: document sentry v7 tracing features"
```

---

### Task 10: Final Verification

**Files:**
- All files changed in Tasks 1-9

- [ ] **Step 1: Run all tests**

Run: `./gradlew.bat test`

Expected: PASS.

- [ ] **Step 2: Run full build**

Run: `./gradlew.bat build`

Expected: PASS.

- [ ] **Step 3: Inspect git status**

Run: `git status --short`

Expected: only intentional tracked changes remain, or clean if all task commits were made. Existing untracked `.claude/`, `AGENTS.md`, and `docs/` from earlier planning work must not be included unless the user explicitly wants them committed.

- [ ] **Step 4: Final commit if needed**

If documentation or plan-only changes are still staged or unstaged, commit them separately:

```bash
git add docs/superpowers/plans/2026-04-30-sentry-v7-features.md
git commit -m "docs: add sentry v7 features implementation plan"
```
