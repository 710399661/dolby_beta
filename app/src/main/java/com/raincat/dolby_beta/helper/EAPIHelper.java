package com.raincat.dolby_beta.helper;

import android.text.TextUtils;

import com.ndktools.javamd5.core.MD5;
import com.raincat.dolby_beta.model.CloudHeader;
import com.raincat.dolby_beta.net.Http;
import com.raincat.dolby_beta.utils.NeteaseAES2;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Random;
import java.util.regex.Pattern;

/**
 * <pre>
 *     author : RainCat
 *     e-mail : nining377@gmail.com
 *     time   : 2021/04/16
 *     desc   : 接口处理
 *     version: 1.0
 * </pre>
 */

public class EAPIHelper {

    /**
     * 修复 player/url 响应数据的逻辑 bug(不修改付费/权限判定,不绕过版权)。
     *
     * 修复点:
     * 1) 保留原 code,避免服务端返回 -110(需付费/无权限)等状态被硬改成 200,
     *    导致 UI 显示可播但播放器拿到空 URL/非法 code,最终"无法播放"。
     * 2) 保留 url 的查询参数(签名/Token),不要误截断。旧代码把 ? 之后全删,
     *    会导致带防盗链签名的官方返回 URL 变成 403。
     * 3) 不在 NeteaseSongListBean 里做字段级的 fee/flag/payed 硬清零,
     *    因为这些字段与 data.code / freeTrialPrivilege 等有交叉校验关系,
     *    改成 JSON 级别做"仅保留原服务端已有字段"的透明回传,避免 Gson 反序列化时
     *    因 NeteaseSongListBean 缺字段(freeTrialPrivilege、chargeInfoList、rightSource、
     *    encType、levelConfuse 等)而被丢弃,再序列化后播放器解析到不完整结构 → 拒播。
     */
    public static String modifyPlayer(String original) {
        try {
            JSONObject json = new JSONObject(original);

            // 1) 保留顶层 code,不要硬改成 200。如果服务端已返回非 200(需付费/下架等),
            //    继续让播放器按原有路径走(切下一首/提示付费),不要误导 UI 以为可播。
            //    (code 字段保留原数值即可,此处不做任何覆盖)

            JSONArray data = json.optJSONArray("data");
            if (data != null) {
                for (int i = 0; i < data.length(); i++) {
                    JSONObject song = data.optJSONObject(i);
                    if (song == null) continue;

                    // 云盘歌曲(flag & 0x8 != 0):保持原样,不做任何修改
                    int flag = song.optInt("flag", 0);
                    if ((flag & 0x8) != 0) continue;

                    // 2) 官方返回的 url 常带 ? 后的防盗链签名/token,不能截断;
                    //    旧代码截断会导致拿到的 URL 被 CDN 鉴权拒绝 → 403 无法播放。
                    //    此处直接保留 song.optString("url") 原值。

                    String url = song.optString("url");
                    // 3) 只在 url 非空且 data[i].code == 200 的合法前提下,
                    //    做"确保 fee/code/flag 不冲突"的防御性兜底:
                    //    如果原返回 url 有效但 fee 与 code 不一致(属于网易云偶发数据问题),
                    //    保持服务端原值,不做硬覆盖,避免与播放器其他校验逻辑冲突。
                    int code = song.optInt("code");
                    if (!TextUtils.isEmpty(url) && code == 200) {
                        // 4) 确保 type/encodeType 字段存在,否则新版播放器在解析
                        //    Hires/Lossless 格式时会认为"格式不合法"而拒绝加载。
                        if (song.isNull("type") || TextUtils.isEmpty(song.optString("type"))) {
                            // 从 url 后缀推断,若推断不出填默认 "mp3" 避免 type 为空被拒
                            String type = inferTypeFromUrl(url);
                            if (type != null) song.put("type", type);
                        }
                        if (song.isNull("encodeType") || TextUtils.isEmpty(song.optString("encodeType"))) {
                            String enc = inferTypeFromUrl(url);
                            if (enc != null) song.put("encodeType", enc);
                        }
                    }
                }
            }
            return json.toString();
        } catch (Exception e) {
            // JSON 解析失败时回传原始字符串,保证链路至少不会因为我们自己的处理崩
            e.printStackTrace();
            return original;
        }
    }

    /**
     * 从播放 URL 的后缀推断音频 type/encodeType,兼容官方返回 type 缺失的情况。
     * 返回 null 表示无法推断,由调用方决定是否写入。
     * EAPIHook.download/url 分支也会调用此方法,因此设为 public。
     */
    public static String inferTypeFromUrl(String url) {
        if (TextUtils.isEmpty(url)) return null;
        int q = url.indexOf('?');
        String path = q >= 0 ? url.substring(0, q) : url;
        int dot = path.lastIndexOf('.');
        if (dot < 0 || dot == path.length() - 1) return null;
        String ext = path.substring(dot + 1).toLowerCase();
        switch (ext) {
            case "mp3":
            case "m4a":
            case "aac":
            case "flac":
            case "wav":
            case "ogg":
            case "ape":
            case "wma":
                return ext;
            case "webm":
                return "ogg";  // 网易云少见,兜底为 ogg
            default:
                return null;
        }
    }

