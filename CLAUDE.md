# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

`grails-sentry` is a Grails 3.x plugin that integrates the official Sentry Java SDK (`io.sentry:sentry-logback:7.14.0`) with Grails applications. It wraps Sentry's Logback appender, enriches events with Grails/Spring context, and provides optional Spring Security user tracking.

- **Grails version**: 3.3.8
- **Sentry SDK**: sentry-logback 7.14.0
- **Plugin version convention**: mirrors Sentry SDK version (e.g., `17.14.0` for SDK `7.14.0`)

## Build & Test Commands

```bash
# Build the plugin JAR
./gradlew build

# Run all tests
./gradlew test

# Run a single test class
./gradlew test --tests "grails.plugin.sentry.SanityIntegrationSpec"

# Clean then build
./gradlew clean build
```

Tests are written in Spock and use the `@Integration` annotation for full application context. The integration test uses an Ersatz mock HTTP server to verify events reach a fake Sentry endpoint.

## Architecture

The plugin has three source files in `src/main/groovy/grails/plugin/sentry/`:

### `SentryGrailsPlugin.groovy` — Plugin lifecycle
- Implements `doWithSpring()` to call `Sentry.init` (guarded with `!Sentry.isEnabled()` to survive context reloads) and register `GrailsLogbackSentryAppender` as a Spring bean
- Static tags (app name, Grails version, custom tags, environment) and the `sanitizeStackTrace` `EventProcessor` are registered inside `Sentry.init` via `SentryOptions` — set once, thread-safe
- Implements `doWithApplicationContext()` to attach the appender to configured loggers; returns early when `active=false` or `dsn` is absent to avoid `NoSuchBeanDefinitionException`
- Optionally registers `SpringSecurityUserEventBuilderHelper` as a Sentry `EventProcessor` via `Sentry.configureScope` when `springSecurityUser` is enabled

### `SentryConfig.groovy` — Configuration model
- Parses `grails.plugin.sentry.*` config keys into a typed object
- Key options: `active`, `dsn`, `loggers` (which loggers get the appender), `levels` (which log levels to capture), `tags`, `environment`, `springSecurityUser`, `sanitizeStackTrace`
- `active` defaults to `false`; the plugin is a no-op unless `active: true` and a valid `dsn` are set

### `GrailsLogbackSentryAppender.groovy` — Custom Logback appender
- Extends `SentryAppender` from `sentry-logback:7.14.0`
- Filters events by configured log levels (defaults to ERROR and WARN)
- Per-event MDC context properties are added using `Sentry.withScope` so concurrent log events on different threads don't clobber each other's scope
- Static tags are **not** set here — they live in `SentryOptions` at init time

### `SpringSecurityUserEventBuilderHelper.groovy` — Spring Security integration
- Implements Sentry's `EventProcessor` interface (SDK v7 signature: `process(SentryEvent, Hint)`)
- Reads the authenticated principal from `springSecurityService` and maps its properties to a Sentry `User` object
- Property mapping is configurable via `springSecurityUserProperties` (e.g., map `email` to a custom field name)

## Configuration Layers

Configuration resolves in this priority order (highest wins):
1. Environment variables (`SENTRY_DSN`, `SENTRY_ACTIVE`, `SENTRY_SERVER_NAME`, etc.)
2. `grails-app/conf/application.yml` per-environment blocks
3. Plugin defaults in `grails-app/conf/plugin.yml`

The `plugin.yml` file uses `${ENV_VAR:default}` syntax so environment variables can override defaults without touching application config.

## Key Gotchas

- The plugin uses the **static Sentry API** (`Sentry.captureException()`, `Sentry.configureScope()`) — not a `SentryClient` bean. Code that references a `sentryClient` bean is from the old v1.x API and must be migrated.
- `Sentry.init` is guarded with `!Sentry.isEnabled()` so application context reloads (e.g., in integration tests) don't leak a second SDK instance.
- **`sanitizeStackTrace`** is implemented via an `EventProcessor` registered in `SentryOptions`. It calls `StackTraceUtils.isApplicationClass(frame.module)` to filter Groovy/Spring internal frames from `SentryException` stack frames. The old `toStackTraceElements` hook does not exist in `SentryAppender` v7.
- **`logHttpRequest`** logs a startup warning and is otherwise a no-op. Capturing HTTP request context requires adding `sentry-spring` to the project's dependencies — it is not bundled with `sentry-logback`.
- **`logClassName`** is always-on in SDK v7; `SentryAppender` automatically captures the logger name (which is the class name).
- The Logback MDC servlet filter (`MDCInsertingServletFilter`) is registered automatically unless `disableMDCInsertingServletFilter: true`. This is distinct from (and not a replacement for) the removed `SentryServletRequestListener`.
- Spring Security integration requires the `spring-security-core` plugin to be on the classpath; the helper is only registered when `springSecurityUser: true`.
- In the test environment (`application.yml`), `active: true` and a dummy DSN are set so integration tests can exercise the full event pipeline against an Ersatz mock server. The integration test calls `Sentry.close()` before re-initializing to avoid the re-init guard.
