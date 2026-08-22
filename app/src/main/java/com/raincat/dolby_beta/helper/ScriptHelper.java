package com.raincat.dolby_beta.helper;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;

import com.raincat.dolby_beta.BuildConfig;
import com.raincat.dolby_beta.Hook;
import com.raincat.dolby_beta.net.HTTPSTrustManager;
import com.raincat.dolby_beta.utils.Tools;
import com.stericson.RootShell.execution.Command;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;

/**
 * <pre>
 *     author : RainCat
 *     e-mail : nining377@gmail.com
 *     time   : 2021/09/26
 *     desc   : 脚本帮助类
 *     version: 1.0
 * </pre>
 */

public class ScriptHelper {
    //模块路径
    public static String modulePath;
    //脚本路径
    private static String scriptPath;
    //node 动态库所在目录列表(实际生效使用 LD_LIBRARY_PATH,不是 PATH)
    private static String nodeLibPath;
    //node 可执行文件 PATH(把 libnode.so 所在目录也加入 PATH,方便直接用 libnode.so 当命令)
    private static String nodeBinPath;

    private static final String[] STOP_PROXY = new String[]{"killall -9 libnode.so >/dev/null 2>&1 || true"};

    @SuppressLint("StaticFieldLeak")
    private static Context neteaseContext;

    private static String getScriptPath(Context context) {
        if (TextUtils.isEmpty(scriptPath))
            scriptPath = context.getFilesDir().getAbsolutePath() + "/script";
        return scriptPath;
    }

    /**
     * 初始化脚本
     *
     * @param cover 是否覆盖
     */
    public static void initScript(Context context, boolean cover) {
        File unblockFile = new File(getScriptPath(context));
        neteaseContext = context;
        if (cover || !unblockFile.exists() || !(BuildConfig.VERSION_CODE + "").equals(ExtraHelper.getExtraDate(ExtraHelper.APP_VERSION))) {
            if (FileHelper.unzipFile(modulePath, getScriptPath(context), "assets", "UnblockNeteaseMusic.zip")) {
                FileHelper.unzipFiles(getScriptPath(context) + "/UnblockNeteaseMusic.zip", getScriptPath(context));
            }
            Command auth = new Command(0, "cd " + getScriptPath(context), "chmod 0777 *");
            Tools.shell(auth);
            ExtraHelper.setExtraDate(ExtraHelper.APP_VERSION, BuildConfig.VERSION_CODE);
        }
        if (TextUtils.isEmpty(nodeLibPath) || TextUtils.isEmpty(nodeBinPath)) {
            String base = TextUtils.isEmpty(modulePath) ? "" : modulePath.substring(0, modulePath.lastIndexOf('/'));
            // libnode.so 是 .so,Android 动态链接器找 .so 依赖的是 LD_LIBRARY_PATH,不是 PATH
            String ld1 = base + "/lib/arm64";
            String ld2 = modulePath + "!/lib/arm64-v8a";
            String ld3 = context.getApplicationInfo().nativeLibraryDir;
            nodeLibPath = "export LD_LIBRARY_PATH=\"" + ld1 + ":" + ld2 + ":" + ld3 + ":$LD_LIBRARY_PATH\"";
            // PATH 也加一份,方便直接把 libnode.so 当可执行文件调用(有些 ROM 需要)
            nodeBinPath = "export PATH=\"" + ld1 + ":" + ld2 + ":" + ld3 + ":$PATH\"";
        }
    }

    /**
     * 采用代理模式执行UnblockNeteaseMusic
     */
    public static void startHttpProxyMode(final Context context) {
        stopScript();
        ExtraHelper.setExtraDate(ExtraHelper.SCRIPT_STATUS, "1");
        Tools.showToastOnLooper(context, "服务器代理运行成功");
    }

    public static void startScript() {
        stopScript();
        String original = SettingHelper.getInstance().getProxyOriginal();
        int port = SettingHelper.getInstance().getProxyPort();
        String execCmd = String.format(
                "cd \"%s\" && %s && %s && export ENABLE_FLAC=\"%s\" && export MIN_BR=\"%s\" && export QQ_COOKIE=\"%s\" && export MIGU_COOKIE=\"%s\" && libnode.so app.js -a 127.0.0.1 -o %s -p %d:%d",
                scriptPath,
                nodeLibPath, nodeBinPath,
                SettingHelper.getInstance().getSetting(SettingHelper.proxy_flac_key),
                SettingHelper.getInstance().getSetting(SettingHelper.proxy_priority_key) ? "256000" : "96000",
                SettingHelper.getInstance().getQqCookie(),
                SettingHelper.getInstance().getMiguCookie(),
                original,
                port, port + 1
        );
        Command start = new Command(0, execCmd) {
            @Override
            public void commandOutput(int id, String line) {
                if (line != null && line.contains("HTTP Server running")) {
                    if (neteaseContext != null)
                        Tools.showToastOnLooper(neteaseContext, "UnblockNeteaseMusic运行成功");
                    ExtraHelper.setExtraDate(ExtraHelper.SCRIPT_STATUS, "1");
                } else if ("RESTART".equals(line)) {
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException ignored) {
                    }
                    startScript();
                }
            }
        };
        Tools.shell(start);
    }

    public static void stopScript() {
        Tools.shell(new Command(0, STOP_PROXY));
    }

    /**
     * 获取CA证书
     */
    public static SSLSocketFactory getSSLSocketFactory(Context context) {
        SSLContext sslContext = null;
        try {
            File ca = new File(getScriptPath(context) + File.separator + "ca.crt");
            if (!SettingHelper.getInstance().getSetting(SettingHelper.proxy_server_key) && ca.exists()) {
                InputStream certificate = new FileInputStream(ca);
                Certificate certificate1 = CertificateFactory.getInstance("X.509").generateCertificate(certificate);
                KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
                keyStore.load(null, null);
                keyStore.setCertificateEntry("ca", certificate1);
                TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                trustManagerFactory.init(keyStore);
                sslContext = SSLContext.getInstance("TLS");
                sslContext.init(null, trustManagerFactory.getTrustManagers(), new SecureRandom());
            } else {
                TrustManager[] trustManagers = new TrustManager[]{new HTTPSTrustManager()};
                try {
                    sslContext = SSLContext.getInstance("TLS");
                    sslContext.init(null, trustManagers, new SecureRandom());
                } catch (NoSuchAlgorithmException | KeyManagementException e) {
                    e.printStackTrace();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        if (sslContext != null)
            return sslContext.getSocketFactory();
        else
            return null;
    }
}
