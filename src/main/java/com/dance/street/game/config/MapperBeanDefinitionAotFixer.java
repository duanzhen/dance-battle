package com.dance.street.game.config;

import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * GraalVM Native Image 下 MyBatis mapper bean 定义的运行时修复。
 *
 * <p>Spring AOT 会把 MapperScannerConfigurer 扫描出的 MapperFactoryBean 定义固化成
 * {@code BeanInstanceSupplier.forConstructor(Class.class)},构造参数( mapper 接口 Class)
 * 被序列化成类名字符串,native 运行时无法按类型自动装配,导致
 * {@code No qualifying bean of type 'java.lang.Class<?>'}。</p>
 *
 * <p>此处理器在 BeanDefinitionRegistryPostProcessor 之后、单例实例化之前执行:
 * 移除错误的 supplier 与构造参数,恢复 {@code AUTOWIRE_BY_TYPE}(AOT 生成定义丢失了该属性),
 * 让 mapper 走与 JVM 一致的经典实例化路径(无参构造 + setter byType 注入 + mapperInterface 属性)。</p>
 */
@Component
public class MapperBeanDefinitionAotFixer implements BeanFactoryPostProcessor {

	private static final Logger log = LoggerFactory.getLogger(MapperBeanDefinitionAotFixer.class);

	@Override
	public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
		for (String beanName : beanFactory.getBeanDefinitionNames()) {
			if (beanFactory.getBeanDefinition(beanName) instanceof AbstractBeanDefinition beanDefinition
					&& MapperFactoryBean.class.getName().equals(beanDefinition.getBeanClassName())) {
				// AOT 固化定义带 supplier + 构造参数(错误的 Class 自动装配);经典定义两者都没有
				if (beanDefinition.getInstanceSupplier() != null
						&& beanDefinition.getConstructorArgumentValues().getArgumentCount() > 0) {
					log.info("MapperBeanDefinitionAotFixer: 修复 AOT 生成的 mapper bean 定义: {}", beanName);
					beanDefinition.setInstanceSupplier(null);
					beanDefinition.getConstructorArgumentValues().clear();
				}
				beanDefinition.setAutowireMode(AbstractBeanDefinition.AUTOWIRE_BY_TYPE);
				// factoryBeanObjectType 短路 getTypeForFactoryBean 的泛型解析,
				// 避免 native 下 ResolvableType 解析 FactoryBean<T> 泛型时无限递归
				Object mapperInterface = beanDefinition.getPropertyValues().get("mapperInterface");
				if (mapperInterface instanceof Class<?> mapperInterfaceClass) {
					beanDefinition.setAttribute("factoryBeanObjectType", mapperInterfaceClass);
				}
			}
		}
	}

}
