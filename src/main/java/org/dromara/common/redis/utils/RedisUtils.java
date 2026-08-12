package org.dromara.common.redis.utils;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.utils.SpringUtils;
import org.redisson.api.*;
import org.redisson.api.options.KeysScanOptions;

import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * redis 工具类
 *
 * @author Lion Li
 * @version 3.1.0 新增
 */
@Slf4j
@NoArgsConstructor(access = AccessLevel.PRIVATE)
@SuppressWarnings(value = {"unchecked", "rawtypes"})
public class RedisUtils {

    /** 本地模式缓存:未配置/不可达 Redis 时兜底,保证单机可运行 */
    private static final Map<String, Object> LOCAL_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Set<Object>> LOCAL_SETS = new ConcurrentHashMap<>();
    private static final Map<String, Long> LOCAL_ATOMIC = new ConcurrentHashMap<>();
    private static final Map<String, Long> LOCAL_ATOMIC_EXPIRE_AT = new ConcurrentHashMap<>();
    private static final Map<String, List<Consumer<?>>> LOCAL_SUBSCRIBERS = new ConcurrentHashMap<>();

    private static volatile boolean unavailableWarned;

    /**
     * 获取 RedissonClient;不存在(Redis 未配置/不可达/被禁用)时返回 null,
     * 调用方按本地模式降级。每次实时解析,避免缓存跨 Spring 上下文失效。
     */
    private static RedissonClient client() {
        try {
            return SpringUtils.getBean(RedissonClient.class);
        } catch (Exception e) {
            if (!unavailableWarned) {
                log.warn("RedissonClient 不可用,Redis 操作降级为本地模式(单机运行): {}", e.getMessage());
                unavailableWarned = true;
            }
            return null;
        }
    }

    /** Redis 是否可用:存在 RedissonClient 时按 Redis 走,否则走本地兜底 */
    public static boolean isAvailable() {
        return client() != null;
    }

    /**
     * 限流
     *
     * @param key          限流key
     * @param rateType     限流类型
     * @param rate         速率
     * @param rateInterval 速率间隔
     * @return -1 表示失败
     */
    public static long rateLimiter(String key, RateType rateType, int rate, int rateInterval) {
        return rateLimiter(key, rateType, rate, rateInterval, 0);
    }

    /**
     * 限流
     *
     * @param key          限流key
     * @param rateType     限流类型
     * @param rate         速率
     * @param rateInterval 速率间隔
     * @param timeout      超时时间
     * @return -1 表示失败
     */
    public static long rateLimiter(String key, RateType rateType, int rate, int rateInterval, int timeout) {
        RedissonClient c = client();
        if (c == null) {
            return -1L;
        }
        RRateLimiter rateLimiter = c.getRateLimiter(key);
        rateLimiter.trySetRate(rateType, rate, Duration.ofSeconds(rateInterval), Duration.ofSeconds(timeout));
        if (rateLimiter.tryAcquire()) {
            return rateLimiter.availablePermits();
        } else {
            return -1L;
        }
    }

    /**
     * 获取客户端实例
     */
    public static RedissonClient getClient() {
        return client();
    }

    /**
     * 发布通道消息
     *
     * @param channelKey 通道key
     * @param msg        发送数据
     * @param consumer   自定义处理
     */
    public static <T> void publish(String channelKey, T msg, Consumer<T> consumer) {
        RedissonClient c = client();
        if (c != null) {
            RTopic topic = c.getTopic(channelKey);
            topic.publish(msg);
        } else {
            dispatchLocally(channelKey, msg);
        }
        if (consumer != null) {
            consumer.accept(msg);
        }
    }

    /**
     * 发布消息到指定的频道
     *
     * @param channelKey 通道key
     * @param msg        发送数据
     */
    public static <T> void publish(String channelKey, T msg) {
        RedissonClient c = client();
        if (c != null) {
            RTopic topic = c.getTopic(channelKey);
            topic.publish(msg);
        } else {
            dispatchLocally(channelKey, msg);
        }
    }

