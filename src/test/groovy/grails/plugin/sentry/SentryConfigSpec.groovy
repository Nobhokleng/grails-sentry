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
