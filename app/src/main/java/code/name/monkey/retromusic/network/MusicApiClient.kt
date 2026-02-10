package code.name.monkey.retromusic.network

import com.google.gson.GsonBuilder
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

fun provideMusicApiOkHttp(serverUrl: String, apiToken: String): OkHttpClient {
    return OkHttpClient.Builder()
        .addInterceptor(Interceptor { chain ->
            val request = chain.request().newBuilder()
                .header("Authorization", "Bearer $apiToken")
                .build()
            chain.proceed(request)
        })
        .addNetworkInterceptor(logInterceptor())
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()
}

fun provideMusicApiRetrofit(serverUrl: String, client: OkHttpClient): Retrofit {
    val baseUrl = if (serverUrl.endsWith("/")) serverUrl else "$serverUrl/"
    val gson = GsonBuilder().setLenient().create()
    return Retrofit.Builder()
        .baseUrl(baseUrl)
        .addConverterFactory(GsonConverterFactory.create(gson))
        .callFactory { request -> client.newCall(request) }
        .build()
}

fun provideMusicApiService(retrofit: Retrofit): MusicApiService {
    return retrofit.create(MusicApiService::class.java)
}
