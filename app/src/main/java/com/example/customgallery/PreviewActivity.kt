package com.example.customgallery

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.activity.ComponentActivity
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
        }

        val pager = findViewById<ViewPager2>(R.id.previewPager)
        pager.adapter = PreviewPagerAdapter(items)
        pager.setCurrentItem(startIndex.coerceIn(0, items.lastIndex), false)
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
    private val items: List<PreviewItem>
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
