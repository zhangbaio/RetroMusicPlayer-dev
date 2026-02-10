package code.name.monkey.retromusic

import androidx.room.Room
import code.name.monkey.retromusic.auto.AutoMusicProvider
import code.name.monkey.retromusic.cast.RetroWebServer
import code.name.monkey.retromusic.db.MIGRATION_23_24
import code.name.monkey.retromusic.db.MIGRATION_24_25
import code.name.monkey.retromusic.db.MIGRATION_25_26
import code.name.monkey.retromusic.db.MIGRATION_27_28
import code.name.monkey.retromusic.db.MIGRATION_28_29
import code.name.monkey.retromusic.db.MIGRATION_29_30
import code.name.monkey.retromusic.db.RetroDatabase
import code.name.monkey.retromusic.fragments.LibraryViewModel
import code.name.monkey.retromusic.fragments.albums.AlbumDetailsViewModel
import code.name.monkey.retromusic.fragments.artists.ArtistDetailsViewModel
import code.name.monkey.retromusic.fragments.genres.GenreDetailsViewModel
import code.name.monkey.retromusic.fragments.playlists.PlaylistDetailsViewModel
import code.name.monkey.retromusic.fragments.server.ServerViewModel
import code.name.monkey.retromusic.model.Genre
import code.name.monkey.retromusic.model.ServerConfig
import code.name.monkey.retromusic.network.MusicApiService
import code.name.monkey.retromusic.network.provideDefaultCache
import code.name.monkey.retromusic.network.provideLastFmRest
import code.name.monkey.retromusic.network.provideLastFmRetrofit
import code.name.monkey.retromusic.network.provideOkHttp
import code.name.monkey.retromusic.network.provideMusicApiOkHttp
import code.name.monkey.retromusic.network.provideMusicApiRetrofit
import code.name.monkey.retromusic.network.provideMusicApiService
import code.name.monkey.retromusic.repository.*
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.bind
import org.koin.dsl.module

val networkModule = module {

    factory {
        provideDefaultCache()
    }
    factory {
        provideOkHttp(get(), get())
    }
    single {
        provideLastFmRetrofit(get())
    }
    single {
        provideLastFmRest(get())
    }
}

private val roomModule = module {

    single {
        Room.databaseBuilder(androidContext(), RetroDatabase::class.java, "playlist.db")
            .addMigrations(
                MIGRATION_23_24,
                MIGRATION_24_25,
                MIGRATION_25_26,
                MIGRATION_27_28,
                MIGRATION_28_29,
                MIGRATION_29_30
            )
            .fallbackToDestructiveMigration()
            .build()
    }

    factory {
        get<RetroDatabase>().playlistDao()
    }

    factory {
        get<RetroDatabase>().playCountDao()
    }

    factory {
        get<RetroDatabase>().historyDao()
    }

    factory {
        get<RetroDatabase>().serverDao()
    }

    single {
        RealRoomRepository(get(), get(), get())
    } bind RoomRepository::class
}
private val autoModule = module {
    single {
        AutoMusicProvider(
            androidContext(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get()
        )
    }
}
private val mainModule = module {
    single {
        androidContext().contentResolver
    }
    single {
        RetroWebServer(get())
    }
}
private val dataModule = module {
    single {
        RealRepository(
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(), // ServerRepository
        )
    } bind Repository::class

    single {
        RealSongRepository(get())
    } bind SongRepository::class

    single {
        RealGenreRepository(get(), get())
    } bind GenreRepository::class

    single {
        RealAlbumRepository(get())
    } bind AlbumRepository::class

    single {
        RealArtistRepository(get(), get())
    } bind ArtistRepository::class

    single {
        RealPlaylistRepository(get())
    } bind PlaylistRepository::class

    single {
        RealTopPlayedRepository(get(), get(), get(), get())
    } bind TopPlayedRepository::class

    single {
        RealLastAddedRepository(
            get(),
            get(),
            get()
        )
    } bind LastAddedRepository::class

    single {
        RealSearchRepository(
            get(),
            get(),
            get(),
            get(),
            get()
        )
    }
    single {
        RealLocalDataRepository(get())
    } bind LocalDataRepository::class
}

private val viewModules = module {

    viewModel {
        LibraryViewModel(get())
    }

    viewModel { (albumId: Long) ->
        AlbumDetailsViewModel(
            get(),
            albumId
        )
    }

    viewModel { (artistId: Long?, artistName: String?) ->
        ArtistDetailsViewModel(
            get(),
            artistId,
            artistName
        )
    }

    viewModel { (playlistId: Long) ->
        PlaylistDetailsViewModel(
            get(),
            playlistId
        )
    }

    viewModel { (genre: Genre) ->
        GenreDetailsViewModel(
            get(),
            genre
        )
    }

    viewModel { ServerViewModel(get()) }
}

private val serverModule = module {
    single {
        val apiServiceProvider: suspend (ServerConfig) -> MusicApiService = { config ->
            val okHttp = provideMusicApiOkHttp(config.serverUrl, config.apiToken)
            val retrofit = provideMusicApiRetrofit(config.serverUrl, okHttp)
            provideMusicApiService(retrofit)
        }
        RealServerRepository(get(), apiServiceProvider)
    } bind ServerRepository::class
}

val appModules = listOf(mainModule, dataModule, autoModule, viewModules, networkModule, roomModule, serverModule)
