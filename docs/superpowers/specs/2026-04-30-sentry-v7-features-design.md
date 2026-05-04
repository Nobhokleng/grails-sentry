# Sentry SDK v7 Features — Plugin Design Spec

**Date:** 2026-04-30
**Plugin:** grails-sentry
**Target:** Grails 3.3.8 / Sentry Java SDK 7.14.0

---

## Goal

Expose Sentry SDK v7's performance monitoring, breadcrumbs, and distributed tracing to Grails 3.3.8 applications automatically — zero code changes required in consuming apps. All features are controlled exclusively by `application.yml` toggles.

---

## Architecture Overview

Four new components are added alongside the existing `GrailsLogbackSentryAppender`. All are registered as Spring beans in `SentryGrailsPlugin.doWithSpring()`. Each is independently toggled by config and defaults to `false` so existing users see no behaviour change.

```
HTTP Request
    │
    ├─ SentryTracingFilter          ← continues inbound sentry-trace/baggage headers
    │
    ├─ SentryTracingInterceptor     ← starts transaction "controllerName/actionName"
    │       │
    │       └─ @Service methods     ← SentryServiceTracingAspect creates child spans
    │
    ├─ SentryTracingInterceptor     ← sets HTTP status, finishes transaction
    │
    └─ log.info/warn/error          ← SentryBreadcrumbAppender records breadcrumbs
```

---

## Configuration

### New keys in `SentryConfig` and `plugin.yml`

```yaml
grails:
    plugin:
        sentry:
            # Performance tracing
            tracingEnabled: ${SENTRY_TRACING_ENABLED:false}
            tracesSampleRate: ${SENTRY_TRACES_SAMPLE_RATE:1.0}
            traceServices: ${SENTRY_TRACE_SERVICES:true}

            # Breadcrumbs
            breadcrumbsEnabled: ${SENTRY_BREADCRUMBS_ENABLED:false}
            breadcrumbLevel: ${SENTRY_BREADCRUMB_LEVEL:INFO}

            # Distributed tracing
            distributedTracingEnabled: ${SENTRY_DISTRIBUTED_TRACING_ENABLED:false}
```

### Config rules

- `tracingEnabled: false` disables `traceServices` and all span creation regardless of its own value.
- `tracesSampleRate` is passed to `SentryOptions` at `Sentry.init` time — SDK handles sampling.
- `breadcrumbsEnabled` is independent of `tracingEnabled` — breadcrumbs work without performance tracing.
- `distributedTracingEnabled` is independent — inbound trace continuation works without generating transactions.
- All new fields default to `false` — no behaviour change for existing users.

### New `SentryConfig` fields

```groovy
boolean tracingEnabled = false
double tracesSampleRate = 1.0
boolean traceServices = true
boolean breadcrumbsEnabled = false
String breadcrumbLevel = 'INFO'
boolean distributedTracingEnabled = false
```

---

## Component Designs

### 1. `SentryTracingInterceptor` — Grails Interceptor

**File:** `src/main/groovy/grails/plugin/sentry/SentryTracingInterceptor.groovy`

**Purpose:** Wrap every Grails controller action in a Sentry transaction.

**Behaviour:**
- Matches all controllers via `matchAll()`
- `before()`:
  - If `distributedTracingEnabled`, retrieves `TransactionContext` from request attribute set by `SentryTracingFilter` and starts transaction from it (continues inbound trace)
  - Otherwise starts a fresh transaction named `"$controllerName/$actionName"` with operation `"http.server"`
  - Stores the `ITransaction` as a request attribute for retrieval in `afterView()`
  - Sets transaction name on the current scope
- `afterView()`:
  - Retrieves the transaction from the request attribute
  - Maps HTTP response status to `SpanStatus` (2xx/3xx → `OK`, 4xx → `INVALID_ARGUMENT`, 5xx → `INTERNAL_ERROR`)
  - Calls `transaction.finish(spanStatus)`

**Guard:** Only runs when `tracingEnabled: true` — checked in `before()`, returns `true` immediately otherwise.

---

### 2. `SentryServiceTracingAspect` — Spring AOP Aspect

**File:** `src/main/groovy/grails/plugin/sentry/SentryServiceTracingAspect.groovy`

**Purpose:** Wrap `@Service`/`@Transactional` Grails service methods as child spans.

**Pointcut:**
```
execution(public * *(..)) &&
(
  @within(grails.transaction.Transactional) ||
  @within(org.springframework.transaction.annotation.Transactional) ||
  within(grails.app.services..*)
)
```

