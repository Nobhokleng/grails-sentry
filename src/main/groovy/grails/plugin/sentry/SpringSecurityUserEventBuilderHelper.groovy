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

import groovy.transform.CompileStatic
import groovy.transform.TypeCheckingMode
import io.sentry.EventProcessor
import io.sentry.SentryEvent
import io.sentry.protocol.User
import io.sentry.Hint

/**
 * @author <a href='mailto:alexey@zhokhov.com'>Alexey Zhokhov</a>
 */
@CompileStatic
class SpringSecurityUserEventBuilderHelper implements EventProcessor {

    SentryConfig config

    SpringSecurityUserEventBuilderHelper(SentryConfig config) {
        this.config = config
    }

    def springSecurityService

    @CompileStatic(TypeCheckingMode.SKIP)
    @Override
    SentryEvent process(SentryEvent event, Hint hint) {
        def isLoggedIn = springSecurityService?.isLoggedIn()

        if (isLoggedIn) {
            def principal = springSecurityService.getPrincipal()

            if (principal != null && principal != 'anonymousUser') {
                String idPropertyName = 'id'
                String emailPropertyName = null
                String usernamePropertyName = 'username'
                List data = null

                if (config?.springSecurityUserProperties) {
                    if (config.springSecurityUserProperties.id) {
                        idPropertyName = config.springSecurityUserProperties.id
                    }
                    if (config.springSecurityUserProperties.email) {
                        emailPropertyName = config.springSecurityUserProperties.email
                    }
                    if (config.springSecurityUserProperties.username) {
                        usernamePropertyName = config.springSecurityUserProperties.username
                    }
                    if (config.springSecurityUserProperties.data) {
                        data = config.springSecurityUserProperties.data
                    }
                }

                def id = principal[idPropertyName].toString()
                String username = principal[usernamePropertyName].toString()
                String email = emailPropertyName ? principal[emailPropertyName].toString() : null
                
                User user = new User()
                user.id = id
                user.username = username
                user.email = email
                
                // Add extra data
                if (data) {
                    Map<String, String> extraData = [:]
                    data.each { Object key ->
                        extraData[key as String] = principal[key as String].toString()
                    }
                    user.data = extraData
                }
                
                event.user = user
            }
        }
        
        return event
    }

}
