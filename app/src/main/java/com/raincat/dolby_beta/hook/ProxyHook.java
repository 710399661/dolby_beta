package com.raincat.dolby_beta.hook;

import android.content.Context;
import android.os.Bundle;


import com.raincat.dolby_beta.helper.ExtraHelper;
import com.raincat.dolby_beta.helper.ScriptHelper;
import com.raincat.dolby_beta.helper.SettingHelper;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.Arrays;
import java.util.List;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLSocketFactory;


import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import static de.robv.android.xposed.XposedBridge.hookAllConstructors;
import static de.robv.android.xposed.XposedHelpers.findAndHookMethod;
import static de.robv.android.xposed.XposedHelpers.findClassIfExists;

/**
 * <pre>
 *     author : RainCat
 *     e-mail : nining377@gmail.com
 *     time   : 2021/09/08
 *     desc   : 代理
 *     version: 1.0
 * </pre>
 */

public class ProxyHook {
    private static SSLSocketFactory socketFactory;
    private static HostnameVerifier hostnameVerifier;
    private static Object objectProxy;
    private static Object objectSSLSocketFactory;
    private static Object objectHostnameVerifier;

    private String fieldSSLSocketFactory;
    private String fieldHttpUrl = "url";
    private String fieldProxy = "proxy";
    private String fieldHostnameVerifier = "hostnameVerifier";
    private final List<String> whiteUrlList = Arrays.asList("song/enhance/player/url", "song/enhance/download/url","/package");

