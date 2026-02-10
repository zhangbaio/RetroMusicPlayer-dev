package code.name.monkey.retromusic.glide.servercover

import com.bumptech.glide.load.Options
import com.bumptech.glide.load.model.ModelLoader
import com.bumptech.glide.load.model.ModelLoaderFactory
import com.bumptech.glide.load.model.MultiModelLoaderFactory
import com.bumptech.glide.signature.ObjectKey
import java.io.InputStream

/**
 * Glide ModelLoader for loading cover art from the music server API.
 */
class ServerAudioCoverLoader : ModelLoader<ServerAudioCover, InputStream> {

    override fun buildLoadData(
        model: ServerAudioCover,
        width: Int,
        height: Int,
        options: Options
    ): ModelLoader.LoadData<InputStream> {
        val signature = "${model.configId}:${model.coverUrl}"
        return ModelLoader.LoadData(ObjectKey(signature), ServerAudioCoverFetcher(model))
    }

    override fun handles(model: ServerAudioCover): Boolean {
        return model.coverUrl.isNotBlank()
    }

    class Factory : ModelLoaderFactory<ServerAudioCover, InputStream> {
        override fun build(multiFactory: MultiModelLoaderFactory): ModelLoader<ServerAudioCover, InputStream> {
            return ServerAudioCoverLoader()
        }

        override fun teardown() = Unit
    }
}
