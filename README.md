Sentry Grails Plugin
====================

# Introduction

Sentry plugin provides a Grails client for integrating apps with [Sentry](https://sentry.io).
[Sentry](https://sentry.io) is an event logging platform primarily focused on capturing and aggregating exceptions.

It uses the official [Sentry Java SDK](https://github.com/getsentry/sentry-java) (`io.sentry:sentry-logback`) under the cover.

**Current versions:**

| Plugin | Sentry Java SDK | Grails | Java |
|--------|----------------|--------|------|
| 18.41.0 | 8.41.0 | 3.3.8+ | 8+ |

# Installation

Declare the plugin dependency in _build.gradle_:

```groovy
dependencies {
    compile("org.grails.plugins:sentry:18.41.0")
}
```

# Config

Add your Sentry DSN to _grails-app/conf/application.yml_:

```yml
grails:
    plugin:
        sentry:
            dsn: https://{PUBLIC_KEY}@o{ORG_ID}.ingest.sentry.io/{PROJECT_ID}
```

The DSN can be found in your Sentry project under _Settings → Client Keys (DSN)_.

> **Note:** The Sentry Java SDK uses a DSN format with only a public key — there is no secret key component. Copy the DSN directly from the Sentry dashboard.

By default the plugin sends events in all environments. Disable it per-environment:

```yml
environments:
    development:
        grails:
            plugin:
                sentry:
                    active: false
    test:
        grails:
            plugin:
                sentry:
                    active: false
```

## Optional configurations

```yml
grails:
    plugin:
        sentry:
            dsn: https://{PUBLIC_KEY}@o{ORG_ID}.ingest.sentry.io/{PROJECT_ID}
            # Loggers to attach the Sentry appender to (defaults to root logger)
            loggers: [LOGGER1, LOGGER2, LOGGER3]
            # Overrides the detected environment name
            environment: staging
            serverName: dev.server.com
            # Log levels that trigger Sentry events (defaults to ERROR and WARN)
            levels: [ERROR]
            tags: {tag1: val1, tag2: val2, tag3: val3}
            # Strip Groovy/Spring internal frames from stack traces
            sanitizeStackTrace: true
            # Enable Sentry SDK diagnostic output
            debug: false
            diagnosticLevel: WARNING
            # Deployment/build distribution attached to events
            dist: build-42
            # Allow PII fields supported by the Sentry SDK/logback integration
            sendDefaultPii: false
            # Attach thread snapshots to events
            attachThreads: false
            # Maximum request body size captured by supported integrations: NONE, SMALL, MEDIUM, ALWAYS
            maxRequestBodySize: SMALL
            # Error event sampling rate
            sampleRate: 1.0
            # Disable Logback MDC servlet filter (enabled by default)
            disableMDCInsertingServletFilter: true
            # Capture authenticated Spring Security user info on each event
            springSecurityUser: true
            springSecurityUserProperties:
                id: 'id'
                email: 'emailAddress'
                username: 'login'
                # Additional principal properties sent as Sentry user extras
                data:
                    - 'authorities'
```

> **`logClassName`:** The logger name (which is the class name) is captured automatically by `SentryAppender`. This option is a no-op and defaults to `false`.

> **`logHttpRequest`:** HTTP request context capture requires the `sentry-spring` or `sentry-spring-boot` dependency, which is not bundled with this plugin. This option defaults to `false`; enabling it logs a warning. The Logback MDC servlet filter (enabled by default) still propagates available request attributes via MDC extras.

> **`subsystems` / `priorities`:** Not yet implemented.

You can also configure connection and protocol options in the DSN query string. See the [Sentry Java SDK documentation](https://docs.sentry.io/platforms/java/) for details.

## Tracing and breadcrumbs

These features are opt-in and disabled by default:

```yml
grails:
    plugin:
        sentry:
            # Enable Sentry performance tracing (creates a transaction per HTTP request)
            tracingEnabled: false
            # Fraction of transactions to sample (1.0 = 100%)
            tracesSampleRate: 1.0
            # Fraction of sampled transactions to profile
            profilesSampleRate: 0.25
            # Create child spans around Grails service methods when a transaction is active
            traceServices: true
            # Disable tracing for OPTIONS requests
            traceOptionsRequests: false
            # Let the SDK reduce trace sampling under transport pressure
            enableBackpressureHandling: true
            # Require incoming baggage org ID to match this SDK org
            strictTraceContinuation: false
            orgId: '12345'
            # Record qualifying log events as Sentry breadcrumbs
            breadcrumbsEnabled: false
            # Minimum log level for breadcrumb capture
            breadcrumbLevel: INFO
            # Send Logback records to Sentry's structured Logs product
            sentryLogsEnabled: false
            sentryLogsMinimumLevel: INFO
            # Promote selected MDC keys to searchable Sentry log attributes
            contextTags:
                - request_id
                - tenant_id
            # Read inbound sentry-trace/baggage headers to continue a distributed trace
            distributedTracingEnabled: false
            # Drop noisy SDK-matched errors, transactions, or span origins
            ignoredErrors:
                - Broken pipe
            ignoredTransactions:
                - /health
            ignoredSpanOrigins:
                - grails.noisy
```

### How it works

- **`tracingEnabled`** — registers a Spring MVC `HandlerInterceptor` that starts a Sentry transaction on every request and finishes it with the HTTP response status after the controller action completes. The transaction name is `controller/action`.
- **`profilesSampleRate`** — passes the profiling sample rate to the SDK for sampled transactions. Profiling depends on tracing and on profiler support being available in the SDK/runtime setup.
- **`traceOptionsRequests`** — controls whether this plugin creates request transactions for CORS/preflight `OPTIONS` requests. Defaults to `false`.
- **`enableBackpressureHandling`** — lets the SDK reduce transaction sampling temporarily when the transport is unhealthy.
- **`traceServices`** — registers an AspectJ `@Aspect` bean that wraps public methods on `@Transactional` Grails services in child spans. Spans only fire when a parent transaction is active on the current thread. Requires `tracingEnabled: true` (or any other mechanism to start a transaction first).
- **`breadcrumbsEnabled`** — attaches a Logback appender that converts log events at or above `breadcrumbLevel` into Sentry breadcrumbs. Events that will themselves be captured as full Sentry errors (at a level in `levels`) are not also added as breadcrumbs.
- **`sentryLogsEnabled`** — sends Logback records at or above `sentryLogsMinimumLevel` to Sentry's structured Logs pipeline. This is separate from error events controlled by `levels` and breadcrumbs controlled by `breadcrumbsEnabled`.
- **`contextTags`** — tells the SDK which MDC keys should become searchable log attributes for structured logs.
- **`distributedTracingEnabled`** — registers a servlet `Filter` that reads the `sentry-trace` and `baggage` headers from inbound requests and stores the resolved `TransactionContext` as a request attribute. When `tracingEnabled` is also on, the interceptor picks up this context so the transaction is linked to the upstream trace.

## Environment recommendations

Start conservative in production, and keep Sentry disabled in automated tests unless a test explicitly verifies this plugin.

### Test

```yml
environments:
    test:
        grails:
            plugin:
                sentry:
                    active: false
```

If you are testing this plugin or your Sentry wiring, point `dsn` at a fake/local endpoint and keep costly features off:

```yml
environments:
    test:
        grails:
            plugin:
                sentry:
                    active: true
                    dsn: http://public@example.com/123
                    tracingEnabled: false
                    breadcrumbsEnabled: false
                    sentryLogsEnabled: false
                    sendDefaultPii: false
```

Recommended test defaults:

| Feature | Recommendation | Why |
|---------|----------------|-----|
| `active` | `false` | Avoid network calls, flaky tests, and polluted Sentry projects. |
| `tracingEnabled` | `false` | Adds request interceptors and transaction work that most tests do not need. |
| `traceServices` | `false` when `tracingEnabled` is true in tests | Adds AOP spans around service calls and can make tests noisier. |
| `breadcrumbsEnabled` | `false` | Usually unnecessary in automated tests. |
| `sentryLogsEnabled` | `false` | Can produce a high volume of log envelopes during tests. |
| `sendDefaultPii` | `false` | Keeps test data out of Sentry. |
| `forceInit` | `false` | Prevents repeated SDK reinitialization across test application context reloads. |

### Production

```yml
environments:
    production:
        grails:
            plugin:
                sentry:
                    active: true
                    dsn: https://{PUBLIC_KEY}@o{ORG_ID}.ingest.sentry.io/{PROJECT_ID}
                    levels: [ERROR, WARN]
                    sanitizeStackTrace: true
                    tracingEnabled: true
                    tracesSampleRate: 0.05
                    traceServices: true
                    distributedTracingEnabled: true
                    breadcrumbsEnabled: true
                    breadcrumbLevel: INFO
                    sentryLogsEnabled: false
                    sendDefaultPii: false
                    enableBackpressureHandling: true
```

Tune the sample rates for your traffic volume. A low sample rate on a busy app can still produce enough performance data.

### Feature impact guide

| Feature | Impact if enabled | Production guidance |
|---------|-------------------|---------------------|
| `levels` | Controls which Logback events become full Sentry error events. More levels means more envelopes and more cost. | Keep `ERROR, WARN` unless you have a short-term investigation need. Avoid `INFO` in production. |
| `sanitizeStackTrace` | Small CPU cost per exception event. | Usually enable; cleaner stack traces are worth the small cost. |
| `debug` | Emits SDK diagnostic logs. Can be noisy and may expose operational details in logs. | Keep disabled except during SDK troubleshooting. |
| `diagnosticLevel` | Controls SDK diagnostic verbosity when `debug=true`. | Use `WARNING` or `ERROR` in production troubleshooting. |
| `sampleRate` | Samples error events. Lower values drop some errors before sending. | Usually keep `1.0`; reduce only for very noisy applications. |
| `tracingEnabled` | Adds request transaction creation/finish work. Sends performance transactions according to `tracesSampleRate`. | Enable with a low `tracesSampleRate` first, such as `0.01` to `0.10`. |
| `tracesSampleRate` | Directly affects transaction volume and overhead. | Avoid `1.0` on high-traffic production apps unless you know the event volume is acceptable. |
| `profilesSampleRate` | Adds profiling work for sampled transactions. More CPU/storage impact than tracing alone. | Start very low, such as `0.01`, or leave unset until you need profiling. |
| `traceServices` | Adds AOP spans around public service methods when tracing is active. Can increase span count significantly. | Enable only with reasonable transaction sampling; watch span volume. |
| `distributedTracingEnabled` | Adds a servlet filter to parse inbound trace headers. | Safe for most production apps when tracing is enabled. |
| `traceOptionsRequests` | Captures `OPTIONS` requests as transactions when true. | Keep false unless preflight request performance matters. |
| `enableBackpressureHandling` | Lets SDK reduce trace sampling when transport is unhealthy. | Enable in production. |
| `breadcrumbsEnabled` | Adds log breadcrumbs to later events. Uses memory up to SDK breadcrumb limits. | Usually useful; keep `breadcrumbLevel` at `INFO` or `WARN`. |
| `sentryLogsEnabled` | Sends structured logs to Sentry. Can create high volume and cost. | Keep disabled by default; enable for selected environments or raise `sentryLogsMinimumLevel` to `WARN`/`ERROR`. |
| `contextTags` | Promotes selected MDC keys to searchable log attributes. High-cardinality keys can increase index/cardinality pressure. | Use stable keys such as `request_id`, `tenant_id`, or `job_name`; avoid raw user input. |
| `sendDefaultPii` | Allows PII capture in supported SDK integrations/logback fields. | Keep false unless your privacy policy and Sentry project settings allow it. |
| `springSecurityUser` | Adds authenticated user data through this plugin's event processor. This is separate from `sendDefaultPii`. | Enable only when the configured user fields are approved for Sentry. |
| `attachThreads` | Attaches thread snapshots to events, increasing payload size. | Enable temporarily for deadlock/thread investigations. |
| `maxRequestBodySize` | Captures request bodies in supported integrations; increases payload size and privacy risk. | Prefer `NONE` or `SMALL`; avoid `ALWAYS` in production. |
| `forceInit` | Forces SDK initialization even if already initialized. Can replace/reinitialize SDK state. | Keep false in applications; use only for controlled test/plugin scenarios. |
| `strictTraceContinuation` + `orgId` | Rejects incoming trace continuation from a different Sentry org. | Enable when accepting traffic from untrusted external clients and you know your org ID. |
| `ignoredErrors`, `ignoredTransactions`, `ignoredSpanOrigins` | Drops matching telemetry before sending. | Use for known noise such as health checks; avoid broad patterns that hide real problems. |

# Usage

## Logback Appender

The Logback Appender is automatically configured by the plugin. All application exceptions at `ERROR` and `WARN` level are forwarded to Sentry. To capture manually, use the standard logger:

```groovy
log.error("Something went wrong", exception)
```

The following tags are automatically attached to every event:

| Tag | Value |
|-----|-------|
| `grails_app_name` | Application name from metadata |
| `grails_version` | Grails version |
| `environment` | Active Grails environment |
| Any `tags` from config | As configured |

Logback MDC context properties are attached as extras on each event.

## Sentry Static API

Use the `Sentry` static API for explicit event capture:

```groovy
import io.sentry.Sentry
import io.sentry.SentryLevel

// Capture a simple message
Sentry.captureMessage("something notable happened")

// Capture a message at a specific level
Sentry.captureMessage("disk space low", SentryLevel.WARNING)

// Capture an exception
Sentry.captureException(new Exception("something failed"))

// Add context to all subsequent events on this thread
Sentry.configureScope { scope ->
    scope.setTag("transaction", "user-signup")
    scope.setExtra("planId", "pro-annual")
}

// Add context to a single event only (isolated scope)
Sentry.withScope { scope ->
    scope.setExtra("orderId", "12345")
    Sentry.captureException(exception)
}
```

# Migration guide

## From v1.x (SDK 1.7.x) to v17.x (SDK 7.x)

SDK v7 replaced the mutable `SentryClient` bean model with a static hub-based API. Remove any direct references to the `sentryClient` Spring bean.

### Sending events

```groovy
// Before (v1.x)
sentryClient.sendMessage("something happened")
sentryClient.sendException(exception)

// After (v7.x)
Sentry.captureMessage("something happened")
Sentry.captureException(exception)
```

### Adding context

```groovy
// Before (v1.x — EventBuilder)
def eventBuilder = sentryClient.newEvent("message")
    .withTag("key", "value")
    .withExtra("extra", "data")
sentryClient.send(eventBuilder.build())

// After (v7.x — scope)
Sentry.withScope { scope ->
    scope.setTag("key", "value")
    scope.setExtra("extra", "data")
    Sentry.captureMessage("message")
}
```

### What changed in the plugin

- `SentryClientFactoryProvider` bean removed — use the static `Sentry` API directly.
- `sanitizeStackTrace` is implemented via an `EventProcessor` registered at init time; it filters Groovy and Spring internal frames using `StackTraceUtils.isApplicationClass`.
- Grails metadata tags and custom tags are set once in `SentryOptions` at startup (thread-safe), not on every log event.
- The `release` field is set from `info.app.version` via `SentryOptions`.
- `logHttpRequest` is not implemented with `sentry-logback` alone.

## From v17.x (SDK 7.x) to v18.x (SDK 8.x)

The plugin now uses Sentry Java SDK 8.41.0. The current plugin code already uses the v8-compatible tracing configuration (`tracesSampleRate`) and does not use removed SDK v7 options such as `enableTracing`, `traceOrigins`, `profilingEnabled`, or `shutdownTimeout`.

When overriding or adding Sentry dependencies in a consuming application, keep all `io.sentry:*` artifacts on the same version. Mixing SDK versions can cause runtime linkage errors.

Notable SDK v8 changes:

- `enableTracing` was removed; use `tracesSampleRate`.
- `profilingEnabled` was removed; use `profilesSampleRate`.
- `shutdownTimeout` was removed; use `shutdownTimeoutMillis`.
- Metrics support was removed from SDK v8.
- `Sentry.traceHeaders()` was removed; use `Sentry.getTraceparent()`.
- Sentry scopes were reworked into global, isolation, and current scopes.

# Latest releases

* 2026-05-08 **V18.41.0** : upgrade to Sentry Java SDK 8.41.0; verified Java 8 bytecode compatibility and Grails 3.3.8 test suite
* 2026-04-30 **V17.14.0** : upgrade to Sentry Java SDK 7.14.0 with Grails 3.3.8 compatibility; migrated from `SentryClient` bean to static `Sentry` API; `sanitizeStackTrace` reimplemented via `EventProcessor`; thread-safe scope handling via `Sentry.withScope`
* 2018-07-16 **V11.7.25** : upgrade Sentry java lib to 1.7.25 + stack trace sanitizer
* 2018-07-16 **V11.7.24** : upgrade Sentry java lib to 1.7.24 + Grails 4 upgrade
* 2018-05-18 **V11.7.4** : upgrade Sentry java lib to 1.7.4 + bug fixes
* 2018-02-09 **V11.6.5** : upgrade Sentry java lib to 1.6.5 + bug fixes
* 2017-11-09 **V11.4.0.3** : fixes
* 2017-08-03 **V11.4.0.2** : fixes
* 2017-08-03 **V11.4.0** : upgrade Sentry java lib to 1.4.0 + bug fix
* 2017-07-17 **V11.3.0** : upgrade Sentry java lib to 1.3.0 + bug fix
* 2017-07-04 **V11.2.0** : upgrade Sentry java lib to 1.2.0 (replaces deprecated Raven java lib)
* 2017-06-06 **V8.0.3** : upgrade Raven java lib to 8.0.3
* 2017-02-01 **V7.8.1** : upgrade Raven java lib to 7.8.1
* 2016-11-22 **V7.8.0.2** : event environment support
* 2016-10-29 **V7.8.0.1** : minor bug fix
* 2016-10-19 **V7.8.0** : upgrade Raven java lib to 7.8.0
* 2016-10-10 **V7.7.1** : upgrade Raven java lib to 7.7.1
* 2016-09-27 **V7.7.0.1** : bug fix
* 2016-09-26 **V7.7.0** : upgrade Raven java lib to 7.7.0, release support added to events
* 2016-08-22 **V7.6.0** : upgrade Raven java lib to 7.6.0, Spring Security integration improvements
* 2016-07-22 **V7.4.0** : upgrade Raven java lib to 7.4.0, Spring Security Core support
* 2016-06-22 **V7.3.0** : upgrade Raven java lib to 7.3.0
* 2016-05-03 **V7.2.1** : upgrade Raven java lib to 7.2.1
* 2016-04-12 **V7.1.0.1** : minor update
* 2016-04-06 **V7.1.0** : upgrade Raven java lib to 7.1.0
* 2015-08-31 **V6.0.0** : initial release for Grails 3.x

## Bugs

To report any bug, please use the project [Issues](https://github.com/agorapulse/grails-sentry/issues/new) section on GitHub.

## Contributing

Please contribute using [Github Flow](https://guides.github.com/introduction/flow/). Create a branch, add commits, and [open a pull request](https://github.com/agorapulse/grails-sentry/compare/).

## License

Copyright © 2016 Alan Rafael Fachini, authors, and contributors. All rights reserved.

This project is licensed under the Apache License, Version 2.0 - see the [LICENSE](LICENSE) file for details.
