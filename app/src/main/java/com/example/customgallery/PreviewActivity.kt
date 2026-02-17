package com.example.customgallery

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.VideoView
import androidx.activity.ComponentActivity
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

    override fun getItemCount(): Int = items.size

    class PreviewPageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val image: ImageView = view.findViewById(R.id.previewImage)
        private val video: VideoView = view.findViewById(R.id.previewVideo)

        fun bind(item: PreviewItem) {
            if (item.isVideo) {
                Glide.with(itemView).clear(image)
                image.visibility = View.GONE
                video.visibility = View.VISIBLE
                video.setVideoURI(item.uri)
                video.start()
            } else {
                video.stopPlayback()
                video.visibility = View.GONE
                image.visibility = View.VISIBLE
                Glide.with(itemView).load(item.uri).into(image)
            }
        }

        fun clear() {
            Glide.with(itemView).clear(image)
            video.stopPlayback()
        }
    }
}