    /**
     * 订阅通道接收消息
     *
     * @param channelKey 通道key
     * @param clazz      消息类型
     * @param consumer   自定义处理
     */
    public static <T> void subscribe(String channelKey, Class<T> clazz, Consumer<T> consumer) {
        LOCAL_SUBSCRIBERS.computeIfAbsent(channelKey, k -> new CopyOnWriteArrayList<>()).add(consumer);
        RedissonClient c = client();
        if (c != null) {
            RTopic topic = c.getTopic(channelKey);
            topic.addListener(clazz, (channel, msg) -> consumer.accept(msg));
        }
    }

    /** 本地模式下的主题分发:直接回调本进程已订阅的消费者 */
    private static <T> void dispatchLocally(String channelKey, T msg) {
        List<Consumer<?>> subscribers = LOCAL_SUBSCRIBERS.get(channelKey);
        if (subscribers == null) {
            return;
        }
        for (Consumer<?> subscriber : subscribers) {
            try {
                ((Consumer<T>) subscriber).accept(msg);
            } catch (Exception e) {
                log.warn("本地主题 {} 分发消息失败: {}", channelKey, e.getMessage());
            }
        }
    }

    /**
     * 缓存基本的对象，Integer、String、实体类等
     *
     * @param key   缓存的键值
     * @param value 缓存的值
     */
    public static <T> void setCacheObject(final String key, final T value) {
        setCacheObject(key, value, false);
    }

    /**
     * 缓存基本的对象，保留当前对象 TTL 有效期
     *
     * @param key       缓存的键值
     * @param value     缓存的值
     * @param isSaveTtl 是否保留TTL有效期(例如: set之前ttl剩余90 set之后还是为90)
     * @since Redis 6.X 以上使用 setAndKeepTTL 兼容 5.X 方案
     */
    public static <T> void setCacheObject(final String key, final T value, final boolean isSaveTtl) {
        RedissonClient c = client();
        if (c == null) {
            LOCAL_CACHE.put(key, value);
            return;
        }
        RBucket<T> bucket = c.getBucket(key);
        if (isSaveTtl) {
            try {
                bucket.setAndKeepTTL(value);
            } catch (Exception e) {
                long timeToLive = bucket.remainTimeToLive();
                if (timeToLive == -1) {
                    bucket.set(value);
                } else {
                    bucket.set(value, Duration.ofMillis(timeToLive));
                }
            }
        } else {
            bucket.set(value);
        }
    }

    /**
     * 缓存基本的对象，Integer、String、实体类等
     *
     * @param key      缓存的键值
     * @param value    缓存的值
     * @param duration 时间
     */
    public static <T> void setCacheObject(final String key, final T value, final Duration duration) {
        RedissonClient c = client();
        if (c == null) {
            LOCAL_CACHE.put(key, value);
            return;
        }
        RBucket<T> bucket = c.getBucket(key);
        bucket.set(value, duration);
    }

    /**
     * 如果不存在则设置 并返回 true 如果存在则返回 false
     *
     * @param key   缓存的键值
     * @param value 缓存的值
     * @return set成功或失败
     */
    public static <T> boolean setObjectIfAbsent(final String key, final T value, final Duration duration) {
        RedissonClient c = client();
        if (c == null) {
            return false;
        }
        RBucket<T> bucket = c.getBucket(key);
        return bucket.setIfAbsent(value, duration);
    }

    /**
     * 如果存在则设置 并返回 true 如果存在则返回 false
     *
     * @param key   缓存的键值
     * @param value 缓存的值
     * @return set成功或失败
     */
    public static <T> boolean setObjectIfExists(final String key, final T value, final Duration duration) {
        RedissonClient c = client();
        if (c == null) {
            return false;
        }
        RBucket<T> bucket = c.getBucket(key);
        return bucket.setIfExists(value, duration);
    }

