/*
 * Copyright 2016 Alan Rafael Fachini, authors, and contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package grails.plugin.sentry

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.helpers.MDCInsertingServletFilter
import grails.plugins.Plugin
import grails.util.Environment
import grails.util.Metadata
import groovy.transform.CompileStatic
import groovy.transform.TypeCheckingMode
import groovy.util.logging.Slf4j
import io.sentry.EventProcessor
import io.sentry.Hint
import io.sentry.Sentry
import io.sentry.SentryEvent
import io.sentry.SentryOptions
import io.sentry.protocol.SentryException
import io.sentry.protocol.SentryStackFrame
import org.codehaus.groovy.runtime.StackTraceUtils
import org.slf4j.LoggerFactory
import org.springframework.boot.web.servlet.FilterRegistrationBean

@CompileStatic
@Slf4j
class SentryGrailsPlugin extends Plugin {

    static final String TAG_GRAILS_APP_NAME = 'grails_app_name'
    static final String TAG_GRAILS_VERSION = 'grails_version'

    // the version or versions of Grails the plugin is designed for
    def grailsVersion = '3.0.0 > *'

    def title = 'Sentry Plugin'
    def author = 'Benoit Hediard'
    def authorEmail = 'ben@benorama.com'
    def description = 'Sentry Client for Grails'
    def profiles = ['web']
    def documentation = 'http://github.com/agorapulse/grails-sentry/blob/master/README.md'

    def license = 'APACHE'
    def developers = [[name: 'Benoit Hediard', email: 'ben@benorama.com'], [name: 'Alexey Zhokhov', email: 'alexey@zhokhov.com']]
    def issueManagement = [system: 'GitHub', url: 'http://github.com/agorapulse/grails-sentry/issues']
    def scm = [url: 'http://github.com/agorapulse/grails-sentry']

    @CompileStatic(TypeCheckingMode.SKIP)
    Closure doWithSpring() {
        { ->
            SentryConfig pluginConfig = getSentryConfig()

            if (!pluginConfig.active) {
                log.warn "Sentry disabled"
                return
            }

            if (pluginConfig?.dsn) {
                log.info 'Sentry config found, initializing Sentry SDK and corresponding Logback appender'

                // Guard against re-initialization on context reloads (e.g., during tests)
                if (!Sentry.isEnabled()) {
                    Metadata appMetadata = Metadata.current
                    boolean sanitize = pluginConfig.sanitizeStackTrace

                    Sentry.init { SentryOptions options ->
                        options.dsn = pluginConfig.dsn.toString()

                        options.environment = pluginConfig.environment ?: Environment.current.name

                        if (pluginConfig.serverName) {
                            options.serverName = pluginConfig.serverName
                        }

                        String release = grailsApplication.metadata['info.app.version']
                        if (release) {
                            options.release = release
                        }

                        // Static tags set once at init time (thread-safe — no per-event mutation)
                        options.setTag(TAG_GRAILS_APP_NAME, appMetadata.getApplicationName() ?: 'unknown')
                        options.setTag(TAG_GRAILS_VERSION, appMetadata.getGrailsVersion() ?: 'unknown')

                        if (pluginConfig.tags) {
                            pluginConfig.tags.each { String key, String value ->
                                options.setTag(key, value)
                            }
                        }

                        // Stack trace sanitizer registered globally so it runs for every captured event
                        if (sanitize) {
                            options.addEventProcessor({ SentryEvent event, Hint hint ->
                                event.exceptions?.values?.each { SentryException ex ->
                                    List<SentryStackFrame> frames = ex.stacktrace?.frames
                                    if (frames) {
                                        ex.stacktrace.frames = frames.findAll { SentryStackFrame frame ->
                                            frame.module == null || StackTraceUtils.isApplicationClass(frame.module)
                                        }
                                    }
                                }
                                event
                            } as EventProcessor)
                        }
                    }
                }

                if (pluginConfig.logHttpRequest) {
                    log.warn "grails.plugin.sentry.logHttpRequest=true is not supported with sentry-logback alone in SDK v7. " +
                            "Add the sentry-spring dependency for HTTP request context capture."
                }

                if (pluginConfig.logClassName) {
                    log.debug "grails.plugin.sentry.logClassName is effectively always enabled in SDK v7 — " +
                            "the logger name (class name) is captured automatically by SentryAppender."
                }

                sentryAppender(GrailsLogbackSentryAppender, pluginConfig)

                if (pluginConfig.springSecurityUser) {
                    springSecurityUserEventBuilderHelper(SpringSecurityUserEventBuilderHelper, pluginConfig) {
                        springSecurityService = ref('springSecurityService')
                    }
                }

                if (!pluginConfig.disableMDCInsertingServletFilter) {
                    log.info 'Activating MDCInsertingServletFilter'
                    mdcInsertingServletFilter(FilterRegistrationBean) {
                        filter = bean(MDCInsertingServletFilter)
                        urlPatterns = ['/*']
                    }
                }
            } else {
                log.warn "Sentry config not found, add 'grails.plugin.sentry.dsn' to your config to enable Sentry client"
            }
        }
    }

    void doWithApplicationContext() {
        SentryConfig pluginConfig = getSentryConfig()

        if (!pluginConfig.active) {
            return
        }

        // DSN may be absent even when active=true (misconfiguration); appender bean won't exist
        if (!pluginConfig.dsn) {
            return
        }

        if (pluginConfig.springSecurityUser) {
            SpringSecurityUserEventBuilderHelper helper =
                applicationContext.getBean(SpringSecurityUserEventBuilderHelper)
            // configureScope targets the global scope; without sentry-spring request isolation
            // this processor correctly runs for every event across all threads
            Sentry.configureScope { scope ->
                scope.addEventProcessor(helper)
            }
        }

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
    }

    @CompileStatic(TypeCheckingMode.SKIP)
    SentryConfig getSentryConfig() {
        def pluginConfig = grailsApplication.config.grails?.plugin?.sentry

        new SentryConfig(pluginConfig)
    }

}
