package com.agemaguro.drivelog;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts background logging after the phone boots or the app is updated, if the saved settings ask for it. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        String a = intent == null ? null : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(a) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) return;
        EngineHost h = EngineHost.get(ctx);
        if (h.savedWantsService()) { h.ensureStarted(); LogService.start(ctx); }
    }
}
