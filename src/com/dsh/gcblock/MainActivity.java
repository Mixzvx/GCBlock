package com.dsh.gcblock;

import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 桌面入口 + 自检页。
 *
 * 存在的意义：LSPosed 的模块列表完全靠 PackageManager 里的 meta-data 判断，
 * 如果它看不到本模块，打开这个页面就能看到「系统实际读到的 meta-data 是什么类型」，
 * 从而判断问题出在模块包本身，还是 LSPosed 的缓存/版本。
 */
public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView tv = new TextView(this);
        tv.setTextSize(13f);
        tv.setPadding(48, 48, 48, 48);
        tv.setTextIsSelectable(true);
        tv.setText(buildReport());
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        setContentView(sv);
    }

    private String buildReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("GCBlock\n");
        sb.append("=============\n\n");

        sb.append("包名: ").append(getPackageName()).append('\n');
        sb.append("系统: Android ").append(Build.VERSION.RELEASE)
          .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("机型: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        sb.append("架构: ").append(Build.CPU_ABI).append('\n');

        try {
            PackageInfo pi = getPackageManager()
                    .getPackageInfo(getPackageName(), PackageManager.GET_META_DATA);
            sb.append("版本: ").append(pi.versionName)
              .append(" (").append(pi.versionCode).append(")\n\n");
            sb.append("—— meta-data（LSPosed 就是读这里）——\n");
            ApplicationInfo ai = pi.applicationInfo;
            Bundle meta = ai == null ? null : ai.metaData;
            if (meta == null) {
                sb.append("metaData = null  ← 异常，LSPosed 不会显示本模块\n");
            } else if (meta.isEmpty()) {
                sb.append("metaData 为空  ← 异常\n");
            } else {
                for (String key : meta.keySet()) {
                    Object v = meta.get(key);
                    sb.append("• ").append(key).append(" = ").append(String.valueOf(v))
                      .append("  [").append(v == null ? "null" : v.getClass().getSimpleName())
                      .append("]\n");
                }
                sb.append('\n');
                sb.append(check(meta, "xposedmodule", "Boolean") );
                sb.append(check(meta, "xposedminversion", "Integer"));
            }
        } catch (Throwable t) {
            sb.append("读取自身包信息失败: ").append(t).append('\n');
        }

        sb.append("\n—— 怎么用 ——\n");
        sb.append("1. LSPosed 管理器 → 模块 → 勾选 GCBlock\n");
        sb.append("2. 作用域只勾「游戏管家」com.hihonor.gameassistant\n");
        sb.append("3. 强行停止游戏管家，再打开它\n");
        sb.append("4. 看日志: adb logcat -s GCBlock\n");
        sb.append("\n如果上面 meta-data 正常，但 LSPosed 里还是没有：\n");
        sb.append("• 关掉 LSPosed 管理器进程后重开（列表有缓存）\n");
        sb.append("• 或重启手机\n");
        sb.append("• 仍不行请把这个页面截图发我\n");
        return sb.toString();
    }

    private String check(Bundle meta, String key, String want) {
        Object v = meta.get(key);
        if (v == null) {
            return "⚠ " + key + " 缺失\n";
        }
        String type = v.getClass().getSimpleName();
        if (want.equals(type)) {
            return "✓ " + key + " 类型正确 (" + type + ")\n";
        }
        return "⚠ " + key + " 类型是 " + type + "，应为 " + want + "\n";
    }
}
