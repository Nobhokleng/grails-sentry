package grails.plugin.sentry

import ch.qos.logback.classic.Level
import groovy.transform.CompileStatic

/**
 * @author <a href='mailto:alexey@zhokhov.com'>Alexey Zhokhov</a>
 */
/*
    EXAMPLE
        dsn: https://foo:bar@api.sentry.io/123
        loggers: [LOGGER1, LOGGER2, LOGGER3]
        environment: staging
        serverName: dev.server.com
        levels: [ERROR]
        tags: {tag1: val1,  tag2: val2, tag3: val3}
        subsystems:
            MODULE1: [com.company.services.module1, com.company.controllers.module1]
            MODULE2: [com.company.services.module2, com.company.controllers.module2]
            MODULE3: [com.company.services.module3, com.company.controllers.module3]
        logClassName: false
        logHttpRequest: false
        disableMDCInsertingServletFilter: true
        springSecurityUser: true
        sanitizeStackTrace: true
        debug: false
        diagnosticLevel: warning
        dist: 42
        sendDefaultPii: false
        attachThreads: false
        maxRequestBodySize: small
        sampleRate: 1.0
        springSecurityUserProperties:
            id: 'id'
            email: 'emailAddress'
            username: 'login'
        profilesSampleRate: 0.25
        traceOptionsRequests: false
        enableBackpressureHandling: true
        strictTraceContinuation: false
        orgId: '12345'
        contextTags: [request_id, tenant_id]
        ignoredErrors: ['Broken pipe']
        ignoredTransactions: ['/health']
        ignoredSpanOrigins: ['grails.noisy']
        sentryLogsEnabled: false
        sentryLogsMinimumLevel: INFO
        priorities:
            HIGH: [java.lang, com.microsoft.sqlserver.jdbc.SQLServerException]
            MID: [com.company.exception]
            LOW: [java.io]
 */

@CompileStatic
class SentryConfig {

    static List<Level> defaultLevels = [Level.ERROR, Level.WARN]

    SentryConfig(Map config = [:]) {
        if (!config) {
            active = false

            return
        }

        if (config.dsn) {
            dsn = config.dsn?.toString()
            active = true
        }

        if (config.containsKey('active') && config.active as String == 'false') {
            active = false
        }

        if (config.loggers) {
            if (config.loggers instanceof List) {
                loggers = (config.loggers as List).collect { it.toString() }
            }
            if (config.loggers instanceof String) {
                loggers = (config.loggers as String).split(",").collect { it.toString() }
            }
        }

        environment = config.environment ?: environment
        serverName = config.serverName ?: serverName
        dist = config.dist ?: dist

        if (config.levels) {
            if (config.levels instanceof List) {
                levels = (config.levels as List).collect { Level.toLevel(it.toString().toUpperCase()) }
            }
            if (config.levels instanceof String) {
                levels = (config.levels as String).split(",").collect { Level.toLevel(it.toString().toUpperCase()) }
            }
        }

        if (config.tags && config.tags instanceof Map) {
            tags = config.tags as Map<String, String>
        }

        if (config.logClassName as String == 'true') {
            logClassName = true
        }
        if (config.logHttpRequest as String == 'true') {
            logHttpRequest = true
        }
        if (config.disableMDCInsertingServletFilter as String == 'true') {
            disableMDCInsertingServletFilter = true
        }
        if (config.springSecurityUser as String == 'true') {
            springSecurityUser = true
        }
        if (config.sanitizeStackTrace as String == 'true') {
            sanitizeStackTrace = true
        }

        debug = asBooleanValue(config.debug, debug)
        diagnosticLevel = config.diagnosticLevel?.toString()?.toUpperCase() ?: diagnosticLevel
        sendDefaultPii = asBooleanValue(config.sendDefaultPii, sendDefaultPii)
        attachThreads = asBooleanValue(config.attachThreads, attachThreads)
        maxRequestBodySize = config.maxRequestBodySize?.toString()?.toUpperCase() ?: maxRequestBodySize
        sampleRate = asDoubleValue(config.sampleRate, sampleRate)

        tracingEnabled = asBooleanValue(config.tracingEnabled, tracingEnabled)

        tracesSampleRate = asDoubleValue(config.tracesSampleRate, tracesSampleRate)
        profilesSampleRate = asNullableDoubleValue(config.profilesSampleRate, profilesSampleRate)

        traceServices = asBooleanValue(config.traceServices, traceServices)
        breadcrumbsEnabled = asBooleanValue(config.breadcrumbsEnabled, breadcrumbsEnabled)
        sentryLogsEnabled = asBooleanValue(config.sentryLogsEnabled, sentryLogsEnabled)

        if (config.breadcrumbLevel) {
            breadcrumbLevel = config.breadcrumbLevel.toString().toUpperCase()
        }
        if (config.sentryLogsMinimumLevel) {
            sentryLogsMinimumLevel = config.sentryLogsMinimumLevel.toString().toUpperCase()
        }

        distributedTracingEnabled = asBooleanValue(config.distributedTracingEnabled, distributedTracingEnabled)
        traceOptionsRequests = asNullableBooleanValue(config.traceOptionsRequests, traceOptionsRequests)
        enableBackpressureHandling = asNullableBooleanValue(config.enableBackpressureHandling, enableBackpressureHandling)
        forceInit = asBooleanValue(config.forceInit, forceInit)
        strictTraceContinuation = asBooleanValue(config.strictTraceContinuation, strictTraceContinuation)
        orgId = config.orgId ?: orgId
        contextTags = asStringList(config.contextTags, contextTags)
        ignoredErrors = asStringList(config.ignoredErrors, ignoredErrors)
        ignoredTransactions = asStringList(config.ignoredTransactions, ignoredTransactions)
        ignoredSpanOrigins = asStringList(config.ignoredSpanOrigins, ignoredSpanOrigins)

        if (config.springSecurityUserProperties && config.springSecurityUserProperties instanceof Map) {
            springSecurityUserProperties = new SpringSecurityUserProperties(
                    id: (config.springSecurityUserProperties as Map).id as String ?: null,
                    email: (config.springSecurityUserProperties as Map).email as String ?: null,
                    username: (config.springSecurityUserProperties as Map).username as String ?: null,
                    data: (config.springSecurityUserProperties as Map).data as List ?: null
            )
        }
    }

