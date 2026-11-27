package com.whisperonnx.quickrecord;

import com.whisperonnx.prefs.Prefs;
import com.whisperonnx.R;
import com.whisperonnx.endpoint.EndpointSender;
import com.whisperonnx.ui.Components;
import com.whisperonnx.ui.Labels;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.widget.RemoteViews;


public class QuickRecordWidgetProvider extends AppWidgetProvider {
    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        for (int id : ids) manager.updateAppWidget(id, views(context, id));
    }

    @Override
    public void onDeleted(Context context, int[] ids) {
        Prefs.of(context).forgetWidgets(ids);
    }

    static void updateAll(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        for (int id : manager.getAppWidgetIds(new ComponentName(context.getPackageName(), Components.QUICK_RECORD_WIDGET))) {
            manager.updateAppWidget(id, views(context, id));
        }
    }

    static RemoteViews views(Context context, int widgetId) {
        Prefs prefs = Prefs.of(context);
        int chosen = prefs.widgetEndpoint(widgetId);
        int index = chosen != 0 ? chosen : prefs.quickRecordEndpoint();
        boolean recording = QuickRecordService.isRecordingTo(index);
        String name = Labels.endpoint(context, EndpointSender.load(prefs, index));

        RemoteViews v = new RemoteViews(context.getPackageName(), R.layout.widget_quick_record);
        v.setInt(R.id.widget_quick_record, "setBackgroundResource",
                recording ? R.drawable.widget_quick_record_on : R.drawable.widget_quick_record_off);
        v.setInt(R.id.widget_quick_record, "setColorFilter",
                context.getColor(recording ? R.color.ime_on_accent : R.color.ime_on_key));
        v.setTextViewText(R.id.widget_quick_record_badge, String.valueOf(index));
        v.setViewVisibility(R.id.widget_quick_record_badge, chosen != 0 ? View.VISIBLE : View.GONE);
        v.setTextViewText(R.id.widget_quick_record_label, name);
        v.setContentDescription(R.id.widget_quick_record, context.getString(
                recording ? R.string.quick_record_stop_to : R.string.quick_record_to, name));

        // External: PendingIntents that differ only in extras are the same one, so each widget gets its own data URI
        Intent toggle = new Intent(context, QuickRecordActivity.class)
                .setData(Uri.parse("chato://quick-record/" + widgetId))
                .putExtra(QuickRecordService.EXTRA_ENDPOINT, chosen);
        v.setOnClickPendingIntent(R.id.widget_quick_record_root,
                PendingIntent.getActivity(context, widgetId, toggle, PendingIntent.FLAG_IMMUTABLE));
        return v;
    }
}
