package grails.plugin.sentry

import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurerAdapter

class SentryMvcConfigurer extends WebMvcConfigurerAdapter {

    SentryTracingInterceptor tracingInterceptor

    @Override
    void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(tracingInterceptor)
    }
}
