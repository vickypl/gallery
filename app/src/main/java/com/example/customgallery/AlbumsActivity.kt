package com.example.customgallery

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide

data class AlbumItem(
    val bucketId: Long,
    val name: String,
    val count: Int,
    val coverUri: android.net.Uri
)

class AlbumsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_albums)

        val recycler = findViewById<RecyclerView>(R.id.albumsRecyclerView)
        val empty = findViewById<TextView>(R.id.albumsEmptyStateText)
        recycler.layoutManager = LinearLayoutManager(this)

        if (!hasAnyMediaPermission()) {
            empty.visibility = View.VISIBLE
            empty.text = getString(R.string.albums_permission_required)
            return
        }

        val albums = loadAlbums(contentResolver)
        if (albums.isEmpty()) {
            empty.visibility = View.VISIBLE
            empty.text = getString(R.string.albums_empty)
            return
        }

        recycler.adapter = AlbumsAdapter(albums) { album ->
            startActivity(
                Intent(this, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_BUCKET_ID, album.bucketId)
                    .putExtra(MainActivity.EXTRA_ALBUM_NAME, album.name)
            )
            finish()
        }
    }

    private fun hasAnyMediaPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasPermission(Manifest.permission.READ_MEDIA_IMAGES) ||
                hasPermission(Manifest.permission.READ_MEDIA_VIDEO) ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                    hasPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))
        } else {
            hasPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun loadAlbums(contentResolver: ContentResolver): List<AlbumItem> {
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.Files.FileColumns.BUCKET_ID,
            MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME
        )
        val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?)"
        val args = arrayOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()
        )
        val sort = "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC, ${MediaStore.Files.FileColumns._ID} DESC"
        val albumsByBucket = linkedMapOf<Long, AlbumItem>()

        contentResolver.query(
            MediaStore.Files.getContentUri("external"),
            projection,
            selection,
            args,
            sort
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val typeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
            val bucketIdCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.BUCKET_ID)
            val bucketNameCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME)

            while (cursor.moveToNext()) {
                val bucketId = cursor.getLong(bucketIdCol)
                if (bucketId == 0L) continue
                val name = cursor.getString(bucketNameCol) ?: getString(R.string.albums_unknown)
                val mediaId = cursor.getLong(idCol)
                val mediaType = cursor.getInt(typeCol)
                val baseUri = if (mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO) {
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                } else {
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                }
                val coverUri = ContentUris.withAppendedId(baseUri, mediaId)

                val existing = albumsByBucket[bucketId]
                if (existing == null) {
                    albumsByBucket[bucketId] = AlbumItem(bucketId, name, 1, coverUri)
                } else {
                    albumsByBucket[bucketId] = existing.copy(count = existing.count + 1)
                }
            }
        }

        return albumsByBucket.values.toList()
    }
}

private class AlbumsAdapter(
    private val items: List<AlbumItem>,
    private val onClick: (AlbumItem) -> Unit
) : RecyclerView.Adapter<AlbumsAdapter.AlbumViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AlbumViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_album, parent, false)
        return AlbumViewHolder(view)
    }

    override fun onBindViewHolder(holder: AlbumViewHolder, position: Int) {
        holder.bind(items[position], onClick)
    }

    override fun getItemCount(): Int = items.size

    class AlbumViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val cover: ImageView = view.findViewById(R.id.albumCover)
        private val title: TextView = view.findViewById(R.id.albumTitle)
        private val count: TextView = view.findViewById(R.id.albumCount)

        fun bind(item: AlbumItem, onClick: (AlbumItem) -> Unit) {
            title.text = item.name
            count.text = itemView.context.getString(R.string.albums_count_format, item.count)
            Glide.with(itemView).load(item.coverUri).centerCrop().into(cover)
            itemView.setOnClickListener { onClick(item) }
        }
    }
}
