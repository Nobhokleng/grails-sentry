Sentry Grails Plugin
====================

# Introduction

Sentry plugin provides a Grails client for integrating apps with [Sentry](https://sentry.io).
[Sentry](https://sentry.io) is an event logging platform primarily focused on capturing and aggregating exceptions.

It uses the official [Sentry Java SDK](https://github.com/getsentry/sentry-java) (`io.sentry:sentry-logback`) under the cover.

**Current versions:**

| Plugin | Sentry Java SDK | Grails | Java |
|--------|----------------|--------|------|
| 17.14.0 | 7.14.0 | 3.3.8+ | 8+ |

# Installation

Declare the plugin dependency in _build.gradle_:

```groovy
dependencies {
    compile("org.grails.plugins:sentry:17.14.0")
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

> **Note:** Sentry SDK v7 uses a DSN format with only a public key — there is no secret key component. Copy the DSN directly from the Sentry dashboard.

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

> **`logClassName`:** In SDK v7 the logger name (which is the class name) is always captured automatically by `SentryAppender`. This option is a no-op.

> **`logHttpRequest`:** HTTP request context capture requires the `sentry-spring` or `sentry-spring-boot` dependency, which is not bundled with this plugin. Enabling this option logs a warning. The Logback MDC servlet filter (enabled by default) still propagates available request attributes via MDC extras.

> **`subsystems` / `priorities`:** Not yet implemented.

You can also configure connection and protocol options in the DSN query string. See the [Sentry Java SDK documentation](https://docs.sentry.io/platforms/java/) for details.

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
- `logHttpRequest` is not implemented in SDK v7 with `sentry-logback` alone.

# Latest releases

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
