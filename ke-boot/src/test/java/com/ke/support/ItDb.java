package com.ke.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.test.annotation.DirtiesContext;

/**
 * 直连共享库 ke_test 的集成测试标记：
 * - 类开始前 ItDbReset 清空 public schema（Flyway 随后重建）；
 * - 类结束后 @DirtiesContext(AFTER_CLASS) 关闭该类的 Spring 上下文。
 *   二者必须成对：清库使已缓存上下文里的 Flyway 状态失效，若后续类复用该缓存上下文
 *   会找不到表（顺序相关的失败）；AFTER_CLASS 保证每个清库类拿到全新上下文。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(ItDbReset.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public @interface ItDb {
}
