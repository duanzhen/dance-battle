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
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.util.ClassUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

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
 *
 * <p>本类只能通过 Spring 的 {@code RuntimeHints} API 表达需求,以下两项没有对应 API,
 * 以独立配置文件放在 {@code src/main/resources/META-INF/native-image/} 下:</p>
 * <ul>
 *   <li>{@code com.dance.street/game/serialization-config.json}:把使用 MyBatis-Plus
 *       lambda 条件构造器的类登记为 {@code lambdaCapturingTypes}。缺这项时
 *       {@code Wrappers.lambdaQuery()} 解析列名会抛
 *       {@code ClassNotFoundException: XxxServiceImpl$$Lambda/0x...};</li>
 *   <li>{@code agent-hints/reachability-metadata.json}:历史遗留的 agent 采集结果。</li>
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

	/**
	 * 需要「整包注册反射」的包。
	 *
	 * <ul>
	 *   <li>{@code com.dance.street.game.domain}:实体/VO/BO,MyBatis-Plus 的 Reflector
	 *       反射调用其 getter/setter/构造器(含 vo/bo 子包);</li>
	 *   <li>{@code org.dromara.common}:框架通用类,大量出现在 Spring MVC 方法签名里
	 *       (如 {@code PageQuery}、{@code BaseEntity}),由数据绑定/参数解析器反射访问,
	 *       Spring AOT 推断不到——缺注册会在首个带分页参数的查询上报
	 *       {@code MissingReflectionRegistrationError}。</li>
	 * </ul>
	 *
	 * <p>两个包加起来约 300 个类型,整包注册的镜像体积代价远小于逐个试错重建。</p>
	 */
	private static final String[] REFLECTIVE_PACKAGES = {
			"com.dance.street.game.domain",
			"org.dromara.common",
	};

	/**
	 * MyBatis mapper 接口所在包。每个 mapper 在运行期由
	 * {@code MybatisMapperProxyFactory#newInstance} 经 {@code Proxy.newProxyInstance}
	 * 生成 JDK 动态代理,代理类必须在构建期注册,否则 native 启动即抛
	 * {@code MissingReflectionRegistrationError}(JVM 下不会有任何提示)。
	 */
	private static final String MAPPER_PACKAGE = "com.dance.street.game.mapper";

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
		// (插入/更新/结果映射都会触发),Spring MVC 的数据绑定同样反射调用
		// query/Body 对象的 getter/setter。包扫描一次性注册全部业务与框架类型,
		// 并沿父类链注册 BaseEntity/TenantEntity 等基类(Reflector 会通过
		// getMethods() 拿到继承的公共 getter/setter),以后新增实体无需再改 hints。
		for (String packageName : REFLECTIVE_PACKAGES) {
			registerPackage(hints, packageName, classLoader);
		}

		// mapper 动态代理注册:MyBatis 的 mapper bean 实际是接口的 JDK 代理,
		// 只注册接口自身的反射信息不够——代理类本身必须显式登记。
		int mappers = 0;
		for (String className : scanClassNames(MAPPER_PACKAGE, classLoader)) {
			try {
				Class<?> type = Class.forName(className, false, classLoader);
				if (!type.isInterface()) {
					continue;
				}
				hints.proxies().registerJdkProxy(type);
				hints.reflection().registerType(type, MemberCategory.values());
				mappers++;
			} catch (ClassNotFoundException | LinkageError e) {
				log.warn("NativeRuntimeHints: mapper 接口加载失败: {} ({})",
						className, e.toString());
			}
		}
		log.info("NativeRuntimeHints: 已注册 {} 个 mapper 动态代理", mappers);

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

	/**
	 * 扫描包内全部 class 文件并还原为类名。
	 *
	 * <p>{@code ClassPathScanningCandidateComponentProvider} 默认只认「具体类」,
	 * 会漏掉接口(如 mapper)与无注解的普通类,这里直接按资源路径扫描,
	 * 保证 domain 与 mapper 两类目标都能被完整枚举。</p>
	 */
	private static List<String> scanClassNames(String basePackage, ClassLoader classLoader) {
		String packagePath = ClassUtils.convertClassNameToResourcePath(basePackage);
		ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver(classLoader);
		List<String> names = new ArrayList<>();
		try {
			for (Resource resource : resolver.getResources("classpath*:" + packagePath + "/**/*.class")) {
				String uri = resource.getURI().toString();
				int start = uri.indexOf('/' + packagePath + '/');
				if (start < 0) {
					continue;
				}
				String relative = uri.substring(start + 1, uri.length() - ".class".length());
				String className = relative.replace('/', '.');
				// 跳过匿名类(Foo$1):无规范名,且不会被按名反射引用
				if (className.matches(".*\\$\\d+$")) {
					continue;
				}
				names.add(className);
			}
		} catch (IOException e) {
			log.warn("NativeRuntimeHints: 扫描包 {} 失败: {}", basePackage, e.toString());
		}
		return names;
	}

	/**
	 * 整包注册反射。
	 */
	private static void registerPackage(RuntimeHints hints, String packageName, ClassLoader classLoader) {
		int registered = 0;
		for (String className : scanClassNames(packageName, classLoader)) {
			try {
				Class<?> type = Class.forName(className, false, classLoader);
				// 匿名/局部类没有规范名(TypeReference.of 会拒绝),也无法被按名反射引用
				if (type.isAnonymousClass() || type.isLocalClass()) {
					continue;
				}
				for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
					hints.reflection().registerType(c, MemberCategory.values());
				}
				registerArrayHint(hints, className, classLoader);
				registered++;
			} catch (ClassNotFoundException | LinkageError e) {
				log.warn("NativeRuntimeHints: 类加载失败: {} ({})", className, e.toString());
			}
		}
		log.info("NativeRuntimeHints: 已注册 {} 个 {} 类型", registered, packageName);
	}

	/**
	 * 同时注册 {@code T[]} 数组类型。
	 *
	 * <p>mapstruct-plus / Jackson 在「单对象 ↔ 集合」转换时会用
	 * {@code Array.newInstance(组件类型, n)} 反射创建数组,而 native 下数组类型
	 * 也需要显式登记,否则报
	 * {@code Cannot reflectively instantiate the array class 'XxxVo[]'}。</p>
	 */
	private static void registerArrayHint(RuntimeHints hints, String className, ClassLoader classLoader) {
		try {
			Class<?> arrayType = Class.forName("[L" + className + ";", false, classLoader);
			if (arrayType.getCanonicalName() == null) {
				return;
			}
			hints.reflection().registerType(TypeReference.of(arrayType));
		} catch (ClassNotFoundException | LinkageError e) {
			log.warn("NativeRuntimeHints: 数组类型注册失败: {}[] ({})", className, e.toString());
		}
	}

}
