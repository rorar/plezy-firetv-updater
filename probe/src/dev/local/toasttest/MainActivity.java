package dev.local.toasttest;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.TextView;

/** Fires both probes on launch, and shows the adb commands for repeat runs. */
public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        TextView tv = new TextView(this);
        tv.setText("ToastTest probe\n\n"
                + "adb shell am broadcast -n dev.local.toasttest/.TestReceiver --es what toast\n\n"
                + "adb shell am broadcast -n dev.local.toasttest/.TestReceiver --es what notif\n\n"
                + "(both fired once on this launch)");
        tv.setTextColor(Color.WHITE);
        tv.setGravity(Gravity.CENTER);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        setContentView(tv);

        TestReceiver.showToast(this, "TOAST from third-party app (launch)");
        TestReceiver.postNotification(this, "ToastTest",
                "Notification from third-party app (launch)");
    }
}
