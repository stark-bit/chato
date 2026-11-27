package com.whisperonnx.testscreen;

import com.whisperonnx.BuildConfig;
import com.whisperonnx.R;
import com.whisperonnx.prefs.Prefs;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;

public class GithubStar {
    public static void setAskForStar(boolean askForStar, Context context) {
        Prefs.of(context).setAskForStar(askForStar);
    }

    public static boolean shouldShowStarDialog(Context context) {
        Prefs prefs = Prefs.of(context);
        boolean show = prefs.starVersionIsOlderThan(BuildConfig.VERSION_CODE) && prefs.askForStar();
        prefs.setStarVersion(BuildConfig.VERSION_CODE);
        return show;
    }

    public static void starDialog(Context context, String url) {
        if (Prefs.of(context).askForStar()) {
            AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(context);
            alertDialogBuilder.setMessage(R.string.dialog_StarOnGitHub);
            alertDialogBuilder.setPositiveButton(context.getString(android.R.string.ok), new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    context.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                    setAskForStar(false, context);
                }
            });
            alertDialogBuilder.setNegativeButton(context.getString(android.R.string.no), new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    setAskForStar(false, context);
                }
            });
            alertDialogBuilder.setNeutralButton(context.getString(R.string.dialog_Later_button), null);

            AlertDialog alertDialog = alertDialogBuilder.create();
            alertDialog.show();
        }
    }
}