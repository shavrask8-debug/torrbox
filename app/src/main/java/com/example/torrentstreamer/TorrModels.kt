package com.example.torrentstreamer

import com.google.gson.annotations.SerializedName

// Модель для команд серверу (додати, видалити, список)
data class TorrentAction(
    @SerializedName("action") val action: String,
    @SerializedName("hash") val hash: String? = null,
    @SerializedName("link") val link: String? = null,
    @SerializedName("title") val title: String? = null,
    @SerializedName("poster") val poster: String? = null,
    @SerializedName("data") val data: String? = null,
    @SerializedName("save_to_db") val saveToDb: Boolean = true
)

// Модель самого торента
data class Torrent(
    @SerializedName("hash") val hash: String,
    @SerializedName("title") val title: String,
    @SerializedName("poster") val poster: String?,
    @SerializedName("file_stats") val fileStats: List<TorrentFile>? = null,
    @SerializedName("download_speed") val downloadSpeed: Long = 0,
    @SerializedName("connected_seeders") val seeds: Int = 0,
    @SerializedName("active_peers") val peers: Int = 0,
    // Додаткові поля для Expressive дизайну
    val type: String? = "Кіно",
    val year: String? = null,
    val genre: String? = null
) {
    val allFiles: List<TorrentFile> get() = fileStats ?: emptyList()
}

// Модель файлу (серії) всередині торента
data class TorrentFile(
    @SerializedName("id") val index: Int,
    @SerializedName("path") val path: String,
    @SerializedName("length") val size: Long
)

// Конфігурація Torznab з Go-сервера (переведено на Nullable)
data class TorznabConfig(
    @SerializedName("Host") var host: String? = "",
    @SerializedName("Key") var key: String? = "",
    @SerializedName("Name") var name: String? = ""
)

// Конфігурація TMDB з Go-сервера (переведено на Nullable)
data class TMDBConfig(
    @SerializedName("APIKey") var apiKey: String? = "",
    @SerializedName("APIURL") var apiUrl: String? = "https://api.themoviedb.org",
    @SerializedName("ImageURL") var imageUrl: String? = "https://image.tmdb.org",
    @SerializedName("ImageURLRu") var imageUrlRu: String? = "https://imagetmdb.com"
)

// Клас-обгортка запиту налаштувань для сумісності з Go-сервером Matrix
data class SettingsPayload(
    @SerializedName("action") val action: String,
    @SerializedName("sets") val sets: TorrSettings? = null
)

// ОНОВЛЕНО: Повністю Null-безпечна PRO модель налаштувань
data class TorrSettings(
    // Налаштування Кешу
    @SerializedName("CacheSize") var cacheSize: Long = 67108864,
    @SerializedName("ReaderReadAHead") var readAhead: Int = 95,
    @SerializedName("PreloadCache") var preloadCache: Int = 50,
    @SerializedName("UseDisk") var useDisk: Boolean = false,
    @SerializedName("TorrentsSavePath") var torrentsSavePath: String? = "",
    @SerializedName("RemoveCacheOnDrop") var removeCacheOnDrop: Boolean = false,

    // Протоколи
    @SerializedName("EnableIPv6") var enableIPv6: Boolean = false,
    @SerializedName("DisableTCP") var disableTCP: Boolean = false,
    @SerializedName("DisableUTP") var disableUTP: Boolean = false,
    @SerializedName("DisableUPNP") var disableUPNP: Boolean = false,
    @SerializedName("DisableDHT") var disableDHT: Boolean = false,
    @SerializedName("DisablePEX") var disablePEX: Boolean = false,
    @SerializedName("DisableUpload") var disableUpload: Boolean = false,

    // Ліміти & Порти
    @SerializedName("DownloadRateLimit") var downloadRateLimit: Int = 0,
    @SerializedName("UploadRateLimit") var uploadRateLimit: Int = 0,
    @SerializedName("ConnectionsLimit") var connectionsLimit: Int = 25,
    @SerializedName("PeersListenPort") var peersListenPort: Int = 0,
    @SerializedName("TorrentDisconnectTimeout") var torrentDisconnectTimeout: Int = 30,
    @SerializedName("ForceEncrypt") var forceEncrypt: Boolean = false,
    @SerializedName("EnableDebug") var enableDebug: Boolean = false,

    // LPD (Local Peer Discovery)
    @SerializedName("EnableLPD") var enableLPD: Boolean = false,
    @SerializedName("LPDIPv6") var lpdIPv6: Boolean = false,

    // Режим Ретрекерів
    @SerializedName("RetrackersMode") var retrackersMode: Int = 1,

    // DLNA
    @SerializedName("EnableDLNA") var enableDLNA: Boolean = false,
    @SerializedName("FriendlyName") var friendlyName: String? = "",

    // HTTPS & SSL
    @SerializedName("SslPort") var sslPort: Int = 0,
    @SerializedName("SslCert") var sslCert: String? = "",
    @SerializedName("SslKey") var sslKey: String? = "",

    // Режим рідера
    @SerializedName("ResponsiveMode") var responsiveMode: Boolean = true,

    // FS & Налаштування сховища
    @SerializedName("ShowFSActiveTorr") var showFSActiveTorr: Boolean = true,
    @SerializedName("StoreSettingsInJson") var storeSettingsInJson: Boolean = true,
    @SerializedName("StoreViewedInJson") var storeViewedInJson: Boolean = false,

    // Пошукові сервіси (Зроблено Nullable для повної сумісності з Gson)
    @SerializedName("EnableRutorSearch") var enableRutorSearch: Boolean = false,
    @SerializedName("EnableTorznabSearch") var enableTorznabSearch: Boolean = false,
    @SerializedName("TorznabUrls") var torznabUrls: List<TorznabConfig>? = emptyList(),

    // TMDB Метадані
    @SerializedName("TMDBSettings") var tmdbSettings: TMDBConfig? = TMDBConfig()
)