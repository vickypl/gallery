package com.example.customgallery

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.VideoView
import androidx.activity.ComponentActivity
import com.bumptech.glide.Glide

class PreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_preview)

        val uri = intent.getStringExtra("uri")?.let(Uri::parse) ?: run {
            finish()
            return
        }
        val isVideo = intent.getBooleanExtra("isVideo", false)

        val image = findViewById<ImageView>(R.id.previewImage)
        val video = findViewById<VideoView>(R.id.previewVideo)

        if (isVideo) {
            image.visibility = View.GONE
            video.visibility = View.VISIBLE
            video.setVideoURI(uri)
            video.start()
        } else {
            video.visibility = View.GONE
            image.visibility = View.VISIBLE
            Glide.with(this).load(uri).into(image)
        }
    }
}
