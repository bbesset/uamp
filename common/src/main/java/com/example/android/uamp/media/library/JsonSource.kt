/*
 * Copyright 2017 Google Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.uamp.media.library

import android.content.Context
import android.net.Uri
import android.support.v4.media.MediaBrowserCompat.MediaItem
import android.support.v4.media.MediaMetadataCompat
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestManager
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import com.example.android.uamp.media.R
import com.example.android.uamp.media.extensions.*
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.URL

/**
 * Source of [MediaMetadataCompat] objects created from a basic JSON stream.
 *
 * The definition of the JSON is specified in the docs of [JsonMusic] in this file,
 * which is the object representation of it.
 */
class JsonSource(context: Context, private val source: Uri) : AbstractMusicSource() {

    private var catalog: List<MediaMetadataCompat> = emptyList()
    private val glide: RequestManager

    init {
        state = STATE_INITIALIZING
        glide = Glide.with(context)
    }

    override fun iterator(): Iterator<MediaMetadataCompat> = catalog.iterator()

    override suspend fun load() {
        updateCatalog(source)?.let { updatedCatalog ->
            catalog = updatedCatalog
            state = STATE_INITIALIZED
        } ?: run {
            catalog = emptyList()
            state = STATE_ERROR
        }
    }

    /**
     * Function to connect to a remote URI and download/process the JSON file that corresponds to
     * [MediaMetadataCompat] objects.
     */
    private suspend fun updateCatalog(catalogUri: Uri): List<MediaMetadataCompat>? {
        return withContext(Dispatchers.IO) {
            val musicCat = try {
                downloadJson(catalogUri)
            } catch (ioException: IOException) {
                return@withContext null
            }

            musicCat.music.map { song ->
                // Block on downloading artwork.
                val artFile = glide.applyDefaultRequestOptions(glideOptions)
                    .downloadOnly()
                    .load(song.image)
                    .submit(NOTIFICATION_LARGE_ICON_SIZE, NOTIFICATION_LARGE_ICON_SIZE)
                    .get()

                // Expose file via Local URI
                val artUri = artFile.asAlbumArtContentUri()

                MediaMetadataCompat.Builder()
                    .from(song)
                    .apply {
                        displayIconUri = artUri.toString() // Used by ExoPlayer and Notification
                        albumArtUri = artUri.toString()
                    }
                    .build()
            }.toList()
        }
    }

    @Throws(IOException::class)
    private fun downloadJson(catalogUri: Uri): JsonCatalog {
        val catalogConn = URL(catalogUri.toString())
        val reader = BufferedReader(InputStreamReader(catalogConn.openStream()))
        return Gson().fromJson<JsonCatalog>(reader, JsonCatalog::class.java)
    }
}

/**
 * Extension method for [MediaMetadataCompat.Builder] to set the fields from
 * our JSON constructed object (to make the code a bit easier to see).
 */
fun MediaMetadataCompat.Builder.from(jsonMusic: JsonMusic): MediaMetadataCompat.Builder {
    id = jsonMusic.id
    title = jsonMusic.title
    mediaUri = jsonMusic.source
    albumArtUri = jsonMusic.image
    flag = MediaItem.FLAG_PLAYABLE

    // To make things easier for *displaying* these, set the display properties as well.
    displayTitle = jsonMusic.title
    displayDescription = jsonMusic.description
    displayIconUri = jsonMusic.image

//    downloadStatus = STATUS_NOT_DOWNLOADED
    return this
}

class JsonCatalog {
    var music: List<JsonMusic> = ArrayList()
}

@Suppress("unused")
class JsonMusic {
    var id: String = ""
    var title: String = ""
    var description: String = ""
    var source: String = ""
    var image: String = ""
}

private const val NOTIFICATION_LARGE_ICON_SIZE = 144 // px

private val glideOptions = RequestOptions()
    .fallback(R.drawable.default_art)
    .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
