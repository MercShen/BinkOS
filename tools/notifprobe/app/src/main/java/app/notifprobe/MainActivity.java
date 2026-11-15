package app.notifprobe;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Bundle;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final String CHANNEL_ID = "probe";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Probe", NotificationManager.IMPORTANCE_HIGH);
        nm.createNotificationChannel(ch);

        // 先清掉本 App 上一轮的通知，再用同一批 ID 重发：
        // 反复 am start 不会叠加通知，也不会因为 ID 变化而残留旧条目。
        nm.cancelAll();

        String label = BuildConfig.PROBE_LABEL;
        for (int i = 1; i <= BuildConfig.NOTIF_COUNT; i++) {
            Notification n = new Notification.Builder(this, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.sym_def_app_icon)
                    .setContentTitle(label + " 消息 " + i)
                    .setContentText("来自 " + label + " 的测试通知 #" + i)
                    .setAutoCancel(true)
                    .build();
            nm.notify(1000 + i, n);
        }
        Toast.makeText(this, "posted " + BuildConfig.NOTIF_COUNT, Toast.LENGTH_SHORT).show();
        finish();
    }
}