    /**
     * 注册对象监听器
     * <p>
     * key 监听器需开启 `notify-keyspace-events` 等 redis 相关配置
     *
     * @param key      缓存的键值
     * @param listener 监听器配置
     */
    public static <T> void addObjectListener(final String key, final ObjectListener listener) {
        RedissonClient c = client();
        if (c == null) {
            return;
        }
        RBucket<T> result = c.getBucket(key);
        result.addListener(listener);
    }

    /**
     * 设置有效时间
     *
     * @param key     Redis键
     * @param timeout 超时时间
     * @return true=设置成功；false=设置失败
     */
    public static boolean expire(final String key, final long timeout) {
        return expire(key, Duration.ofSeconds(timeout));
    }

    /**
     * 设置有效时间
     *
     * @param key      Redis键
     * @param duration 超时时间
     * @return true=设置成功；false=设置失败
     */
    public static boolean expire(final String key, final Duration duration) {
        RedissonClient c = client();
        if (c == null) {
            LOCAL_ATOMIC_EXPIRE_AT.put(key, System.currentTimeMillis() + duration.toMillis());
            return true;
        }
        RBucket rBucket = c.getBucket(key);
        return rBucket.expire(duration);
    }

    /**
     * 获得缓存的基本对象。
     *
     * @param key 缓存键值
     * @return 缓存键值对应的数据
     */
    public static <T> T getCacheObject(final String key) {
        RedissonClient c = client();
        if (c == null) {
            return (T) LOCAL_CACHE.get(key);
        }
        RBucket<T> rBucket = c.getBucket(key);
        return rBucket.get();
    }

    /**
     * 获得key剩余存活时间
     *
     * @param key 缓存键值
     * @return 剩余存活时间
     */
    public static <T> long getTimeToLive(final String key) {
        RedissonClient c = client();
        if (c == null) {
            purgeIfExpired(key);
            Long deadline = LOCAL_ATOMIC_EXPIRE_AT.get(key);
            return deadline == null ? 0 : Math.max(0, deadline - System.currentTimeMillis());
        }
        RBucket<T> rBucket = c.getBucket(key);
        return rBucket.remainTimeToLive();
    }

    /**
     * 删除单个对象
     *
     * @param key 缓存的键值
     */
    public static boolean deleteObject(final String key) {
        LOCAL_CACHE.remove(key);
        LOCAL_SETS.remove(key);
        LOCAL_ATOMIC.remove(key);
        LOCAL_ATOMIC_EXPIRE_AT.remove(key);
        RedissonClient c = client();
        return c != null && c.getBucket(key).delete();
    }

    /**
     * 删除集合对象
     *
     * @param collection 多个对象
     */
    public static void deleteObject(final Collection collection) {
        RedissonClient c = client();
        if (c == null) {
            return;
        }
        RBatch batch = c.createBatch();
        collection.forEach(t -> {
            batch.getBucket(t.toString()).deleteAsync();
        });
        batch.execute();
    }

    /**
     * 检查缓存对象是否存在
     *
     * @param key 缓存的键值
     */
    public static boolean isExistsObject(final String key) {
        RedissonClient c = client();
        return c != null && c.getBucket(key).isExists();
    }

    /**
     * 缓存List数据
     *
     * @param key      缓存的键值
     * @param dataList 待缓存的List数据
     * @return 缓存的对象
     */
    public static <T> boolean setCacheList(final String key, final List<T> dataList) {
        RedissonClient c = client();
        if (c == null) {
            return false;
        }
        RList<T> rList = c.getList(key);
        return rList.addAll(dataList);
    }

    /**
     * 追加缓存List数据
     *
     * @param key  缓存的键值
     * @param data 待缓存的数据
     * @return 缓存的对象
     */
    public static <T> boolean addCacheList(final String key, final T data) {
        RedissonClient c = client();
        if (c == null) {
            return false;
        }
        RList<T> rList = c.getList(key);
        return rList.add(data);
    }

    /**
     * 注册List监听器
     * <p>
     * key 监听器需开启 `notify-keyspace-events` 等 redis 相关配置
     *
     * @param key      缓存的键值
     * @param listener 监听器配置
     */
    public static <T> void addListListener(final String key, final ObjectListener listener) {
        RedissonClient c = client();
        if (c == null) {
            return;
        }
        RList<T> rList = c.getList(key);
        rList.addListener(listener);
    }