The `within(grails.app.services..*)` arm catches conventional Grails services that declare `static transactional = true` rather than using the annotation.

**`@Around` advice behaviour:**
- Checks `Sentry.getSpan() != null` — only creates a span if a transaction is already active. If no transaction is active (e.g. background job, test), proceeds without creating a span.
- Span name: `"$simpleClassName.$methodName"`
- Operation: `"grails.service"`
- On exception: marks span `INTERNAL_ERROR`, re-throws. Does not capture the exception — the Logback appender handles that.
- Always calls `span.finish()` in `finally`.

**Guard:** Bean only registered when `tracingEnabled: true && traceServices: true`.

---

### 3. `SentryBreadcrumbAppender` — Logback Appender

**File:** `src/main/groovy/grails/plugin/sentry/SentryBreadcrumbAppender.groovy`

**Purpose:** Convert qualifying log events to Sentry breadcrumbs automatically.

**Extends:** `AppenderBase<ILoggingEvent>`

**Behaviour:**
- Filters events below `breadcrumbLevel` (default `INFO`)
- For each qualifying event, creates a `Breadcrumb`:
  - `category` = logger name
  - `message` = formatted log message
  - `level` = mapped from Logback level (`INFO→INFO`, `WARN→WARNING`, `ERROR→ERROR`, `DEBUG→DEBUG`)
  - `type` = `"default"`
- Calls `Sentry.addBreadcrumb(breadcrumb)`
- Does **not** capture a Sentry event — that is `GrailsLogbackSentryAppender`'s responsibility

**Registration:** Added to the same loggers as `GrailsLogbackSentryAppender` in `doWithApplicationContext`. Both appenders coexist on each logger.

**Guard:** Only registered when `breadcrumbsEnabled: true`.

---

### 4. `SentryTracingFilter` — Servlet Filter

**File:** `src/main/groovy/grails/plugin/sentry/SentryTracingFilter.groovy`

**Purpose:** Continue inbound distributed traces from upstream services.

**Implements:** `javax.servlet.Filter`

**Behaviour:**
- Reads `sentry-trace` header from incoming request
- Reads `baggage` header from incoming request
- Calls `Sentry.continueTrace(sentryTraceHeader, baggageHeaders)` to obtain a `TransactionContext`
- Stores the `TransactionContext` as request attribute `"sentry.transactionContext"` for `SentryTracingInterceptor` to consume
- Calls `chain.doFilter()` to continue the request

**Registration:** Registered via `FilterRegistrationBean` on `/*` with order `Ordered.HIGHEST_PRECEDENCE + 10` — runs before the MDC filter (`HIGHEST_PRECEDENCE + 20`) so the trace context is available to all downstream filters. Only registered when `distributedTracingEnabled: true`.

---

## Files Changed

| File | Change |
|------|--------|
| `SentryConfig.groovy` | Add 6 new config fields with parsing |
| `SentryGrailsPlugin.groovy` | Register 4 new beans conditionally in `doWithSpring`; set `tracesSampleRate` in `Sentry.init` |
| `plugin.yml` | Add 6 new keys with env var defaults |
| `SentryTracingInterceptor.groovy` | New file |
| `SentryServiceTracingAspect.groovy` | New file |
| `SentryBreadcrumbAppender.groovy` | New file |
| `SentryTracingFilter.groovy` | New file |

---

## Testing

Each component has a dedicated `@Integration` Spock spec using the existing Ersatz mock server pattern.

| Spec | What it verifies |
|------|-----------------|
| `TracingInterceptorIntegrationSpec` | Transaction envelope sent with correct name and HTTP status |
| `ServiceTracingAspectIntegrationSpec` | Child span present in transaction; no span when no active transaction |
| `BreadcrumbAppenderIntegrationSpec` | Breadcrumbs attached to error events; `breadcrumbLevel` filter respected |
| `DistributedTracingFilterIntegrationSpec` | Captured transaction carries same trace ID as inbound `sentry-trace` header |
| `SanityIntegrationSpec` (updated) | All new features `false` by default — no regression for existing users |

---

## Non-Goals

- No OkHttp / RestTemplate outbound trace propagation (requires additional HTTP client artifacts not bundled with `sentry-logback`)
- No JDBC span instrumentation (requires DataSource proxy not appropriate for a logging-focused plugin)
- No custom package include/exclude lists for AOP (deferred — Option C from design discussion)
- No changes to existing error capture, stack trace sanitization, or Spring Security user context behaviour