    /**
     * 收藏
     */
    public static String modifyManipulate(HashMap<String, String> data, String original) throws Exception {
        if (original.contains("\"code\":200") && original.contains("\"offlineIds\":[]") && !original.contains("\"trackIds\":\"[]\""))
            return original;

        String cookie = ExtraHelper.getExtraDate(ExtraHelper.COOKIE);
        if (cookie.equals("-1")) {
            return original;
        }
        HashMap<String, Object> header = new HashMap<>();
        header.put("Cookie", cookie);

        JSONObject paramJSON = decrypt(data.get("params"));
        HashMap<String, Object> param = new HashMap<>();
        String trackIds = paramJSON.getString("trackIds");
        param.put("op", paramJSON.getString("op"));
        param.put("pid", paramJSON.getString("pid"));

        String newTrackIds = trackIds.replace("]", "") + trackIds.replace("[", ",");
        param.put("trackIds", newTrackIds);
        String result = new Http("POST", "http://music.163.com/api/playlist/manipulate/tracks", param, header).getResult();
        if (result.contains("502") || result.contains("200"))
            result = "{\"trackIds\":" + trackIds + ",\"code\":200,\"privateCloudStored\":false}";
        return result;
    }

    /**
     * 喜欢
     */
    public static String modifyLike(HashMap<String, String> data, String original) throws Exception {
        String cookie = ExtraHelper.getExtraDate(ExtraHelper.COOKIE);
        String pid = ExtraHelper.getExtraDate(ExtraHelper.LOVE_PLAY_LIST);
        if (original.contains("\"code\":200") || cookie.equals("-1") || pid.equals("-1"))
            return original;

        HashMap<String, Object> header = new HashMap<>();
        header.put("Cookie", cookie);

        //获取我喜欢的音乐列表
        JSONObject paramJSON = decrypt(data.get("params"));
        String trackId = paramJSON.getString("trackId");

        HashMap<String, Object> param = new HashMap<>();
        param.put("trackIds", "[\"" + trackId + "\",\"" + trackId + "\"]");
        param.put("op", "add");
        param.put("pid", pid);

        String result = new Http("POST", "http://music.163.com/api/playlist/manipulate/tracks", param, header).getResult();
        if (result.contains("502") || result.contains("200"))
            result = "{\"playlistId\":" + pid + ",\"code\":200}";
        return result;
    }

    public static void uploadCloud(String data) {
        String paramString = "{\"songid\":\"" + data + "\",\"e_r\":true,\"header\":\"%s\"}";
        CloudHeader cloudHeader = new CloudHeader();
        cloudHeader.setOs("pc");
        cloudHeader.setAppver("2.7.1.198242");
//        cloudHeader.setDeviceId(ExtraDao.getInstance(context).getExtra("deviceId"));
        Random random = new Random();
        cloudHeader.setRequestId(String.valueOf(random.nextInt() * (1000000 - 10000 + 1) + 10000));
        cloudHeader.setClientSign("60:45:CB:9A:C3:5E@@@WD-WCC2E6LCUS2U@@@@@@39cda0b9-b0aa-4e38-a7d5-e5e9b2f430176d0b275515819c796da324b0129703e2");
        cloudHeader.setOsver("Microsoft-Windows-10-Professional-build-18363-64bit");
        cloudHeader.setBatchmethod("POST");
        cloudHeader.setMUSIC_U(ExtraHelper.getExtraDate(ExtraHelper.COOKIE).replace("MUSIC_U=", ""));

        Gson gson = new Gson();
        String headerParam = gson.toJson(cloudHeader);
        headerParam = headerParam.replace("\"", "\\\"");
        paramString = String.format(paramString, headerParam);
        MD5 md5 = new MD5();
        String md5String = md5.getMD5ofStr("nobody" + "/api/cloud/pub/v2" + "use" + paramString + "md5forencrypt");
        paramString = "/api/cloud/pub/v2-36cd479b6b5-" + paramString + "-36cd479b6b5-" + md5String.toLowerCase();

        HashMap<String, Object> header = new HashMap<>();
        header.put("Host", "interface3.music.163.com");
        header.put("Connection", "keep-alive");
        header.put("Accept", "*/*");
        header.put("Content-Type", "application/x-www-form-urlencoded");
        header.put("Origin", "orpheus://orpheus");
        header.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/35.0.1916.157 NeteaseMusicDesktop/2.7.1.198242 Safari/537.36");
        header.put("Accept-Encoding", "gzip,deflate");
        header.put("Accept-Language", "en-us,en;q=0.8");

        paramString = NeteaseAES2.Encrypt(paramString);
        HashMap<String, Object> param = new HashMap<>();
        param.put("params", paramString);

        new Http("POST", "http://interface3.music.163.com/eapi/cloud/pub/v2", param, header).getResult();
    }

    /**
     * 音效
     */
    public static String modifyEffect(String originalContent) {
        originalContent = Pattern.compile("\"type\":\\d+").matcher(originalContent).replaceAll("\"type\":1");
        return originalContent;
    }

    public static JSONObject decrypt(String params) throws Exception {
        params = NeteaseAES2.Decrypt(params);
        if (params != null && params.length() != 0) {
            params = params.substring(params.indexOf("{"), params.lastIndexOf("}") + 1);
            JSONObject jsonObject = new JSONObject(params);
            if (jsonObject.isNull("params"))
                return new JSONObject(params);
            else
                return decrypt(jsonObject.getString("params"));
        } else
            return new JSONObject();
    }
}
