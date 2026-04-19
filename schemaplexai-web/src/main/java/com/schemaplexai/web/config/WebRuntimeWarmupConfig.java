package com.schemaplexai.web.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.catalina.core.ApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.DispatcherServlet;

/**
 * Web 运行时预热配置
 *
 * <p>线上 fat jar 环境中，Spring MVC / Tomcat 某些延迟解析类在首次异常分支触发时
 * 可能被错误的类加载器解析失败。启动时显式预热关键类，避免请求阶段再触发延迟加载。
 */
@Slf4j
@Configuration
public class WebRuntimeWarmupConfig {

    @PostConstruct
    public void warmup() {
        preloadClass(
                DispatcherServlet.class.getClassLoader(),
                "org.springframework.web.servlet.ModelAndViewDefiningException"
        );
        preloadClass(
                ApplicationContext.class.getClassLoader(),
                "org.apache.catalina.core.ApplicationContext$DispatchData"
        );
    }

    private void preloadClass(ClassLoader classLoader, String className) {
        try {
            Class.forName(className, true, classLoader);
            log.info("Web 运行时预热成功: class={}, classLoader={}", className, classLoader);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Web 运行时预热失败: " + className, e);
        }
    }
}
