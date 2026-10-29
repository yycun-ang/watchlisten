package com.watchlisten.music.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.util.AtomicFile
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.watchlisten.music.WatchApplication
import com.watchlisten.music.data.coverUrlCandidates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 歌曲封面加载。
 *
 * 原尺寸质量与缓存容量分别管理：封面按高分辨率解码，内存缓存按实际堆空间
 * 分配，磁盘保留下载原件。同图请求复用结果，文件原子写入，避免错图与半文件。
 *
 * 磁盘读写一律切到 [Dispatchers.IO]。解码放在主线程会让列表滚动掉帧，
 * 而 stat 整个缓存目录在手表存储上足以触发输入超时。
 */
class CoverCache(context: Context, private val maxBytes: Int) {

    private val disk = File(context.cacheDir, "covers")
    private val diskMutex = Mutex()
    private class LoadLock(val mutex: Mutex = Mutex(), var users: Int = 0)
    private val loads = mutableMapOf<String, LoadLock>()
    private val decoders = Semaphore(2)
    private val memory = object : LruCache<String, Bitmap>(maxBytes) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    fun cached(url: String): Bitmap? = memory.get(url)

    fun put(url: String, bitmap: Bitmap) {
        memory.put(url, bitmap)
    }

    /** 按 URL 散列定位磁盘副本，文件名只与内容来源有关，不含歌名。 */
    private fun fileFor(url: String) = File(disk, coverDiskKey(url))

    /** 等待中的同图请求共享一次加载；取消时同步释放锁记录，不留下永久任务。 */
    suspend fun load(url: String, download: suspend () -> ByteArray): Bitmap? {
        val request = synchronized(loads) { loads.getOrPut(url) { LoadLock() }.also { it.users++ } }
        try {
            return request.mutex.withLock {
                cached(url)?.let { return@withLock it }
                fromDisk(url)?.let { return@withLock it }
                decoders.withPermit {
                    val bytes = download()
                    val decoded = withContext(Dispatchers.IO) { decodeCover(bytes) } ?: return@withPermit null
                    put(url, decoded)
                    save(url, bytes)
                    decoded
                }
            }
        } finally {
            synchronized(loads) {
                request.users--
                if (request.users == 0) loads.remove(url)
            }
        }
    }

    suspend fun fromDisk(url: String): Bitmap? = withContext(Dispatchers.IO) {
        val bytes = diskMutex.withLock {
            val file = fileFor(url)
            if (!file.isFile && !File(file.path + ".bak").isFile) return@withLock null
            runCatching {
                AtomicFile(file).openRead().use { it.readBytes() }
                    .also { file.setLastModified(System.currentTimeMillis()) }
            }.getOrNull()
        } ?: return@withContext null
        decoders.withPermit { decodeCover(bytes) }?.also { memory.put(url, it) }
    }

    suspend fun save(url: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        diskMutex.withLock {
            disk.mkdirs()
            val file = AtomicFile(fileFor(url))
            var output: java.io.FileOutputStream? = null
            try {
                output = file.startWrite()
                output.write(bytes)
                file.finishWrite(output)
            } catch (error: Exception) {
                output?.let(file::failWrite)
                if (error is CancellationException) throw error
            }
            trimDisk()
        }
    }

    /** 清理所有封面副本；磁盘操作与正在进行的读写互斥。 */
    suspend fun clear() = withContext(Dispatchers.IO) {
        diskMutex.withLock {
            memory.evictAll()
            disk.listFiles()?.filter(File::isFile)?.forEach {
                if (!it.delete() && it.exists()) throw java.io.IOException("Cover cache could not be cleared")
            }
        }
    }

    private fun trimDisk() {
        val files = disk.listFiles()?.filter(File::isFile) ?: return
        var totalBytes = files.sumOf(File::length)
        for (file in files.sortedBy(File::lastModified)) {
            if (totalBytes <= DISK_LIMIT) break
            val bytes = file.length()
            if (file.delete()) totalBytes -= bytes
        }
    }

    companion object {
        private const val DISK_LIMIT = 256L * 1024 * 1024

        @Volatile private var instance: CoverCache? = null

        fun shared(context: Context): CoverCache = instance ?: synchronized(this) {
            instance ?: CoverCache(context.applicationContext,
                (Runtime.getRuntime().maxMemory() / 4).coerceIn(1, Int.MAX_VALUE.toLong()).toInt())
                .also { instance = it }
        }
    }
}

@Composable
fun Cover(
    url: String,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 26.dp,
    fallback: @Composable () -> Unit = { Brand(58) }
) {
    val context = LocalContext.current
    val cache = remember(context.applicationContext) { CoverCache.shared(context) }
    val localFile = remember(url, context) { privateCoverFile(context, url) }
    val candidates = remember(url, localFile) { if (localFile != null) listOf(url) else coverUrlCandidates(url) }
    var bitmap by remember(candidates) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(candidates) {
        // 走 HttpTransport 而不是裸 OkHttpCall：封面与接口同属一套双栈回退
        // 与超时策略，手表上 IPv6 优先失败时才能一起生效。
        for (imageUrl in candidates) {
            val decoded = try {
                cache.load(imageUrl) {
                    if (localFile != null) withContext(Dispatchers.IO) {
                        if (localFile.length() > MAX_COVER_DOWNLOAD_BYTES) throw java.io.IOException("Cover too large")
                        localFile.readBytes()
                    } else (context.applicationContext as WatchApplication).graph.http
                        .bytes(Request.Builder().url(imageUrl).build(), limit = MAX_COVER_DOWNLOAD_BYTES)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) { null }
            if (decoded != null) {
                bitmap = decoded.asImageBitmap()
                return@LaunchedEffect
            }
        }
    }

    Box(modifier.clip(RoundedCornerShape(cornerRadius)).background(Panel),
        contentAlignment = Alignment.Center) {
        val current = bitmap
        if (current != null) Image(current, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
            filterQuality = FilterQuality.High)
        else fallback()
    }
}

private fun privateCoverFile(context: Context, url: String): File? = runCatching {
    if (!url.startsWith("file:")) return null
    val file = File(java.net.URI(url))
    file.takeIf { it.isFile && it.canonicalFile.parentFile == File(context.filesDir, "local-covers").canonicalFile }
}.getOrNull()

/** 按短边保留清晰度，直接裁取实际展示的中心方图；不把小图放大伪装成高清。 */
internal fun decodeCover(bytes: ByteArray): Bitmap? = try {
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val ratio = min(1.0, COVER_TARGET_PX.toDouble() / min(info.size.width, info.size.height))
        val width = (info.size.width * ratio).roundToInt().coerceAtLeast(1)
        val height = (info.size.height * ratio).roundToInt().coerceAtLeast(1)
        decoder.setTargetSize(width, height)
        val side = min(width, height)
        val left = (width - side) / 2
        val top = (height - side) / 2
        decoder.crop = Rect(left, top, left + side, top + side)
    }
} catch (_: Exception) { null }

internal fun coverDiskKey(url: String): String = MessageDigest.getInstance("SHA-256")
    .digest(url.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }

private const val COVER_TARGET_PX = 800
private const val MAX_COVER_DOWNLOAD_BYTES = 16 * 1024 * 1024
