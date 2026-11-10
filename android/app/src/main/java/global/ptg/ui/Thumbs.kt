package global.ptg.ui

import android.net.Uri
import android.util.Size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.key.Keyer
import coil3.request.Options
import coil3.size.pxOrElse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A gallery thumbnail. Loaded through MediaStore's own thumbnail cache, which the system keeps
 * pre-generated, instead of decoding the full photo (or a video frame) for every grid cell.
 */
data class Thumb(val uri: Uri)

class ThumbFetcher(private val thumb: Thumb, private val options: Options) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val px = options.size.width.pxOrElse { 320 }.coerceIn(96, 1024)
        val bitmap = options.context.contentResolver.loadThumbnail(thumb.uri, Size(px, px), null)
        return ImageFetchResult(bitmap.asImage(), isSampled = true, dataSource = DataSource.DISK)
    }

    class Factory : Fetcher.Factory<Thumb> {
        override fun create(data: Thumb, options: Options, imageLoader: ImageLoader) = ThumbFetcher(data, options)
    }
}

class ThumbKeyer : Keyer<Thumb> {
    override fun key(data: Thumb, options: Options) = "thumb:${data.uri}"
}

/**
 * Like `remember(key) { compute(key) }`, but computed off the main thread, keeping the previous
 * value on screen until the new one is ready, so heavy filtering never costs a frame.
 */
@Composable
fun <K, T> rememberComputed(key: K, initial: T, compute: (K) -> T): T {
    var value by remember { mutableStateOf(initial) }
    LaunchedEffect(key) { value = withContext(Dispatchers.Default) { compute(key) } }
    return value
}
