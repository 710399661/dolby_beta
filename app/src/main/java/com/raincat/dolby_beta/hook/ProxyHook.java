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
                        boolean isHttps = urlStr.startsWith("https://");
                        setProxy(context, client, isHttps);
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
     * 设置代理
     *
     * @param isHttps 当前请求是否是 https:// URL;
     *                本地脚本模式下端口分流:HTTP → proxy_port(脚本 HTTP 代理),HTTPS → proxy_port+1(脚本 HTTPS MITM 直连端口)
     */
    private void setProxy(Context context, Object client, boolean isHttps) throws Exception {
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
            int port;
            if (serverMode) {
                // 服务器代理模式:用户自己设置的单端口(可能是 HTTP 代理也可能是 HTTPS 反向代理)
                port = SettingHelper.getInstance().getProxyPort();
            } else {
                // 本地脚本模式:端口与脚本 app.js -p port:port+1 对齐
                // - HTTP 请求 → HTTP 代理端口 (port): OkHttp 会发 CONNECT / 明文 GET 过去
                // - HTTPS 请求 → 脚本的 HTTPS MITM 端口 (port+1):
                //   因为 MITM 场景下 client 是和 "*.music.163.com:443" 做握手,
                //   OkHttp 的 Tunnel CONNECT + 自定义 sslSocketFactory 在 Android 上经常出现 TLS 不一致,
                //   更稳妥的做法是用 HTTP Proxy 连接到脚本 HTTPS 监听端口(Tunnel CONNECT 升级后,
                //   脚本 HTTPS server 会完成 MITM,再交给自定义 trust-all SocketFactory 放行)
                int proxyPort = SettingHelper.getInstance().getProxyPort();
                port = isHttps ? proxyPort + 1 : proxyPort;
            }

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
