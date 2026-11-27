package com.whisperonnx.quickrecord;

import com.whisperonnx.prefs.Prefs;
import com.whisperonnx.R;
import com.whisperonnx.endpoint.EndpointSender;

import android.appwidget.AppWidgetManager;
import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public class QuickRecordWidgetConfigActivity extends AppCompatActivity {
    private int widgetId = AppWidgetManager.INVALID_APPWIDGET_ID;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Bundle extras = getIntent().getExtras();
        if (extras != null) widgetId = extras.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId);
        setResult(RESULT_CANCELED, result());  // External: backing out leaves RESULT_CANCELED, so Android doesn't add the widget
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish();
            return;
        }

        Prefs prefs = Prefs.of(this);
        String[] items = new String[EndpointSender.COUNT];
        for (int i = 1; i <= EndpointSender.COUNT; i++) {
            EndpointSender.Endpoint ep = EndpointSender.load(prefs, i);
            String title = getString(R.string.endpoint_title, i);
            items[i - 1] = ep.name.isEmpty() ? title : title + ": " + ep.name;
            if (!ep.isConfigured()) items[i - 1] += " " + getString(R.string.quick_record_widget_no_url);
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.quick_record_widget_pick)
                .setItems(items, (d, which) -> {
                    prefs.setWidgetEndpoint(widgetId, which + 1);
                    AppWidgetManager.getInstance(this).updateAppWidget(widgetId, QuickRecordWidgetProvider.views(this, widgetId));
                    setResult(RESULT_OK, result());
                    finish();
                })
                .setOnCancelListener(d -> finish())
                .show();
    }

    private Intent result() {
        return new Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId);
    }
}
