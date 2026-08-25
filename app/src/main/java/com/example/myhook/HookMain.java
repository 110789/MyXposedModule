package com.example.myhook;

import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class HookMain implements IXposedHookLoadPackage, IXposedHookZygoteInit {

    public static final String PREF_NAME = "myhook_config";
    public static final String KEY_ENABLE = "enable";
    public static final String KEY_PACKAGE = "target_package";
    public static final String KEY_URL = "url_keyword";
    public static final String KEY_RULES = "replace_rules";
    public static final String KEY_SMART = "smart_mode";
    public static final String KEY_ANTI_DETECT = "anti_detect";

    private static final long MAX_BODY_BYTES = 3L * 1024 * 1024;
    private static final long RELOAD_INTERVAL_MS = 2000;

    private static final Set<String> SMART_STATUS_KEYS = new HashSet<>(Arrays.asList(
            "isvip", "vip", "ismember", "member", "vipstatus", "issvip", "svip", "ispro",
            "ispremium", "hasvip", "issubscribed", "subscribed", "isactive", "ispaid",
            "isforevervip", "isyearvip", "ismonthvip", "isweekvip", "islifelong", "isforever",
            "autorenew", "isautorenew", "vipsign", "vipflag", "ispay", "haspay", "paiduser",
            "ispaiduser", "proaccount", "isproaccount", "premiumaccount", "ispremiumaccount",
            "goldmember", "diamondmember", "supermember", "supervip", "memberflag",
            "会员", "是否会员", "超级会员", "高级会员", "会员状态", "订阅状态", "是否订阅",
            "开通会员", "已开通", "是否vip", "vip状态", "是否开通", "已购买", "是否付费",
            "会员标识", "会员权限", "vip权限", "是否高级用户", "是否超级用户", "开通状态",
            "续费状态", "vip有效", "会员有效", "有效会员"
    ));

    private static final Set<String> SMART_LEVEL_KEYS = new HashSet<>(Arrays.asList(
            "viplevel", "viplvl", "memberlevel", "sviplevel", "vipcardtype", "cardtype", "viptype",
            "会员等级", "vip等级", "会员级别"
    ));

    private static final Set<String> SMART_EXPIRE_KEYS = new HashSet<>(Arrays.asList(
            "vipexpiredtime", "vipexpiretime", "expiretime", "expiredate", "expiredat",
            "endtime", "deadline", "validuntil", "memberexpiretime", "vipenddate",
            "subscriptionenddate", "subscriptionexpiry", "expiresat", "expiry", "validthru",
            "validto", "enddate",
            "到期时间", "会员到期时间", "过期时间", "有效期", "有效期至", "截止时间",
            "会员截止时间", "会员到期日", "vip到期日", "截止日期"
    ));

    private static final Set<String> SMART_REMAIN_KEYS = new HashSet<>(Arrays.asList(
            "remaincount", "remaintimes", "freecount", "surpluscount", "parsevideoremain",
            "downloadremain", "vipdays", "memberdays", "remainingdays", "surplusdays",
            "usageremain", "creditsremain", "quotaremain", "freetrialremain",
            "剩余次数", "剩余天数", "解析次数", "可用次数", "试用剩余", "剩余解析次数", "剩余下载次数",
            "可用解析次数"
    ));

    private static XSharedPreferences prefs;
    private static final AtomicBoolean hooked = new AtomicBoolean(false);
    private static volatile XC_MethodHook.Unhook loadClassUnhook;

    private static volatile long lastReload = 0;
    private static volatile boolean cachedEnable = true;
    private static volatile boolean cachedSmartMode = false;
    private static volatile Set<String> cachedKeywords = new HashSet<>();
    private static volatile List<Rule> cachedRules = new ArrayList<>();

    private enum RuleType { TEXT, JSON }

    private static class Rule {
        String pkgScope;
        RuleType type;
        String from;
        String to;
        String jsonKey;
        String jsonValueLiteral;
    }

    @Override
    public void initZygote(StartupParam startupParam) {
        prefs = new XSharedPreferences("com.example.myhook", PREF_NAME);
        prefs.makeWorldReadable();
    }

    private static XSharedPreferences getRawPrefs() {
        if (prefs == null) {
            prefs = new XSharedPreferences("com.example.myhook", PREF_NAME);
        }
        return prefs;
    }

    private static Set<String> parseLines(String text) {
        Set<String> set = new HashSet<>();
        if (TextUtils.isEmpty(text)) return set;
        for (String line : text.split("\n")) {
            line = line.trim();
            if (!line.isEmpty()) set.add(line);
        }
        return set;
    }

    private static List<Rule> parseRules(String text) {
        List<Rule> list = new ArrayList<>();
        if (TextUtils.isEmpty(text)) return list;
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;

            String pkgScope = null;
            if (line.startsWith("[")) {
                int end = line.indexOf(']');
                if (end > 0) {
                    pkgScope = line.substring(1, end).trim();
                    line = line.substring(end + 1).trim();
                }
            }
            if (line.isEmpty()) continue;

            int pipeIdx = line.indexOf('|');
            int eqIdx = line.indexOf('=');

            Rule r = new Rule();
            r.pkgScope = pkgScope;

            if (pipeIdx >= 0) {
                r.type = RuleType.TEXT;
                r.from = line.substring(0, pipeIdx);
                r.to = line.substring(pipeIdx + 1);
            } else if (eqIdx > 0) {
                r.type = RuleType.JSON;
                r.jsonKey = line.substring(0, eqIdx).trim();
                r.jsonValueLiteral = line.substring(eqIdx + 1);
            } else {
                continue;
            }
            list.add(r);
        }
        return list;
    }

    private static void reloadIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - lastReload < RELOAD_INTERVAL_MS) return;
        synchronized (HookMain.class) {
            if (System.currentTimeMillis() - lastReload < RELOAD_INTERVAL_MS) return;
            XSharedPreferences p = getRawPrefs();
            p.reload();
            cachedEnable = p.getBoolean(KEY_ENABLE, true);
            cachedSmartMode = p.getBoolean(KEY_SMART, true);
            cachedKeywords = parseLines(p.getString(KEY_URL, ""));
            cachedRules = parseRules(p.getString(KEY_RULES, ""));
            lastReload = System.currentTimeMillis();
        }
    }

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) {
        XSharedPreferences p = getRawPrefs();
        p.reload();
        if (!p.getBoolean(KEY_ENABLE, true)) return;

        final String packageName = lpparam.packageName;
        XposedBridge.log("MyHook: LSPosed 已注入 " + packageName + "（作用域已勾选，自动生效，无需在设置里重复配置包名）");

        if (p.getBoolean(KEY_ANTI_DETECT, false)) {
            applyAntiDetect(lpparam.classLoader, packageName);
        }

        try {
            loadClassUnhook = XposedHelpers.findAndHookMethod(
                    ClassLoader.class,
                    "loadClass",
                    String.class,
                    boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (hooked.get()) return;
                            String name = (String) param.args[0];
                            if (name != null && name.startsWith("okhttp3.")) {
                                tryHook((ClassLoader) param.thisObject, packageName);
                            }
                        }
                    }
            );
        } catch (Throwable t) {
            XposedBridge.log("MyHook: Hook ClassLoader 失败 " + t);
        }

        new Thread(() -> {
            for (int i = 0; i < 12 && !hooked.get(); i++) {
                try {
                    Thread.sleep(500);
                    tryHook(lpparam.classLoader, packageName);
                } catch (Throwable ignored) {}
            }
        }).start();
    }

    private static final Set<String> ANTI_DETECT_PKG_BLACKLIST = new HashSet<>(Arrays.asList(
            "de.robv.android.xposed.installer",
            "org.meowcat.edxposed.manager",
            "com.solohsu.android.edxp.manager",
            "org.lsposed.manager",
            "io.va.exposed",
            "com.example.myhook"
    ));

    private static final Set<String> ANTI_DETECT_PATH_KEYWORDS = new HashSet<>(Arrays.asList(
            "xposedbridge.jar",
            "xposed.prop",
            "/data/adb/lspd",
            "/data/adb/modules/",
            "app_process_xposed",
            "/data/data/de.robv.android.xposed.installer"
    ));

    private void applyAntiDetect(ClassLoader cl, String packageName) {
        try {
            XposedHelpers.findAndHookMethod(Throwable.class, "getStackTrace", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object result = param.getResult();
                        if (!(result instanceof StackTraceElement[])) return;
                        StackTraceElement[] orig = (StackTraceElement[]) result;
                        List<StackTraceElement> filtered = new ArrayList<>();
                        boolean changed = false;
                        for (StackTraceElement el : orig) {
                            String cn = el.getClassName();
                            if (cn.startsWith("de.robv.android.xposed")
                                    || cn.startsWith("org.lsposed")
                                    || cn.startsWith("com.example.myhook")
                                    || cn.contains("XposedBridge")) {
                                changed = true;
                                continue;
                            }
                            filtered.add(el);
                        }
                        if (changed) {
                            param.setResult(filtered.toArray(new StackTraceElement[0]));
                        }
                    } catch (Throwable ignored) {}
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("MyHook: 反检测 Hook 堆栈失败 " + t);
        }

        try {
            Class<?> fileClz = XposedHelpers.findClass("java.io.File", cl);
            XC_MethodHook fileHook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object thiz = param.thisObject;
                        String path = (String) XposedHelpers.callMethod(thiz, "getAbsolutePath");
                        if (path == null) return;
                        String lower = path.toLowerCase(Locale.ROOT);
                        for (String kw : ANTI_DETECT_PATH_KEYWORDS) {
                            if (lower.contains(kw)) {
                                param.setResult(Boolean.FALSE);
                                return;
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            };
            XposedHelpers.findAndHookMethod(fileClz, "exists", fileHook);
            XposedHelpers.findAndHookMethod(fileClz, "canRead", fileHook);
        } catch (Throwable t) {
            XposedBridge.log("MyHook: 反检测 Hook 文件检查失败 " + t);
        }

        try {
            Class<?> pmClz = XposedHelpers.findClass("android.app.ApplicationPackageManager", cl);
            XposedHelpers.findAndHookMethod(pmClz, "getInstalledApplications", int.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    filterAppList(param);
                }
            });
            XposedHelpers.findAndHookMethod(pmClz, "getInstalledPackages", int.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    filterAppList(param);
                }
            });
            XposedHelpers.findAndHookMethod(pmClz, "getPackageInfo", String.class, int.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Object pkg = param.args[0];
                    if (pkg instanceof String && ANTI_DETECT_PKG_BLACKLIST.contains(pkg)) {
                        param.setThrowable(new android.content.pm.PackageManager.NameNotFoundException((String) pkg));
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("MyHook: 反检测 Hook PackageManager 失败 " + t);
        }

        XposedBridge.log("MyHook: 反检测已启用 " + packageName);
    }

    @SuppressWarnings("unchecked")
    private static void filterAppList(XC_MethodHook.MethodHookParam param) {
        try {
            Object result = param.getResult();
            if (!(result instanceof List)) return;
            List<Object> list = (List<Object>) result;
            Iterator<Object> it = list.iterator();
            while (it.hasNext()) {
                Object entry = it.next();
                String pkgName = null;
                try {
                    Object direct = XposedHelpers.getObjectField(entry, "packageName");
                    if (direct instanceof String) pkgName = (String) direct;
                } catch (Throwable ignored) {}
                if (pkgName == null) {
                    try {
                        Object appInfo = XposedHelpers.getObjectField(entry, "applicationInfo");
                        pkgName = (String) XposedHelpers.getObjectField(appInfo, "packageName");
                    } catch (Throwable ignored) {}
                }
                if (pkgName != null && ANTI_DETECT_PKG_BLACKLIST.contains(pkgName)) {
                    it.remove();
                }
            }
        } catch (Throwable ignored) {}
    }

    private void tryHook(ClassLoader cl, String packageName) {
        if (hooked.get()) return;
        if (tryHookNewCall(cl, packageName)) {
            markHooked("okhttp3.OkHttpClient#newCall");
            return;
        }
        String[] candidates = {
                "okhttp3.internal.connection.RealCall",
                "okhttp3.RealCall"
        };
        for (String className : candidates) {
            try {
                Class<?> callClz = XposedHelpers.findClass(className, cl);
                hookRealCall(callClz, cl, packageName);
                markHooked(className);
                return;
            } catch (Throwable ignored) {}
        }
    }

    private void markHooked(String where) {
        hooked.set(true);
        XposedBridge.log("MyHook: 成功 Hook " + where);
        XC_MethodHook.Unhook u = loadClassUnhook;
        if (u != null) {
            u.unhook();
            loadClassUnhook = null;
        }
    }

    private boolean tryHookNewCall(ClassLoader cl, String packageName) {
        try {
            Class<?> okHttpClientClz = XposedHelpers.findClass("okhttp3.OkHttpClient", cl);
            Class<?> requestClz = XposedHelpers.findClass("okhttp3.Request", cl);
            Class<?> callClz = XposedHelpers.findClass("okhttp3.Call", cl);
            Class<?> callbackClz = XposedHelpers.findClass("okhttp3.Callback", cl);

            XposedHelpers.findAndHookMethod(okHttpClientClz, "newCall", requestClz, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (param.hasThrowable()) return;
                        final Object realCall = param.getResult();
                        if (realCall == null) return;
                        InvocationHandler handler = (proxy, method, args) -> {
                            String name = method.getName();
                            if ("execute".equals(name) && (args == null || args.length == 0)) {
                                Object response = method.invoke(realCall);
                                Object modified;
                                try {
                                    modified = maybeModifyResponse(response, packageName, cl);
                                } catch (Throwable t) {
                                    modified = null;
                                }
                                return modified != null ? modified : response;
                            }
                            if ("enqueue".equals(name) && args != null && args.length == 1 && args[0] != null) {
                                final Object originalCallback = args[0];
                                InvocationHandler cbHandler = (cp, cm, cargs) -> {
                                    if ("onResponse".equals(cm.getName()) && cargs != null && cargs.length == 2) {
                                        Object response = cargs[1];
                                        Object modified;
                                        try {
                                            modified = maybeModifyResponse(response, packageName, cl);
                                        } catch (Throwable t) {
                                            modified = null;
                                        }
                                        return cm.invoke(originalCallback, cargs[0], modified != null ? modified : response);
                                    }
                                    return cm.invoke(originalCallback, cargs);
                                };
                                Object cbProxy = Proxy.newProxyInstance(cl, new Class<?>[]{callbackClz}, cbHandler);
                                return method.invoke(realCall, cbProxy);
                            }
                            return method.invoke(realCall, args);
                        };
                        Object callProxy = Proxy.newProxyInstance(cl, new Class<?>[]{callClz}, handler);
                        param.setResult(callProxy);
                    } catch (Throwable t) {
                        XposedBridge.log("MyHook: newCall 处理异常 " + t);
                    }
                }
            });
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private void hookRealCall(Class<?> callClz, ClassLoader cl, String packageName) {
        try {
            XposedHelpers.findAndHookMethod(callClz, "execute", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (param.hasThrowable()) return;
                        Object response = param.getResult();
                        Object modified = maybeModifyResponse(response, packageName, cl);
                        if (modified != null && modified != response) {
                            param.setResult(modified);
                        }
                    } catch (Throwable t) {
                        XposedBridge.log("MyHook: execute 处理异常 " + t);
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("MyHook: Hook execute 失败 " + t);
        }

        try {
            Class<?> callbackClz = XposedHelpers.findClass("okhttp3.Callback", cl);
            XposedHelpers.findAndHookMethod(callClz, "enqueue", callbackClz, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        final Object original = param.args[0];
                        if (original == null) return;
                        InvocationHandler handler = (proxy, method, args) -> {
                            if ("onResponse".equals(method.getName()) && args != null && args.length == 2) {
                                Object response = args[1];
                                Object modified;
                                try {
                                    modified = maybeModifyResponse(response, packageName, cl);
                                } catch (Throwable t) {
                                    modified = null;
                                }
                                return method.invoke(original, args[0], modified != null ? modified : response);
                            }
                            return method.invoke(original, args);
                        };
                        param.args[0] = Proxy.newProxyInstance(cl, new Class<?>[]{callbackClz}, handler);
                    } catch (Throwable t) {
                        XposedBridge.log("MyHook: enqueue 处理异常 " + t);
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("MyHook: Hook enqueue 失败 " + t);
        }
    }

    private Object maybeModifyResponse(Object response, String packageName, ClassLoader cl) throws Exception {
        if (response == null) return null;
        reloadIfNeeded();
        if (!cachedEnable) return null;

        Object body = XposedHelpers.callMethod(response, "body");
        if (body == null) return null;

        Object mediaType = XposedHelpers.callMethod(body, "contentType");
        if (mediaType != null) {
            String type = safeToLower(XposedHelpers.callMethod(mediaType, "type"));
            if ("image".equals(type) || "video".equals(type) || "audio".equals(type) || "font".equals(type)) return null;
            String subtype = safeToLower(XposedHelpers.callMethod(mediaType, "subtype"));
            if (subtype != null && subtype.contains("octet-stream")) return null;
        }

        long len = -1;
        try {
            Object lenObj = XposedHelpers.callMethod(body, "contentLength");
            if (lenObj instanceof Long) len = (Long) lenObj;
        } catch (Throwable ignored) {}
        if (len > MAX_BODY_BYTES) return null;

        String url;
        try {
            Object request = XposedHelpers.callMethod(response, "request");
            Object httpUrl = XposedHelpers.callMethod(request, "url");
            url = String.valueOf(httpUrl);
        } catch (Throwable t) {
            url = "";
        }

        if (!cachedKeywords.isEmpty()) {
            boolean hit = false;
            String urlLower = url.toLowerCase(Locale.ROOT);
            for (String kw : cachedKeywords) {
                String pattern = kw.startsWith("re:") ? kw.substring(3) : kw;
                if (urlLower.contains(kw.toLowerCase(Locale.ROOT))) {
                    hit = true;
                    break;
                }
                try {
                    if (java.util.regex.Pattern.compile(pattern, java.util.regex.Pattern.CASE_INSENSITIVE).matcher(url).find()) {
                        hit = true;
                        break;
                    }
                } catch (Throwable ignored) {}
            }
            if (!hit) return null;
        }

        List<Rule> applicable = new ArrayList<>();
        for (Rule r : cachedRules) {
            if (r.pkgScope == null || r.pkgScope.equals(packageName)) applicable.add(r);
        }
        if (applicable.isEmpty() && !cachedSmartMode) return null;

        String original;
        try {
            original = (String) XposedHelpers.callMethod(body, "string");
        } catch (Throwable t) {
            return null;
        }
        if (TextUtils.isEmpty(original)) return null;

        String modified = applyRules(applicable, original, cachedSmartMode);
        if (modified.equals(original)) return null;

        XposedBridge.log("MyHook: 命中并修改响应 " + url);

        Object newBody;
        try {
            Class<?> responseBodyClz = XposedHelpers.findClass("okhttp3.ResponseBody", cl);
            Class<?> mediaTypeClz = XposedHelpers.findClass("okhttp3.MediaType", cl);
            Method createMethod = responseBodyClz.getMethod("create", mediaTypeClz, String.class);
            newBody = createMethod.invoke(null, mediaType, modified);
        } catch (Throwable t) {
            XposedBridge.log("MyHook: 构造 ResponseBody 失败 " + t);
            return null;
        }

        Object builder = XposedHelpers.callMethod(response, "newBuilder");
        XposedHelpers.callMethod(builder, "body", newBody);
        return XposedHelpers.callMethod(builder, "build");
    }

    private static String safeToLower(Object o) {
        return o == null ? null : String.valueOf(o).toLowerCase(Locale.ROOT);
    }

    private static String applyRules(List<Rule> rules, String body, boolean smartMode) {
        List<Rule> jsonRules = new ArrayList<>();
        List<Rule> textRules = new ArrayList<>();
        for (Rule r : rules) {
            if (r.type == RuleType.JSON) jsonRules.add(r); else textRules.add(r);
        }

        String result = body;
        if (!jsonRules.isEmpty() || smartMode) {
            String trimmed = result.trim();
            try {
                Object root = null;
                if (trimmed.startsWith("{")) root = new JSONObject(trimmed);
                else if (trimmed.startsWith("[")) root = new JSONArray(trimmed);
                if (root != null) {
                    int hits = 0;
                    for (Rule r : jsonRules) {
                        hits += applyJsonKey(root, r.jsonKey, parseJsonLiteral(r.jsonValueLiteral));
                    }
                    if (smartMode) {
                        hits += applySmartScan(root);
                    }
                    if (hits > 0) result = root.toString();
                }
            } catch (JSONException ignored) {}
        }

        for (Rule r : textRules) {
            if (r.from != null && !r.from.isEmpty()) {
                result = result.replace(r.from, r.to == null ? "" : r.to);
            }
        }
        return result;
    }

    private static String normalizeKey(String s) {
        if (s == null) return "";
        return s.replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
    }

    private static int applyJsonKey(Object node, String key, Object value) {
        if (key != null && key.indexOf('.') >= 0) {
            return applyJsonPath(node, key.split("\\."), 0, value);
        }
        int count = 0;
        String normKey = normalizeKey(key);
        if (node instanceof JSONObject) {
            JSONObject obj = (JSONObject) node;
            List<String> keys = new ArrayList<>();
            Iterator<String> it = obj.keys();
            while (it.hasNext()) keys.add(it.next());
            for (String k : keys) {
                Object v = obj.opt(k);
                if (normalizeKey(k).equals(normKey)) {
                    try { obj.put(k, value); } catch (JSONException ignored) {}
                    count++;
                } else if (v instanceof JSONObject || v instanceof JSONArray) {
                    count += applyJsonKey(v, key, value);
                } else if (v instanceof String) {
                    Object nested = tryParseNestedJson((String) v);
                    if (nested != null) {
                        int hits = applyJsonKey(nested, key, value);
                        if (hits > 0) {
                            try { obj.put(k, nested.toString()); count += hits; } catch (JSONException ignored) {}
                        }
                    }
                }
            }
        } else if (node instanceof JSONArray) {
            JSONArray arr = (JSONArray) node;
            for (int i = 0; i < arr.length(); i++) {
                Object v = arr.opt(i);
                if (v instanceof JSONObject || v instanceof JSONArray) {
                    count += applyJsonKey(v, key, value);
                } else if (v instanceof String) {
                    Object nested = tryParseNestedJson((String) v);
                    if (nested != null) {
                        int hits = applyJsonKey(nested, key, value);
                        if (hits > 0) {
                            try { arr.put(i, nested.toString()); count += hits; } catch (JSONException ignored) {}
                        }
                    }
                }
            }
        }
        return count;
    }

    private static int applyJsonPath(Object node, String[] path, int idx, Object value) {
        if (node == null || idx >= path.length) return 0;
        String segment = path[idx];
        boolean last = idx == path.length - 1;
        String normSegment = normalizeKey(segment);

        if (node instanceof JSONArray) {
            JSONArray arr = (JSONArray) node;
            int count = 0;
            for (int i = 0; i < arr.length(); i++) {
                count += applyJsonPath(arr.opt(i), path, idx, value);
            }
            return count;
        }
        if (!(node instanceof JSONObject)) return 0;

        JSONObject obj = (JSONObject) node;
        List<String> keys = new ArrayList<>();
        Iterator<String> it = obj.keys();
        while (it.hasNext()) keys.add(it.next());

        int count = 0;
        for (String k : keys) {
            if (!normalizeKey(k).equals(normSegment)) continue;
            if (last) {
                try { obj.put(k, value); count++; } catch (JSONException ignored) {}
                continue;
            }
            Object v = obj.opt(k);
            if (v instanceof JSONObject || v instanceof JSONArray) {
                count += applyJsonPath(v, path, idx + 1, value);
            } else if (v instanceof String) {
                Object nested = tryParseNestedJson((String) v);
                if (nested != null) {
                    int hits = applyJsonPath(nested, path, idx + 1, value);
                    if (hits > 0) {
                        try { obj.put(k, nested.toString()); count += hits; } catch (JSONException ignored) {}
                    }
                }
            }
        }
        return count;
    }

    private static int applySmartScan(Object node) {
        int count = 0;
        if (node instanceof JSONObject) {
            JSONObject obj = (JSONObject) node;
            List<String> keys = new ArrayList<>();
            Iterator<String> it = obj.keys();
            while (it.hasNext()) keys.add(it.next());
            for (String k : keys) {
                Object v = obj.opt(k);
                String nk = normalizeKey(k);
                if (SMART_STATUS_KEYS.contains(nk)) {
                    if (looksFalsy(v)) {
                        try { obj.put(k, flipStatusValue(v)); count++; } catch (JSONException ignored) {}
                    }
                } else if (SMART_LEVEL_KEYS.contains(nk)) {
                    Object bumped = bumpNumberLike(v, 99);
                    if (bumped != null) { try { obj.put(k, bumped); count++; } catch (JSONException ignored) {} }
                } else if (SMART_EXPIRE_KEYS.contains(nk)) {
                    Object future = futureDateLike(v);
                    if (future != null) { try { obj.put(k, future); count++; } catch (JSONException ignored) {} }
                } else if (SMART_REMAIN_KEYS.contains(nk)) {
                    Object bumped = bumpNumberLike(v, 9999);
                    if (bumped != null) { try { obj.put(k, bumped); count++; } catch (JSONException ignored) {} }
                }
                if (v instanceof JSONObject || v instanceof JSONArray) {
                    count += applySmartScan(v);
                } else if (v instanceof String) {
                    Object nested = tryParseNestedJson((String) v);
                    if (nested != null) {
                        int hits = applySmartScan(nested);
                        if (hits > 0) {
                            try { obj.put(k, nested.toString()); count += hits; } catch (JSONException ignored) {}
                        }
                    }
                }
            }
        } else if (node instanceof JSONArray) {
            JSONArray arr = (JSONArray) node;
            for (int i = 0; i < arr.length(); i++) {
                Object v = arr.opt(i);
                if (v instanceof JSONObject || v instanceof JSONArray) {
                    count += applySmartScan(v);
                } else if (v instanceof String) {
                    Object nested = tryParseNestedJson((String) v);
                    if (nested != null) {
                        int hits = applySmartScan(nested);
                        if (hits > 0) {
                            try { arr.put(i, nested.toString()); count += hits; } catch (JSONException ignored) {}
                        }
                    }
                }
            }
        }
        return count;
    }

    private static boolean looksFalsy(Object v) {
        if (v == null || v == JSONObject.NULL) return true;
        if (v instanceof Boolean) return !((Boolean) v);
        if (v instanceof Integer) return (Integer) v == 0;
        if (v instanceof Long) return (Long) v == 0L;
        if (v instanceof Double) return (Double) v == 0.0;
        if (v instanceof String) {
            String s = ((String) v).trim();
            return s.isEmpty() || s.equals("0") || s.equalsIgnoreCase("false") || s.equalsIgnoreCase("null");
        }
        return false;
    }

    private static Object flipStatusValue(Object orig) {
        if (orig instanceof Boolean) return Boolean.TRUE;
        if (orig instanceof Integer) return 1;
        if (orig instanceof Long) return 1L;
        if (orig instanceof Double) return 1.0;
        if (orig instanceof String) {
            String s = (String) orig;
            if (s.equalsIgnoreCase("false")) return "true";
            return "1";
        }
        return Boolean.TRUE;
    }

    private static Object bumpNumberLike(Object orig, long target) {
        if (orig instanceof Integer) return (int) target;
        if (orig instanceof Long) return target;
        if (orig instanceof Double) return (double) target;
        if (orig instanceof String) {
            String s = ((String) orig).trim();
            if (s.matches("-?\\d+")) return String.valueOf(target);
        }
        return null;
    }

    private static Object futureDateLike(Object orig) {
        if (orig instanceof String) {
            String s = ((String) orig).trim();
            if (s.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}")) return "2099-12-31 23:59:59";
            if (s.matches("\\d{4}-\\d{2}-\\d{2}")) return "2099-12-31";
            if (s.matches("\\d{4}/\\d{2}/\\d{2} \\d{2}:\\d{2}:\\d{2}")) return "2099/12/31 23:59:59";
            if (s.matches("\\d{4}/\\d{2}/\\d{2}")) return "2099/12/31";
            if (s.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}.*")) return "2099-12-31T23:59:59";
            if (s.matches("\\d{13}")) return "4102444800000";
            if (s.matches("\\d{10}")) return "4102444800";
            if (s.isEmpty() || s.equals("0") || s.equalsIgnoreCase("null")) return "2099-12-31 23:59:59";
            return null;
        }
        if (orig instanceof Long) {
            long l = (Long) orig;
            if (l == 0) return 4102444800L;
            String digits = String.valueOf(Math.abs(l));
            return digits.length() >= 12 ? 4102444800000L : 4102444800L;
        }
        if (orig instanceof Integer) {
            int i = (Integer) orig;
            return i == 0 ? 4102444800L : null;
        }
        if (orig == null || orig == JSONObject.NULL) return "2099-12-31 23:59:59";
        return null;
    }

    private static Object tryParseNestedJson(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.length() < 2) return null;
        char first = t.charAt(0);
        if (first != '{' && first != '[') return null;
        try {
            return first == '{' ? new JSONObject(t) : new JSONArray(t);
        } catch (JSONException e) {
            return null;
        }
    }

    private static Object parseJsonLiteral(String raw) {
        String v = raw.trim();
        if (v.equalsIgnoreCase("true")) return Boolean.TRUE;
        if (v.equalsIgnoreCase("false")) return Boolean.FALSE;
        if (v.equalsIgnoreCase("null")) return JSONObject.NULL;
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
            return v.substring(1, v.length() - 1);
        }
        try { return Long.parseLong(v); } catch (NumberFormatException ignored) {}
        try { return Double.parseDouble(v); } catch (NumberFormatException ignored) {}
        return v;
    }
}
