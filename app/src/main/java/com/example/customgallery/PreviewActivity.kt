package com.example.customgallery

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.bumptech.glide.Glide

class PreviewActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_preview)

        val uriStrings = intent.getStringArrayListExtra(EXTRA_URIS)?.filterNotNull().orEmpty()
        val isVideos = intent.getBooleanArrayExtra(EXTRA_IS_VIDEOS)
        val startIndex = intent.getIntExtra(EXTRA_START_INDEX, 0)

        if (uriStrings.isEmpty()) {
            finish()
            return
        }

        val items = uriStrings.mapIndexed { index, value ->
            val uri = Uri.parse(value)
            val isVideo = isVideos?.getOrNull(index) ?: false
            PreviewItem(uri = uri, isVideo = isVideo)
        }.toMutableList()

        val pager = findViewById<ViewPager2>(R.id.previewPager)
        val actionBar = findViewById<View>(R.id.previewActionsBar)
        val shareButton = findViewById<ImageButton>(R.id.previewShareButton)
        val albumButton = findViewById<ImageButton>(R.id.previewAlbumButton)
        val deleteButton = findViewById<ImageButton>(R.id.previewDeleteButton)
        val adapter = PreviewPagerAdapter(items)

        pager.adapter = adapter
        pager.setCurrentItem(startIndex.coerceIn(0, items.lastIndex), false)

        ViewCompat.setOnApplyWindowInsetsListener(actionBar) { view, insets ->
            val navBarInset = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            view.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = resources.getDimensionPixelSize(R.dimen.preview_actions_bottom_margin) + navBarInset
            }
            insets
        }
        ViewCompat.requestApplyInsets(actionBar)

        shareButton.setOnClickListener {
            val currentItem = items.getOrNull(pager.currentItem) ?: return@setOnClickListener
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = if (currentItem.isVideo) "video/*" else "image/*"
                putExtra(Intent.EXTRA_STREAM, currentItem.uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, getString(R.string.preview_share)))
        }

        albumButton.setOnClickListener { finish() }

        deleteButton.setOnClickListener {
            val position = pager.currentItem
            val itemToDelete = items.getOrNull(position) ?: return@setOnClickListener
            val deletedCount = contentResolver.delete(itemToDelete.uri, null, null)
            if (deletedCount > 0) {
                adapter.removeAt(position)
                if (items.isEmpty()) {
                    finish()
                } else {
                    val nextIndex = position.coerceAtMost(items.lastIndex)
                    pager.setCurrentItem(nextIndex, false)
                }
            } else {
                Toast.makeText(this, R.string.preview_delete_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    companion object {
        const val EXTRA_URIS = "preview_uris"
        const val EXTRA_IS_VIDEOS = "preview_is_videos"
        const val EXTRA_START_INDEX = "preview_start_index"
    }
}

data class PreviewItem(
    val uri: Uri,
    val isVideo: Boolean
)

private class PreviewPagerAdapter(
    private val items: MutableList<PreviewItem>
) : RecyclerView.Adapter<PreviewPagerAdapter.PreviewPageViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PreviewPageViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_preview_page, parent, false)
        return PreviewPageViewHolder(view)
    }

    override fun onBindViewHolder(holder: PreviewPageViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun onViewRecycled(holder: PreviewPageViewHolder) {
        holder.clear()
        super.onViewRecycled(holder)
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
    }

    override fun getItemCount(): Int = items.size

    fun removeAt(position: Int) {
        if (position !in items.indices) return
        items.removeAt(position)
        notifyItemRemoved(position)
    }

    class PreviewPageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val image: ImageView = view.findViewById(R.id.previewImage)
        private val playerView: PlayerView = view.findViewById(R.id.previewPlayerView)
        private var player: ExoPlayer? = null

        fun bind(item: PreviewItem) {
            if (item.isVideo) {
                Glide.with(itemView).clear(image)
                image.visibility = View.GONE
                playerView.visibility = View.VISIBLE
                val localPlayer = player ?: ExoPlayer.Builder(itemView.context).build().also {
                    player = it
                    playerView.player = it
                }
                playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                localPlayer.setMediaItem(MediaItem.fromUri(item.uri))
                localPlayer.prepare()
                localPlayer.playWhenReady = true
            } else {
                releasePlayer()
                playerView.visibility = View.GONE
                image.visibility = View.VISIBLE
                Glide.with(itemView).load(item.uri).into(image)
            }
        }

        fun clear() {
            Glide.with(itemView).clear(image)
            releasePlayer()
        }

        private fun releasePlayer() {
            player?.release()
            player = null
            playerView.player = null
        }
    }
}
