# 默认暂不启用混淆，规则文件留作后续发布时补充
# Room / Retrofit / Gson 模型类在此追加 keep 规则

# ---------- 微信开放平台 SDK ----------
-keep class com.tencent.mm.opensdk.** { *; }
-keep class com.tencent.wxop.stat.** { *; }

# ---------- 阿里云号码认证 SDK ----------
-keep class com.mobile.auth.** { *; }
-keep class com.alibaba.sdk.android.** { *; }
-keep class com.ta.utdid2.** { *; }
-keep class com.nirvana.** { *; }
-keep class com.cmic.sso.sdk.** { *; }
-keep class org.json.** { *; }
-keep public class R.drawable.authsdk*
-keep public class R.layout.authsdk*
-keep public class R.anim.authsdk*
-keep public class R.id.authsdk*
-keep public class R.string.authsdk*
-keep public class R.style.authsdk*

# ---------- fastjson（阿里云 SDK 依赖） ----------
-keep class com.alibaba.fastjson.** { *; }