    public ProxyHook(Context context, boolean isPlayProcess) {
        Class<?> realCallClass = findClassIfExists("okhttp3.internal.connection.RealCall", context.getClassLoader());
        if (realCallClass != null) {
            fieldSSLSocketFactory = "sslSocketFactoryOrNull";
        } else {
            realCallClass = findClassIfExists("okhttp3.RealCall", context.getClassLoader());
            if (realCallClass != null)
                fieldSSLSocketFactory = "sslSocketFactory";
            else {
                realCallClass = findClassIfExists("okhttp3.z", context.getClassLoader());
                fieldSSLSocketFactory = "o";
                fieldHttpUrl = "a";
                fieldProxy = "d";
            }
        }

        hookAllConstructors(realCallClass, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                // 兼容两种 RealCall constructor:
                // OkHttp 3.x: RealCall(OkHttpClient client, Request request, boolean forWebSocket) → args.length == 3
                // OkHttp 4.x+: RealCall(OkHttpClient client, Request request) → args.length == 2
                if (param.args.length != 3 && param.args.length != 2) return;
                Object client = param.args[0];
                Object request = param.args[1];

                Field urlField = request.getClass().getDeclaredField(fieldHttpUrl);
                urlField.setAccessible(true);
                Object urlObj = urlField.get(request);
                String urlStr = urlObj == null ? "" : urlObj.toString();
                for (String url : whiteUrlList) {
                    if (urlStr.contains(url)) {
                        setProxy(context, client);
                        break;
                    }
                }
            }
        });

        Class<?> okHttpClientBuilderClass = XposedHelpers.findClassIfExists("okhttp3.OkHttpClient$Builder", context.getClassLoader());
        if (okHttpClientBuilderClass != null) {
            XposedBridge.hookAllMethods(okHttpClientBuilderClass, "addInterceptor", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    super.beforeHookedMethod(param);
                    if (param.args[0].getClass().getName().contains("com.netease.cloudmusic.network.cronet"))
                        param.setResult(param.thisObject);
//                        XposedBridge.hookAllMethods(param.args[0].getClass(), "intercept", new XC_MethodHook() {
//                            @Override
//                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
//                                super.beforeHookedMethod(param);
//                                Object object = param.args[0];
//                                if (object != null && object.getClass().getName().contains("Chain")) {
//                                    Object request = XposedHelpers.callMethod(object, "request");
//                                    if (request.toString().contains("song/enhance/player/url") || request.toString().contains("song/enhance/download/url")) {
//                                        Object response = XposedHelpers.callMethod(object, "proceed", request);
//                                        param.setResult(response);
//                                    }
//                                }
//                            }
//                        });
                }
            });
        }

        if (!isPlayProcess) {
            ExtraHelper.setExtraDate(ExtraHelper.SCRIPT_STATUS, "0");
            if (SettingHelper.getInstance().getSetting(SettingHelper.proxy_master_key)) {
                ScriptHelper.initScript(context, false);
                if (SettingHelper.getInstance().getSetting(SettingHelper.proxy_server_key)) {
                    ScriptHelper.startHttpProxyMode(context);
                } else {
                    ScriptHelper.startScript();
                }
            }
        }
    }

    /**
     * 设置代理 (本地脚本模式 / 服务器代理模式通用)
     *
     * 致命修复说明(对应"开启音源代理就无法播放、关闭代理能播放"现象):
     *  1) UnblockNeteaseMusic 自带 server.crt 已过期(notAfter=2023-04-04),
     *     使用 ca.crt + TrustManagerFactory 的"完整链校验"在 Android 7+ BoringSSL 上会直接抛
     *     CertPathValidatorException(certificate has expired),
     *     因此在 ScriptHelper.getSSLSocketFactory 里统一使用 trust-all 的 HTTPSTrustManager。
     *  2) OkHttp 在"代理 + 自定义 sslSocketFactory"同时启用时,仍会额外做 HostnameVerifier
     *     校验(CN/SAN=*.music.163.com vs 127.0.0.1 / 服务器 IP 必然不匹配),
     *     抛出 SSLPeerUnverifiedException,因此这里同步把 OkHttpClient.hostnameVerifier
     *     替换成 allow-all。关闭代理时还原。
     *  3) 端口:
     *     - 本地脚本模式(proxy_server=false):统一用 HTTP 代理端口 proxy_port,
     *       HTTPS 请求走 CONNECT 隧道后由脚本完成 MITM。
     *     - 服务器代理模式(proxy_server=true):使用用户配置的 http_proxy + proxy_port(单端口),
     *       不做 scheme 分流。
     */
    private void setProxy(Context context, Object client) throws Exception {
        //保存正常的代理与SSL
        Field sslSocketFactoryField = client.getClass().getDeclaredField(fieldSSLSocketFactory);
        sslSocketFactoryField.setAccessible(true);
        Field proxyField = client.getClass().getDeclaredField(fieldProxy);
        proxyField.setAccessible(true);
        Field hostnameVerifierField = client.getClass().getDeclaredField(fieldHostnameVerifier);
        hostnameVerifierField.setAccessible(true);

        if (objectProxy == null)
            objectProxy = proxyField.get(client);
        if (objectSSLSocketFactory == null)
            objectSSLSocketFactory = sslSocketFactoryField.get(client);
        if (objectHostnameVerifier == null)
            objectHostnameVerifier = hostnameVerifierField.get(client);

        if (ExtraHelper.getExtraDate(ExtraHelper.SCRIPT_STATUS).equals("1")) {
            boolean serverMode = SettingHelper.getInstance().getSetting(SettingHelper.proxy_server_key);
            String httpUrlHost = serverMode ?
                    SettingHelper.getInstance().getHttpProxy() : "127.0.0.1";
            // 本地脚本模式与服务器代理模式都用单个 HTTP 代理端口;
            // CONNECT 隧道后脚本/远端服务器自行处理 HTTPS MITM / 上游转发。
            int port = SettingHelper.getInstance().getProxyPort();

            Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(httpUrlHost, port));
            proxyField.set(client, proxy);

            if (socketFactory == null)
                socketFactory = ScriptHelper.getSSLSocketFactory(context);
            if (hostnameVerifier == null)
                hostnameVerifier = (hostname, session) -> true;

            if (socketFactory != null)
                sslSocketFactoryField.set(client, socketFactory);
            hostnameVerifierField.set(client, hostnameVerifier);
        } else {
            proxyField.set(client, objectProxy);
            sslSocketFactoryField.set(client, objectSSLSocketFactory);
            hostnameVerifierField.set(client, objectHostnameVerifier);
        }
    }
}