    private static boolean asBooleanValue(Object value, boolean defaultValue = false) {
        if (value == null) {
            return defaultValue
        }
        value.toString().equalsIgnoreCase('true')
    }

    private static Boolean asNullableBooleanValue(Object value, Boolean defaultValue = null) {
        if (value == null || value.toString().trim() == '') {
            return defaultValue
        }
        value.toString().equalsIgnoreCase('true')
    }

    private static double asDoubleValue(Object value, double defaultValue) {
        Double parsed = asNullableDoubleValue(value, null)
        parsed == null ? defaultValue : parsed
    }

    private static Double asNullableDoubleValue(Object value, Double defaultValue = null) {
        String valueStr = value?.toString()?.trim()
        if (!valueStr) {
            return defaultValue
        }
        try {
            return Double.parseDouble(valueStr)
        } catch (NumberFormatException ignored) {
            return defaultValue
        }
    }

    private static List<String> asStringList(Object value, List<String> defaultValue = []) {
        if (value == null) {
            return defaultValue
        }
        if (value instanceof List) {
            return (value as List).collect { it.toString() }
        }
        if (value instanceof String) {
            String valueStr = value.toString().trim()
            if (!valueStr) {
                return []
            }
            return valueStr.split(",").collect { it.toString().trim() }.findAll { it }
        }
        defaultValue
    }

    boolean active = false
    String dsn
    List<String> loggers = []
    String environment
    String serverName
    String dist
    List<Level> levels = defaultLevels
    Map<String, String> tags = [:]
    boolean logClassName = false
    boolean logHttpRequest = false
    boolean disableMDCInsertingServletFilter = false
    boolean springSecurityUser = false
    boolean sanitizeStackTrace = false
    boolean debug = false
    String diagnosticLevel = 'WARNING'
    boolean sendDefaultPii = false
    boolean attachThreads = false
    String maxRequestBodySize
    double sampleRate = 1.0d
    boolean tracingEnabled = false
    double tracesSampleRate = 1.0d
    Double profilesSampleRate
    boolean traceServices = true
    boolean breadcrumbsEnabled = false
    String breadcrumbLevel = 'INFO'
    boolean sentryLogsEnabled = false
    String sentryLogsMinimumLevel = 'INFO'
    boolean distributedTracingEnabled = false
    Boolean traceOptionsRequests = false
    Boolean enableBackpressureHandling
    boolean forceInit = false
    boolean strictTraceContinuation = false
    String orgId
    List<String> contextTags = []
    List<String> ignoredErrors = []
    List<String> ignoredTransactions = []
    List<String> ignoredSpanOrigins = []
    // TODO
    // priorities
    // subsystems

    boolean getServiceTracingActive() {
        tracingEnabled && traceServices
    }

    SpringSecurityUserProperties springSecurityUserProperties

    static class SpringSecurityUserProperties {
        String id
        String email
        String username
        List data
    }

}
