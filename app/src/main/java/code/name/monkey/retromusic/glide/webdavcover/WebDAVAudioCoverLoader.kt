package code.name.monkey.retromusic.glide.webdavcover

import com.bumptech.glide.load.Options
import com.bumptech.glide.load.model.ModelLoader
import com.bumptech.glide.load.model.ModelLoaderFactory
import com.bumptech.glide.load.model.MultiModelLoaderFactory
import com.bumptech.glide.signature.ObjectKey
import java.io.InputStream

class WebDAVAudioCoverLoader : ModelLoader<WebDAVAudioCover, InputStream> {
    override fun buildLoadData(
        model: WebDAVAudioCover,
        width: Int,
        height: Int,
        options: Options
    ): ModelLoader.LoadData<InputStream> {
        val signature = "${model.configId}:${model.coverUrls.joinToString("|")}"
        return ModelLoader.LoadData(ObjectKey(signature), WebDAVAudioCoverFetcher(model))
    }

    override fun handles(model: WebDAVAudioCover): Boolean {
        return model.coverUrls.isNotEmpty()
    }

    class Factory : ModelLoaderFactory<WebDAVAudioCover, InputStream> {
        override fun build(multiFactory: MultiModelLoaderFactory): ModelLoader<WebDAVAudioCover, InputStream> {
            return WebDAVAudioCoverLoader()
        }

        override fun teardown() = Unit
    }
}
