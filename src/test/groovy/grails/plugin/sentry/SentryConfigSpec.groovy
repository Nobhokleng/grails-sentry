package grails.plugin.sentry

import spock.lang.Specification

class SentryConfigSpec extends Specification {

    def "Sentry feature flags default to disabled values"() {
        when:
            SentryConfig config = new SentryConfig([dsn: 'https://foo:bar@example.com/123'])

        then:
            config.active
            !config.tracingEnabled
            config.tracesSampleRate == 1.0d
            config.traceServices
            !config.breadcrumbsEnabled
            config.breadcrumbLevel == 'INFO'
            !config.sentryLogsEnabled
            config.sentryLogsMinimumLevel == 'INFO'
            !config.distributedTracingEnabled
            !config.debug
            config.diagnosticLevel == 'WARNING'
            !config.sendDefaultPii
            !config.attachThreads
            config.sampleRate == 1.0d
            config.profilesSampleRate == null
            config.traceOptionsRequests == false
            config.enableBackpressureHandling == null
            !config.forceInit
            !config.strictTraceContinuation
            config.contextTags == []
            config.ignoredErrors == []
            config.ignoredTransactions == []
            config.ignoredSpanOrigins == []
    }

    def "Sentry feature flags parse strings and numbers"() {
        when:
            SentryConfig config = new SentryConfig([
                    dsn: 'https://foo:bar@example.com/123',
                    tracingEnabled: 'true',
                    tracesSampleRate: '0.25',
                    traceServices: 'false',
                    breadcrumbsEnabled: 'true',
                    breadcrumbLevel: 'warn',
                    sentryLogsEnabled: 'true',
                    sentryLogsMinimumLevel: 'debug',
                    distributedTracingEnabled: 'true',
                    debug: 'true',
                    diagnosticLevel: 'error',
                    dist: 'build-42',
                    sendDefaultPii: 'true',
                    attachThreads: 'true',
                    maxRequestBodySize: 'small',
                    sampleRate: '0.75',
                    profilesSampleRate: '0.5',
                    traceOptionsRequests: 'false',
                    enableBackpressureHandling: 'false',
                    forceInit: 'true',
                    strictTraceContinuation: 'true',
                    orgId: '12345',
                    contextTags: 'request_id,tenant_id',
                    ignoredErrors: 'Broken pipe,Connection reset',
                    ignoredTransactions: '/health,/ready',
                    ignoredSpanOrigins: 'grails.noisy,spring.noisy'
            ])

        then:
            config.tracingEnabled
            config.tracesSampleRate == 0.25d
            !config.traceServices
            config.breadcrumbsEnabled
            config.breadcrumbLevel == 'WARN'
            config.sentryLogsEnabled
            config.sentryLogsMinimumLevel == 'DEBUG'
            config.distributedTracingEnabled
            config.debug
            config.diagnosticLevel == 'ERROR'
            config.dist == 'build-42'
            config.sendDefaultPii
            config.attachThreads
            config.maxRequestBodySize == 'SMALL'
            config.sampleRate == 0.75d
            config.profilesSampleRate == 0.5d
            config.traceOptionsRequests == false
            config.enableBackpressureHandling == false
            config.forceInit
            config.strictTraceContinuation
            config.orgId == '12345'
            config.contextTags == ['request_id', 'tenant_id']
            config.ignoredErrors == ['Broken pipe', 'Connection reset']
            config.ignoredTransactions == ['/health', '/ready']
            config.ignoredSpanOrigins == ['grails.noisy', 'spring.noisy']
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
