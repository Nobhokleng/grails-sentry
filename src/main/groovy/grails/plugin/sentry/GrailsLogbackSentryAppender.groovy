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

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.IThrowableProxy
import ch.qos.logback.core.status.ErrorStatus
import ch.qos.logback.core.status.Status
import grails.util.Environment
import grails.util.Metadata
import groovy.transform.CompileStatic
import io.sentry.Sentry
import io.sentry.logback.SentryAppender
import org.codehaus.groovy.runtime.StackTraceUtils

@CompileStatic
class GrailsLogbackSentryAppender extends SentryAppender {

    private static final String TAG_GRAILS_APP_NAME = 'grails_app_name'
    private static final String TAG_GRAILS_VERSION = 'grails_version'

    SentryConfig config
    String release

    GrailsLogbackSentryAppender(SentryConfig config, String release = '') {
        super()
        this.config = config
        this.release = release
    }

    @Override
    protected void append(ILoggingEvent event) {
        if (!config.active) {
            return
        }

        if ((config.levels ?: SentryConfig.defaultLevels).contains(event.level)) {
            // Enrich event with Grails-specific context before sending
            Sentry.configureScope { scope ->
                // Add Grails metadata as tags
                Metadata metadata = Metadata.current
                scope.setTag(TAG_GRAILS_APP_NAME, metadata.getApplicationName())
                scope.setTag(TAG_GRAILS_VERSION, metadata.getGrailsVersion())
                
                // Add custom tags
                if (config.tags) {
                    config.tags.each { String key, String value ->
                        scope.setTag(key, value)
                    }
                }
                
                // Set environment
                if (config.environment) {
                    scope.setTag('environment', config.environment)
                } else {
                    scope.setTag('environment', Environment.current.name)
                }
                
                // Add logger context as extras
                for (Map.Entry<String, String> contextEntry : event.loggerContextVO.propertyMap.entrySet()) {
                    scope.setExtra(contextEntry.key, contextEntry.value)
                }
            }
            
            super.append(event)
        }
    }

    @Override
    void addStatus(Status status) {
        if (status instanceof ErrorStatus) {
            // this error is otherwise completely swallowed
            status.throwable?.printStackTrace()
        }
        super.addStatus(status)
    }

    @Override
    protected StackTraceElement[] toStackTraceElements(IThrowableProxy throwableProxy) {
        StackTraceElement[] stackTraceElements = super.toStackTraceElements(throwableProxy)
        if (!config.sanitizeStackTrace) {
            return stackTraceElements
        }

        Exception stackTraceWrapper = new Exception()
        stackTraceWrapper.stackTrace = stackTraceElements
        StackTraceUtils.deepSanitize(stackTraceWrapper).stackTrace
    }

}
