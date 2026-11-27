package com.whisperonnx.quickrecord;

import com.whisperonnx.R;
import com.whisperonnx.endpoint.EndpointSender;
import com.whisperonnx.ui.Components;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.view.View;
import android.widget.RemoteViews;

import androidx.preference.PreferenceManager;


/**
 * The quick record widget: one mic button that starts and stops quick record for one endpoint,
 * chosen when the widget is placed ({@link QuickRecordWidgetConfigActivity}). Shows the endpoint's
 * number and name, and is lit while recording for that endpoint.
 */
public class QuickRecordWidgetProvider extends AppWidgetProvider {

    /** Pref key for a widget's endpoint (1 or 2). Widgets placed before this existed have none. */
    static String endpointKey(int widgetId) {
        return "quickRecordWidget_" + widgetId;
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        for (int id : ids) manager.updateAppWidget(id, views(context, id));
    }

    @Override
    public void onDeleted(Context context, int[] ids) {
        SharedPreferences.Editor e = PreferenceManager.getDefaultSharedPreferences(context).edit();
        for (int id : ids) e.remove(endpointKey(id));
        e.apply();
    }

    /** Redraws every placed widget, e.g. when recording starts or stops. */
    static void updateAll(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        for (int id : manager.getAppWidgetIds(new ComponentName(context.getPackageName(), Components.QUICK_RECORD_WIDGET))) {
            manager.updateAppWidget(id, views(context, id));
        }
    }

    static RemoteViews views(Context context, int widgetId) {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
        int chosen = sp.getInt(endpointKey(widgetId), 0);
        int index = chosen != 0 ? chosen : sp.getInt(EndpointSender.PREF_QUICK_RECORD, 1);
        boolean recording = QuickRecordService.isRecordingTo(index);
        String name = EndpointSender.load(sp, index).displayName();

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

        // A distinct data URI per widget, so each keeps its own PendingIntent and endpoint
        Intent toggle = new Intent(context, QuickRecordActivity.class)
                .setData(Uri.parse("chato://quick-record/" + widgetId))
                .putExtra(QuickRecordService.EXTRA_ENDPOINT, chosen);
        v.setOnClickPendingIntent(R.id.widget_quick_record_root,
                PendingIntent.getActivity(context, widgetId, toggle, PendingIntent.FLAG_IMMUTABLE));
        return v;
    }
}
