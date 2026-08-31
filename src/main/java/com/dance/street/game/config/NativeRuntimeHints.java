package com.dance.street.game.config;

import com.dance.street.game.engine.common.DimensionConfig;
import com.dance.street.game.engine.common.GroupConfig;
import com.dance.street.game.engine.common.KnockoutConfig;
import com.dance.street.game.engine.common.OutcomeScore;
import com.dance.street.game.engine.common.PromotionTarget;
import com.dance.street.game.engine.common.RuleConfigHolder;
import com.dance.street.game.engine.common.ScoringConfig;
import com.dance.street.game.engine.common.TransitionConfig;
import com.dance.street.game.engine.common.enums.AggregateRuleEnum;
import com.dance.street.game.engine.common.enums.MatchModeEnum;
import com.dance.street.game.engine.common.enums.MatchOutcomeEnum;
import com.dance.street.game.engine.common.enums.OutcomeStatusEnum;
import com.dance.street.game.engine.common.enums.ScoreTypeEnum;
import com.dance.street.game.engine.common.enums.StageModeEnum;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportRuntimeHints;

/**
 * GraalVM Native Image 运行期提示。
 *
 * <p>Spring AOT 只能推断 Spring 容器可见的反射/资源需求,以下场景需要显式注册:</p>
 * <ul>
 *   <li>MyBatis {@code LogFactory} 静态初始化时通过反射探测各日志实现类的
 *       {@code (String)} 构造函数,缺注册会导致 {@code logConstructor} 为 null,启动即 NPE;</li>
 *   <li>规则引擎直接用独立 {@code ObjectMapper} 反序列化 POJO(AOT 无法感知这些类型);</li>
 *   <li>{@code SqliteFallbackEnvironmentPostProcessor} 中 {@code Class.forName} MySQL 驱动;</li>
 *   <li>运行时才从 classpath 读取的 mapper XML 与建表 SQL 资源。</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@ImportRuntimeHints(NativeRuntimeHints.class)
public class NativeRuntimeHints implements RuntimeHintsRegistrar {

	private static final Logger log = LoggerFactory.getLogger(NativeRuntimeHints.class);

	private static final String[] MYBATIS_LOG_IMPLS = {
			"org.apache.ibatis.logging.slf4j.Slf4jImpl",
			"org.apache.ibatis.logging.commons.JakartaCommonsLoggingImpl",
			"org.apache.ibatis.logging.log4j2.Log4j2Impl",
			"org.apache.ibatis.logging.log4j.Log4jImpl",
			"org.apache.ibatis.logging.jdk14.Jdk14LoggingImpl",
			"org.apache.ibatis.logging.nologging.NoLoggingImpl",
			"org.apache.ibatis.logging.stdout.StdOutImpl"
	};

	private static final String[] MYBATIS_REFLECTION_TYPES = {
			// MapperFactoryBean:无参构造 + mapperInterface 属性 + byType setter 注入,
			// native 经典实例化路径需要构造器/方法/内省全部注册
			"org.mybatis.spring.mapper.MapperFactoryBean",
			"org.mybatis.spring.support.SqlSessionDaoSupport",
			// MyBatis Configuration 构造器用 Class.forName 探测 Javassist(懒加载代理),
			// 缺注册会抛 "Cannot enable lazy loading because Javassist is not available"
			"org.apache.ibatis.javassist.util.proxy.ProxyFactory",
			"org.apache.ibatis.javassist.util.proxy.Proxy"
	};

	/**
	 * MyBatis 启动时通过反射实例化的类(Configuration 构造器):
	 * 语言驱动、内置 TypeHandler、MyBatis-Plus 枚举处理器。
	 * 缺注册会抛 NoSuchMethodException / TypeException。
	 */
	private static final String[] MYBATIS_REFLECTIVE_CONSTRUCTORS = {
			"org.apache.ibatis.scripting.xmltags.XMLLanguageDriver",
			"org.apache.ibatis.scripting.defaults.RawLanguageDriver",
			// MyBatis-Plus 默认语言驱动:MybatisConfiguration 构造器用反射实例化
			// (native 运行时报 NoSuchMethodException: MybatisXMLLanguageDriver.<init>())
			"com.baomidou.mybatisplus.core.MybatisXMLLanguageDriver",
			"org.apache.ibatis.type.ArrayTypeHandler",
			"org.apache.ibatis.type.BigDecimalTypeHandler",
			"org.apache.ibatis.type.BigIntegerTypeHandler",
			"org.apache.ibatis.type.BlobByteObjectArrayTypeHandler",
			"org.apache.ibatis.type.BlobInputStreamTypeHandler",
			"org.apache.ibatis.type.BlobTypeHandler",
			"org.apache.ibatis.type.BooleanTypeHandler",
			"org.apache.ibatis.type.ByteArrayTypeHandler",
			"org.apache.ibatis.type.ByteObjectArrayTypeHandler",
			"org.apache.ibatis.type.ByteTypeHandler",
			"org.apache.ibatis.type.CharacterTypeHandler",
			"org.apache.ibatis.type.ClobReaderTypeHandler",
			"org.apache.ibatis.type.ClobTypeHandler",
			"org.apache.ibatis.type.DateOnlyTypeHandler",
			"org.apache.ibatis.type.DateTypeHandler",
			"org.apache.ibatis.type.DoubleTypeHandler",
			"org.apache.ibatis.type.EnumOrdinalTypeHandler",
			"org.apache.ibatis.type.EnumTypeHandler",
			"org.apache.ibatis.type.FloatTypeHandler",
			"org.apache.ibatis.type.InstantTypeHandler",
			"org.apache.ibatis.type.IntegerTypeHandler",
			"org.apache.ibatis.type.JapaneseDateTypeHandler",
			"org.apache.ibatis.type.LocalDateTimeTypeHandler",
			"org.apache.ibatis.type.LocalDateTypeHandler",
			"org.apache.ibatis.type.LocalTimeTypeHandler",
			"org.apache.ibatis.type.LongTypeHandler",
			"org.apache.ibatis.type.MonthTypeHandler",
			"org.apache.ibatis.type.NClobTypeHandler",
			"org.apache.ibatis.type.NStringTypeHandler",
			"org.apache.ibatis.type.ObjectTypeHandler",
			"org.apache.ibatis.type.OffsetDateTimeTypeHandler",
			"org.apache.ibatis.type.OffsetTimeTypeHandler",
			"org.apache.ibatis.type.ShortTypeHandler",
			"org.apache.ibatis.type.SqlDateTypeHandler",
			"org.apache.ibatis.type.SqlTimeTypeHandler",
			"org.apache.ibatis.type.SqlTimestampTypeHandler",
			"org.apache.ibatis.type.SqlxmlTypeHandler",
			"org.apache.ibatis.type.StringTypeHandler",
			"org.apache.ibatis.type.TimeOnlyTypeHandler",
			"org.apache.ibatis.type.UnknownTypeHandler",
			"org.apache.ibatis.type.YearMonthTypeHandler",
			"org.apache.ibatis.type.YearTypeHandler",
			"org.apache.ibatis.type.ZonedDateTimeTypeHandler",
			"com.baomidou.mybatisplus.core.handlers.MybatisEnumTypeHandler",
			"com.baomidou.mybatisplus.core.handlers.CompositeEnumTypeHandler"
	};

	private static final Class<?>[] RULE_CONFIG_TYPES = {
			RuleConfigHolder.class,
			PromotionTarget.class,
			KnockoutConfig.class,
			GroupConfig.class,
			ScoringConfig.class,
			TransitionConfig.class,
			DimensionConfig.class,
			OutcomeScore.class,
			StageModeEnum.class,
			MatchModeEnum.class,
			MatchOutcomeEnum.class,
			OutcomeStatusEnum.class,
			ScoreTypeEnum.class,
			AggregateRuleEnum.class
	};

	@Override
	public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
		for (String impl : MYBATIS_LOG_IMPLS) {
			hints.reflection().registerType(TypeReference.of(impl),
					MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS);
		}

		for (String type : MYBATIS_REFLECTION_TYPES) {
			hints.reflection().registerType(TypeReference.of(type), MemberCategory.values());
		}

		for (String type : MYBATIS_REFLECTIVE_CONSTRUCTORS) {
			hints.reflection().registerType(TypeReference.of(type),
					MemberCategory.INVOKE_DECLARED_CONSTRUCTORS);
		}

		for (Class<?> type : RULE_CONFIG_TYPES) {
			hints.reflection().registerType(type, MemberCategory.values());
		}

		// MyBatis-Plus 的 CRUD 通过 Reflector 反射调用实体 getter/setter/构造器
		// (插入/更新/结果映射都会触发),缺注册会在首个写操作报
		// MissingReflectionRegistrationError。包扫描一次性注册 domain 全部实体
		// (含 vo/bo 子包),并沿父类链注册 TenantEntity 等基类(Reflector 会
		// 通过 getMethods() 拿到继承的公共 getter/setter),以后新增实体无需再改 hints。
		ClassPathScanningCandidateComponentProvider scanner =
				new ClassPathScanningCandidateComponentProvider(false);
		int registered = 0;
		for (BeanDefinition definition : scanner.findCandidateComponents("com.dance.street.game.domain")) {
			try {
				Class<?> type = Class.forName(definition.getBeanClassName(), false, classLoader);
				for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
					hints.reflection().registerType(c, MemberCategory.values());
				}
				registered++;
			} catch (ClassNotFoundException | LinkageError e) {
				log.warn("NativeRuntimeHints: domain 类加载失败: {} ({})",
						definition.getBeanClassName(), e.toString());
			}
		}
		log.info("NativeRuntimeHints: 已注册 {} 个 domain 类型(含继承链)", registered);

		// SqliteFallbackEnvironmentPostProcessor: Class.forName(com.mysql.cj.jdbc.Driver)
		hints.reflection().registerType(TypeReference.of("com.mysql.cj.jdbc.Driver"),
				MemberCategory.values());

		// MyBatis mapper XML 与 JDBC 兜底建表 SQL 均为运行时 classpath 资源
		hints.resources().registerPattern("mapper/**/*.xml");
		hints.resources().registerPattern("sql/*.sql");

		// MyBatis-Plus Wrapper 条件表达式经 OGNL 反序列化 SerializedLambda,
		// 缺注册会抛 UnsupportedFeatureError(SerializationConstructorAccessor not found)
		hints.serialization().registerType(TypeReference.of("java.lang.invoke.SerializedLambda"));
	}

}
