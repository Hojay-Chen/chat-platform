package com.luxera.companion;

import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

/**
 * 静态 Spring ApplicationContext 访问器。
 * 供非 Spring 管理的类(如 WebSocket 客户端端点)获取 Bean。
 * 仅在单进程过渡期使用; 双进程后, DH 端通过 HTTPS 调 chat 端 API, 不再需要此工具。
 */
@Component
public class ApplicationContextProvider implements ApplicationContextAware {

    private static ApplicationContext context;

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        context = applicationContext;
    }

    public static <T> T getBean(Class<T> beanClass) {
        if (context == null) return null;
        return context.getBean(beanClass);
    }

    public static ApplicationContext getContext() {
        return context;
    }
}