    /**
     * 获得缓存的list对象
     *
     * @param key 缓存的键值
     * @return 缓存键值对应的数据
     */
    public static <T> List<T> getCacheList(final String key) {
        RedissonClient c = client();
        if (c == null) {
            return List.of();
        }
        RList<T> rList = c.getList(key);
        return rList.readAll();
    }

    /**
     * 获得缓存的list对象(范围)
     *
     * @param key  缓存的键值
     * @param form 起始下标
     * @param to   截止下标
     * @return 缓存键值对应的数据
     */
    public static <T> List<T> getCacheListRange(final String key, int form, int to) {
        RedissonClient c = client();
        if (c == null) {
            return List.of();
        }
        RList<T> rList = c.getList(key);
        return rList.range(form, to);
    }

    /**
     * 缓存Set
     *
     * @param key     缓存键值
     * @param dataSet 缓存的数据
     * @return 缓存数据的对象
     */
    public static <T> boolean setCacheSet(final String key, final Set<T> dataSet) {
        RedissonClient c = client();
        if (c == null) {
            LOCAL_SETS.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet()).addAll(dataSet);
            return true;
        }
        RSet<T> rSet = c.getSet(key);
        return rSet.addAll(dataSet);
    }

    /**
     * 追加缓存Set数据
     *
     * @param key  缓存的键值
     * @param data 待缓存的数据
     * @return 缓存的对象
     */
    public static <T> boolean addCacheSet(final String key, final T data) {
        RedissonClient c = client();
        if (c == null) {
            return false;
        }
        RSet<T> rSet = c.getSet(key);
        return rSet.add(data);
    }

    /**
     * 注册Set监听器
     * <p>
     * key 监听器需开启 `notify-keyspace-events` 等 redis 相关配置
     *
     * @param key      缓存的键值
     * @param listener 监听器配置
     */
    public static <T> void addSetListener(final String key, final ObjectListener listener) {
        RedissonClient c = client();
        if (c == null) {
            return;
        }
        RSet<T> rSet = c.getSet(key);
        rSet.addListener(listener);
    }

    /**
     * 获得缓存的set
     *
     * @param key 缓存的key
     * @return set对象
     */
    public static <T> Set<T> getCacheSet(final String key) {
        RedissonClient c = client();
        if (c == null) {
            Set<Object> set = LOCAL_SETS.get(key);
            return set == null ? Set.of() : (Set<T>) set;
        }
        RSet<T> rSet = c.getSet(key);
        return rSet.readAll();
    }

    /**
     * 缓存Map
     *
     * @param key     缓存的键值
     * @param dataMap 缓存的数据
     */
    public static <T> void setCacheMap(final String key, final Map<String, T> dataMap) {
        RedissonClient c = client();
        if (c != null && dataMap != null) {
            RMap<String, T> rMap = c.getMap(key);
            rMap.putAll(dataMap);
        }
    }

    /**
     * 注册Map监听器
     * <p>
     * key 监听器需开启 `notify-keyspace-events` 等 redis 相关配置
     *
     * @param key      缓存的键值
     * @param listener 监听器配置
     */
    public static <T> void addMapListener(final String key, final ObjectListener listener) {
        RedissonClient c = client();
        if (c == null) {
            return;
        }
        RMap<String, T> rMap = c.getMap(key);
        rMap.addListener(listener);
    }

    /**
     * 获得缓存的Map
     *
     * @param key 缓存的键值
     * @return map对象
     */
    public static <T> Map<String, T> getCacheMap(final String key) {
        RedissonClient c = client();
        if (c == null) {
            return Map.of();
        }
        RMap<String, T> rMap = c.getMap(key);
        return rMap.getAll(rMap.keySet());
    }

    /**
     * 获得缓存Map的key列表
     *
     * @param key 缓存的键值
     * @return key列表
     */
    public static <T> Set<String> getCacheMapKeySet(final String key) {
        RedissonClient c = client();
        if (c == null) {
            return Set.of();
        }
        RMap<String, T> rMap = c.getMap(key);
        return rMap.keySet();
    }

    /**
     * 往Hash中存入数据
     *
     * @param key   Redis键
     * @param hKey  Hash键
     * @param value 值
     */
    public static <T> void setCacheMapValue(final String key, final String hKey, final T value) {
        RedissonClient c = client();
        if (c == null) {
            return;
        }
        RMap<String, T> rMap = c.getMap(key);
        rMap.put(hKey, value);
    }

    /**
     * 获取Hash中的数据
     *
     * @param key  Redis键
     * @param hKey Hash键
     * @return Hash中的对象
     */
    public static <T> T getCacheMapValue(final String key, final String hKey) {
        RedissonClient c = client();
        if (c == null) {
            return null;
        }
        RMap<String, T> rMap = c.getMap(key);
        return rMap.get(hKey);
    }

    /**
     * 删除Hash中的数据
     *
     * @param key  Redis键
     * @param hKey Hash键
     * @return Hash中的对象
     */
    public static <T> T delCacheMapValue(final String key, final String hKey) {
        RedissonClient c = client();
        if (c == null) {
            return null;
        }
        RMap<String, T> rMap = c.getMap(key);
        return rMap.remove(hKey);
    }

    /**
     * 删除Hash中的数据
     *
     * @param key   Redis键
     * @param hKeys Hash键
     */
    public static <T> void delMultiCacheMapValue(final String key, final Set<String> hKeys) {
        RedissonClient c = client();
        if (c == null) {
            return;
        }
        RBatch batch = c.createBatch();
        RMapAsync<String, T> rMap = batch.getMap(key);
        for (String hKey : hKeys) {
            rMap.removeAsync(hKey);
        }
        batch.execute();
    }

    /**
     * 获取多个Hash中的数据
     *
     * @param key   Redis键
     * @param hKeys Hash键集合
     * @return Hash对象集合
     */
    public static <K, V> Map<K, V> getMultiCacheMapValue(final String key, final Set<K> hKeys) {
        RedissonClient c = client();
        if (c == null) {
            return Map.of();
        }
        RMap<K, V> rMap = c.getMap(key);
        return rMap.getAll(hKeys);
    }

    /**
     * 设置原子值
     *
     * @param key   Redis键
     * @param value 值
     */
    public static void setAtomicValue(String key, long value) {
        RedissonClient c = client();
        if (c == null) {
            LOCAL_ATOMIC.put(key, value);
            return;
        }
        RAtomicLong atomic = c.getAtomicLong(key);
        atomic.set(value);
    }

    /**
     * 获取原子值
     *
     * @param key Redis键
     * @return 当前值
     */
    public static long getAtomicValue(String key) {
        RedissonClient c = client();
        if (c == null) {
            purgeIfExpired(key);
            return LOCAL_ATOMIC.getOrDefault(key, 0L);
        }
        RAtomicLong atomic = c.getAtomicLong(key);
        return atomic.get();
    }

    /**
     * 递增原子值
     *
     * @param key Redis键
     * @return 当前值
     */
    public static long incrAtomicValue(String key) {
        RedissonClient c = client();
        if (c == null) {
            purgeIfExpired(key);
            return LOCAL_ATOMIC.merge(key, 1L, Long::sum);
        }
        RAtomicLong atomic = c.getAtomicLong(key);
        return atomic.incrementAndGet();
    }

    /**
     * 递减原子值
     *
     * @param key Redis键
     * @return 当前值
     */
    public static long decrAtomicValue(String key) {
        RedissonClient c = client();
        if (c == null) {
            purgeIfExpired(key);
            return LOCAL_ATOMIC.merge(key, -1L, Long::sum);
        }
        RAtomicLong atomic = c.getAtomicLong(key);
        return atomic.decrementAndGet();
    }

    /**
     * 获得缓存的基本对象列表(全局匹配忽略租户 自行拼接租户id)
     * <P>
     * limit-设置扫描的限制数量(默认为0,查询全部)
     * pattern-设置键的匹配模式(默认为null)
     * chunkSize-设置每次扫描的块大小(默认为0,本方法设置为1000)
     * type-设置键的类型(默认为null,查询全部类型)
     * </P>
     * @see KeysScanOptions
     * @param pattern 字符串前缀
     * @return 对象列表
     */
    public static Collection<String> keys(final String pattern) {
        RedissonClient c = client();
        if (c == null) {
            return localKeys(pattern);
        }
        return keys(KeysScanOptions.defaults().pattern(pattern).chunkSize(1000));
    }

    /**
     * 通过扫描参数获取缓存的基本对象列表
     * @param keysScanOptions 扫描参数
     * <P>
     * limit-设置扫描的限制数量(默认为0,查询全部)
     * pattern-设置键的匹配模式(默认为null)
     * chunkSize-设置每次扫描的块大小(默认为0)
     * type-设置键的类型(默认为null,查询全部类型)
     * </P>
     * @see KeysScanOptions
     */
    public static Collection<String> keys(final KeysScanOptions keysScanOptions) {
        RedissonClient c = client();
        if (c == null) {
            return localKeys("*");
        }
        Stream<String> keysStream = c.getKeys().getKeysStream(keysScanOptions);
        return keysStream.collect(Collectors.toList());
    }

    /**
     * 删除缓存的基本对象列表(全局匹配忽略租户 自行拼接租户id)
     *
     * @param pattern 字符串前缀
     */
    public static void deleteKeys(final String pattern) {
        RedissonClient c = client();
        if (c == null) {
            Pattern p = globToRegex(pattern);
            LOCAL_CACHE.keySet().removeIf(k -> p.matcher(k).matches());
            LOCAL_SETS.keySet().removeIf(k -> p.matcher(k).matches());
            LOCAL_ATOMIC.keySet().removeIf(k -> p.matcher(k).matches());
            LOCAL_ATOMIC_EXPIRE_AT.keySet().removeIf(k -> p.matcher(k).matches());
            return;
        }
        c.getKeys().deleteByPattern(pattern);
    }

    /** 本地模式 key 扫描:匹配 Redis glob 模式(仅 * 与 ? 通配) */
    private static Collection<String> localKeys(String pattern) {
        Pattern p = globToRegex(pattern);
        Set<String> keys = new LinkedHashSet<>();
        LOCAL_CACHE.keySet().stream().filter(k -> p.matcher(k).matches()).forEach(keys::add);
        LOCAL_SETS.keySet().stream().filter(k -> p.matcher(k).matches()).forEach(keys::add);
        LOCAL_ATOMIC.keySet().stream().filter(k -> p.matcher(k).matches()).forEach(keys::add);
        return keys;
    }

    /** 本地模式原子计数过期清理:窗口已结束则归零 */
    private static void purgeIfExpired(String key) {
        Long deadline = LOCAL_ATOMIC_EXPIRE_AT.get(key);
        if (deadline != null && deadline <= System.currentTimeMillis()) {
            LOCAL_ATOMIC.remove(key);
            LOCAL_ATOMIC_EXPIRE_AT.remove(key);
        }
    }

    /** Redis glob 模式转正则(支持 * 与 ?) */
    private static Pattern globToRegex(String glob) {
        StringBuilder sb = new StringBuilder();
        for (char ch : glob.toCharArray()) {
            switch (ch) {
                case '*' -> sb.append(".*");
                case '?' -> sb.append('.');
                default -> {
                    if ("\\.[]{}()+-^$|".indexOf(ch) >= 0) {
                        sb.append('\\');
                    }
                    sb.append(ch);
                }
            }
        }
        return Pattern.compile(sb.toString());
    }

    /**
     * 检查redis中是否存在key
     *
     * @param key 键
     */
    public static Boolean hasKey(String key) {
        RedissonClient c = client();
        if (c == null) {
            return false;
        }
        RKeys rKeys = c.getKeys();
        return rKeys.countExists(key) > 0;
    }
}
