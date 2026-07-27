package com.example.lecturesummarizer

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.example.lecturesummarizer.data.LectureDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SummifyWidgetPanel : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val dao = LectureDatabase.getDatabase(context).lectureDao()
        CoroutineScope(Dispatchers.IO).launch {
            val latest = dao.getLatestLecture()
            for (appWidgetId in appWidgetIds) {
                val views = RemoteViews(context.packageName, R.layout.summify_widget_panel)
                views.setTextViewText(R.id.txt_latest_lecture, latest?.title ?: "Нет записей")
                
                val intent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    putExtra("START_RECORDING", true)
                }
                val pendingIntent = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                views.setOnClickPendingIntent(R.id.btn_record_panel, pendingIntent)
                views.setOnClickPendingIntent(R.id.widget_panel_container, pendingIntent)
                
                appWidgetManager.updateAppWidget(appWidgetId, views)
            }
        }
    }
}
