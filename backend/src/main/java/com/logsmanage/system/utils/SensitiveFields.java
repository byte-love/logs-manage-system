package com.logsmanage.system.utils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 手机号这类敏感字段的统一口径：只用于内部核对，不随接口回传给前端。
 *
 * <p>同步侧早就是这么做的 —— {@code UserAndLogExportToMongo} 从 SQL Server 读数据时
 * 直接把 {@code Phone} 从文档里删掉（注释：「手机号属于敏感信息，不导出到目标 MongoDB」），
 * 所以 {@code User_Data} / {@code NewLog} 目标集合里本来就没有手机号。
 * 这里管的是另外两处<b>直接读源库、原样回传</b>的接口：数据交付记录（{@code delivery_apply.phone}）
 * 与审核记录（{@code register_xiaochengxu.phone}）。
 *
 * <p>字段名按忽略大小写比对：源表是从 SQL Server 迁过来的，大小写不统一
 * （{@code register} 里 {@code Email} 大写、{@code name} 小写并存），写死 {@code "phone"}
 * 做等值判断会漏掉 {@code Phone}。
 */
public final class SensitiveFields {

    /** 手机号字段名，一律小写。表里目前只有 phone / Phone 两种写法。 */
    private static final Set<String> PHONE_FIELDS =
            new HashSet<String>(Arrays.asList("phone"));

    private SensitiveFields() {
    }

    /** 这个字段名是不是手机号。 */
    public static boolean isPhoneField(String field) {
        return field != null && PHONE_FIELDS.contains(field.toLowerCase(Locale.ROOT));
    }

    /**
     * 就地删除文档里的手机号字段，键名忽略大小写。
     * 传 null、空文档、没有该字段都是无操作。
     */
    public static void stripPhone(Map<String, Object> fields) {
        if (fields == null || fields.isEmpty()) {
            return;
        }
        // 先把命中的键收集起来再删，避免遍历 keySet 的过程中改 map
        List<String> hits = null;
        for (String key : fields.keySet()) {
            if (isPhoneField(key)) {
                if (hits == null) {
                    hits = new ArrayList<String>();
                }
                hits.add(key);
            }
        }
        if (hits != null) {
            for (String key : hits) {
                fields.remove(key);
            }
        }
    }
}
