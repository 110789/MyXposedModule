package com.example.myhook;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.Toast;

public class SettingsActivity extends Activity {

    private Switch swEnable;
    private Switch swSmart;
    private Switch swAntiDetect;
    private EditText etPackage, etUrl, etRules;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        setTitle("MyHook 设置");

        prefs = getSharedPreferences(HookMain.PREF_NAME, Context.MODE_PRIVATE);

        swEnable = findViewById(R.id.sw_enable);
        swSmart = findViewById(R.id.sw_smart);
        swAntiDetect = findViewById(R.id.sw_anti_detect);
        etPackage = findViewById(R.id.et_package);
        etUrl = findViewById(R.id.et_url);
        etRules = findViewById(R.id.et_rules);
        Button btnSave = findViewById(R.id.btn_save);
        Button btnExample = findViewById(R.id.btn_example);

        load();
        btnSave.setOnClickListener(v -> save());
        btnExample.setOnClickListener(v -> fillExample());
    }

    private void load() {
        swEnable.setChecked(prefs.getBoolean(HookMain.KEY_ENABLE, true));
        swSmart.setChecked(prefs.getBoolean(HookMain.KEY_SMART, true));
        swAntiDetect.setChecked(prefs.getBoolean(HookMain.KEY_ANTI_DETECT, false));
        etPackage.setText(prefs.getString(HookMain.KEY_PACKAGE, ""));
        etUrl.setText(prefs.getString(HookMain.KEY_URL, ""));
        etRules.setText(prefs.getString(HookMain.KEY_RULES, ""));
    }

    private void fillExample() {
        etPackage.setText(
                "com.YiGeTechnology.XiaoWai.business\n" +
                "com.example.anotherapp"
        );
        etUrl.setText("userInfo");
        etRules.setText(
                "# 字段名匹配已忽略大小写和下划线，isvip=true 会自动命中\n" +
                "# isVip / is_vip / isVIP / IsVip 等各种写法，不用每种都单独列\n" +
                "\n" +
                "# 通用 VIP/会员字段\n" +
                "isvip=true\n" +
                "ismember=true\n" +
                "ismembership=true\n" +
                "issvip=true\n" +
                "ispro=true\n" +
                "ispremium=true\n" +
                "ispaid=true\n" +
                "issubscribed=true\n" +
                "hasvip=true\n" +
                "vipstatus=1\n" +
                "memberstatus=1\n" +
                "usertype=1\n" +
                "viptype=1\n" +
                "viplevel=9\n" +
                "memberlevel=9\n" +
                "level=9\n" +
                "grade=9\n" +
                "\n" +
                "# 国内 App 常见的其他会员字段写法\n" +
                "vip=1\n" +
                "member=1\n" +
                "huiyuan=1\n" +
                "isyearvip=true\n" +
                "ismonthvip=true\n" +
                "isweekvip=true\n" +
                "isforever=true\n" +
                "isforevervip=true\n" +
                "islifelong=true\n" +
                "svip=true\n" +
                "svipstatus=1\n" +
                "sviplevel=9\n" +
                "goldvip=true\n" +
                "diamondvip=true\n" +
                "supervip=true\n" +
                "vipcardtype=9\n" +
                "cardtype=9\n" +
                "vipsource=1\n" +
                "vipflag=1\n" +
                "vipsign=true\n" +
                "isactive=true\n" +
                "accountstatus=1\n" +
                "paystatus=1\n" +
                "orderstatus=1\n" +
                "isautorenew=true\n" +
                "autorenew=true\n" +
                "renewstatus=1\n" +
                "\n" +
                "# 到期时间 / 有效期类字段\n" +
                "vipexpiredtime=\"2099-12-31 23:59:59\"\n" +
                "vipexpiretime=\"2099-12-31 23:59:59\"\n" +
                "expiretime=\"2099-12-31 23:59:59\"\n" +
                "expiredate=\"2099-12-31\"\n" +
                "expiredat=\"2099-12-31 23:59:59\"\n" +
                "endtime=\"2099-12-31 23:59:59\"\n" +
                "deadline=\"2099-12-31 23:59:59\"\n" +
                "validuntil=\"2099-12-31 23:59:59\"\n" +
                "memberexpiretime=\"2099-12-31 23:59:59\"\n" +
                "vipenddate=\"2099-12-31\"\n" +
                "vipdays=9999\n" +
                "memberdays=9999\n" +
                "remainingdays=9999\n" +
                "surplusdays=9999\n" +
                "\n" +
                "# 广告 / 次数限制类字段\n" +
                "showad=false\n" +
                "hasad=false\n" +
                "adfree=true\n" +
                "needad=false\n" +
                "isad=false\n" +
                "remaincount=9999\n" +
                "remaintimes=9999\n" +
                "freecount=9999\n" +
                "surpluscount=9999\n" +
                "parsevideoremain=9999\n" +
                "downloadremain=9999\n" +
                "\n" +
                "# 国外/通用订阅制常见字段\n" +
                "subscribed=true\n" +
                "issubscriptionactive=true\n" +
                "hasactivesubscription=true\n" +
                "subscriptionstatus=1\n" +
                "subscriptiontype=1\n" +
                "plantype=1\n" +
                "packagetype=1\n" +
                "accounttype=1\n" +
                "tier=1\n" +
                "entitlement=1\n" +
                "isentitled=true\n" +
                "\n" +
                "# 谨慎使用：这类字段除了控制 VIP 状态，也可能被 App 用来\n" +
                "# 判断要不要显示某些引导/营销类功能入口，强改后如果发现\n" +
                "# App 其他功能缺失，先把下面这行删掉再试\n" +
                "# isinappbuy=1\n" +
                "\n" +
                "# 旧版纯文本替换写法仍然支持（谨慎使用，容易误伤同名文本）\n" +
                "\"isVip\":false|\"isVip\":true\n" +
                "\n" +
                "# 给某条规则单独限定只在某个包名生效，用 [包名] 前缀，例如：\n" +
                "# [com.example.anotherapp] userLevel=99\n" +
                "\n" +
                "# URL 关键字支持正则，加 re: 前缀，例如：\n" +
                "# re:/api/(user|member)/info"
        );
        Toast.makeText(this, "已填入示例，请按需修改后保存", Toast.LENGTH_SHORT).show();
    }

    private void save() {
        SharedPreferences.Editor e = prefs.edit();
        e.putBoolean(HookMain.KEY_ENABLE, swEnable.isChecked());
        e.putBoolean(HookMain.KEY_SMART, swSmart.isChecked());
        e.putBoolean(HookMain.KEY_ANTI_DETECT, swAntiDetect.isChecked());
        e.putString(HookMain.KEY_PACKAGE, etPackage.getText().toString());
        e.putString(HookMain.KEY_URL, etUrl.getText().toString());
        e.putString(HookMain.KEY_RULES, etRules.getText().toString());
        boolean ok = e.commit();
        android.util.Log.e("MyHookUI", "commit() 结果=" + ok
                + " 文件路径=" + getFilesDir().getParent() + "/shared_prefs/" + HookMain.PREF_NAME + ".xml");
        if (ok) {
            String packagesText = etPackage.getText().toString();
            Toast.makeText(this, "已保存，正在自动结束目标 App 进程…", Toast.LENGTH_LONG).show();
            forceStopTargetsAsync(packagesText);
        } else {
            Toast.makeText(this, "保存失败！commit() 返回 false，请截图这条提示反馈", Toast.LENGTH_LONG).show();
        }
    }

    private void forceStopTargetsAsync(String packagesText) {
        new Thread(() -> {
            for (String raw : packagesText.split("\n")) {
                String pkg = raw.trim();
                if (pkg.isEmpty()) continue;
                try {
                    Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "am force-stop " + pkg});
                    p.waitFor();
                    android.util.Log.e("MyHookUI", "已 force-stop " + pkg);
                } catch (Throwable t) {
                    android.util.Log.e("MyHookUI", "force-stop " + pkg + " 失败：" + t);
                }
            }
        }).start();
    }
}
