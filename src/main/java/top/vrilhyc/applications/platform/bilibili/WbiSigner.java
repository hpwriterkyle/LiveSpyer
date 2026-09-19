package top.vrilhyc.applications.platform.bilibili;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

final class WbiSigner {
    private static final int[] MIX = {46,47,18,2,53,8,23,32,15,50,10,31,58,3,45,35,27,43,5,49,33,9,42,19,29,28,14,39,12,38,41,13,37,48,7,16,24,55,40,61,26,17,0,1,60,51,30,4,22,25,54,21,56,59,6,63,57,62,11,36,20,34,44,52};
    static String sign(Map<String,String> params, String imgKey, String subKey, long timestamp) throws Exception {
        String original = imgKey + subKey;
        if (original.length() != 64) throw new IllegalArgumentException("WBI 密钥格式不正确");
        StringBuilder key = new StringBuilder();
        for (int i = 0; i < 32; i++) key.append(original.charAt(MIX[i]));
        var sorted = new TreeMap<String,String>();
        params.forEach((k,v) -> sorted.put(k, v.replaceAll("[!'()*]", "")));
        sorted.put("wts", Long.toString(timestamp));
        String query = BiliApi.encode(sorted);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("MD5")
                .digest((query + key).getBytes(StandardCharsets.UTF_8)));
        return query + "&w_rid=" + hash;
    }
}
