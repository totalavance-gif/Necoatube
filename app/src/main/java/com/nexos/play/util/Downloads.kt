package com.nexos.play.util

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment

object Downloads {

    fun fileName(title: String, label: String, ext: String): String {
        val clean = title.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().take(80)
            .ifBlank { "video" }
        val tag = label.replace(Regex("[^A-Za-z0-9]+"), "")
        return if (tag.isEmpty()) "$clean.$ext" else "${clean}_$tag.$ext"
    }

    fun enqueue(ctx: Context, url: String, name: String): Long {
        val req = DownloadManager.Request(Uri.parse(url))
            .setTitle(name)
            .setDescription("NexosPlay")
            .setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
            )
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            .setAllowedOverMetered(true)
        return (ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
    }
}
