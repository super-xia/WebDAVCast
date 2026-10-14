package com.webdavcast.app

import android.content.Context
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import java.io.File

/**
 * 自动预存 + 本地磁盘缓存 + 自动清理。全自动, 无按钮。
 *
 * 1) 起播门槛保持 ExoPlayer 默认 2.5 秒(点进去不额外等待)
 * 2) 播放中自动预存 120 秒: 由 ExoPlayer 自身完成, 网络抖动时靠这 120 秒垫着不卡;
 *    暂停时也会继续攒到 120 秒("看一会等一会"就是攒这个)
 * 3) 所有读过的数据自动落盘(app 私有 cacheDir): 卡顿切集/重进/重看不重下
 * 4) 自动清理: 超过 1GB 立即按最旧先清(LRU), 另外超过 7 天的启动时清掉
 */
object VideoCache {
    /** 预存目标时长(毫秒): 播放中始终保持这么多秒的缓冲 */
    const val PRELOAD_MS = 120_000L
    /** 起播门槛: 沿用 ExoPlayer 默认 2.5 秒, 点进去不等待 */
    private const val START_BUFFER_MS = 2_500
    /** 磁盘缓存上限 1GB */
    const val CACHE_MB = 1024L
    /** 超过 7 天的缓存自动清掉 */
    private const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

    /** 全局唯一(SimpleCache 要求同一目录只能有一个实例, 故用 object 单例) */
    @Volatile
    private var cache: SimpleCache? = null

    /** 取缓存实例(懒加载)。初始化失败返回 null, 调用方自动降级为纯网络播放。 */
    fun get(ctx: Context): SimpleCache? {
        cache?.let { return it }
        return synchronized(this) {
            cache ?: try {
                SimpleCache(
                    File(ctx.cacheDir, "video_cache"),
                    LeastRecentlyUsedCacheEvictor(CACHE_MB * 1024 * 1024), // 超 1GB 立即最旧先清
                    StandaloneDatabaseProvider(ctx)
                ).also {
                    cache = it
                    Thread { runCatching { trim(it) } }.start() // 启动即清一次过期数据
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    /** 已就绪的缓存实例(不触发初始化, 不拿锁)。未就绪返回 null。 */
    fun ready(): SimpleCache? = cache

    /** 缓存数据源工厂: 命中读盘, 未命中走网络并写入缓存(read-through)。
     *  【只能从后台线程调用】: 首次会同步初始化 SimpleCache(建 SQLite 索引)。
     *  主线程绝不要直接调, 用 [LazyCacheFactory] 让它延迟到 ExoPlayer 加载线程再初始化。 */
    fun cacheFactory(ctx: Context, upstream: DataSource.Factory): CacheDataSource.Factory? {
        val c = get(ctx) ?: return null
        return CacheDataSource.Factory()
            .setCache(c)
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    /** 播放器 Builder: 起播 2.5 秒不变, 播放中预存到 120 秒。 */
    fun playerBuilder(ctx: Context): ExoPlayer.Builder {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                PRELOAD_MS.toInt(),     // minBufferMs: 低于它继续下载
                PRELOAD_MS.toInt(),     // maxBufferMs: 达到它停止下载
                START_BUFFER_MS,        // 起播门槛(默认值, 点进去不等待)
                START_BUFFER_MS,        // 卡顿后重新起播门槛(默认值)
            )
            // 不打开 prioritizeTimeOverSizeThresholds: 保留 ExoPlayer 的字节上限(约128MB)作为
            // 内存保险。1080p 片子能攒满 120 秒; 极高码率(4K)时按内存上限截断, 避免 OOM。
            .build()
        return ExoPlayer.Builder(ctx).setLoadControl(loadControl)
    }

    /**
     * 延迟解析的缓存数据源工厂: 只在真正创建 DataSource 时才解析缓存。
     * createDataSource() 由 ExoPlayer 的加载线程调用(不在主线程), 所以首次的
     * SimpleCache 初始化(建 SQLite 索引)也发生在加载线程 —— UI 线程完全不碰。
     * 初始化失败自动降级纯网络。
     */
    class LazyCacheFactory(
        private val ctx: Context,
        private val upstream: DataSource.Factory,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource {
            val cached = try { cacheFactory(ctx, upstream) } catch (e: Exception) { null }
            if (cached != null) {
                android.util.Log.i("VideoCache", "缓存数据源就绪, 启用落盘缓存")
            } else {
                android.util.Log.w("VideoCache", "缓存不可用, 本次降级纯网络播放")
            }
            return (cached ?: upstream).createDataSource()
        }
    }

    /**
     * 自动清理: ① 删掉超过 7 天的缓存 ② 总大小超过 1GB 时从最旧的开始删。
     * 只删内存索引(文件同步删除), 播放中的片段被 lock 不会被删。
     */
    fun trim(c: SimpleCache) {
        val now = System.currentTimeMillis()
        val groups = mutableListOf<Pair<String, List<CacheSpan>>>()
        for (key in c.keys.toList()) {
            val spans = try { c.getCachedSpans(key).toList() } catch (e: Exception) { emptyList() }
            if (spans.isNotEmpty()) groups.add(key to spans)
        }
        if (groups.isEmpty()) return

        fun bytesOf(spans: List<CacheSpan>) = spans.sumOf { if (it.length > 0) it.length else 0L }
        fun lastTouch(spans: List<CacheSpan>) = spans.maxOfOrNull { it.lastTouchTimestamp } ?: 0L

        // ① 过期(> 7 天)
        for ((key, spans) in groups) {
            if (now - lastTouch(spans) > MAX_AGE_MS) {
                try { c.removeResource(key) } catch (e: Exception) {}
            }
        }
        // ② 超上限: 最旧的先删
        val limit = CACHE_MB * 1024 * 1024
        val alive = groups.filter { (k, _) -> try { c.getCachedSpans(k).isNotEmpty() } catch (e: Exception) { false } }
        var total = alive.sumOf { bytesOf(it.second) }
        if (total <= limit) return
        for ((key, spans) in alive.sortedBy { lastTouch(it.second) }) {
            if (total <= limit) break
            try { c.removeResource(key) } catch (e: Exception) { continue }
            total -= bytesOf(spans)
        }
    }
}
