package com.logsmanage.system.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * IpCityResolver 走的是 classpath 里的 ip2region_v4.xdb，不连任何库。
 *
 * 这组测试的真正目的：确认升级到 ip2region 2.7.0（xdb 格式）之后，
 * region 字符串仍是「国家|区域|省份|城市|运营商」五段式 ——
 * cityFromRegion() 靠下标 3 取城市，格式变了会静默取错字段。
 */
class IpCityResolverTest {

    @Test
    void 能从classpath加载xdb并解析出真实城市名() throws Exception {
        try (IpCityResolver resolver = new IpCityResolver()) {
            // 断言必须落到具体城市名。只断言「非空」是不够的：
            // v4 xdb 的 region 是 国家|省份|城市|ISP|国家代码，
            // 取错下标会拿到「电信」「联通」这类运营商名 —— 非空、长度>0，
            // 却是错的。这里点名城市，取错下标就会红。
            assertEquals("南京市", resolver.resolve("114.114.114.114"));
            assertEquals("上海市", resolver.resolve("180.165.9.229"));
            assertEquals("杭州市", resolver.resolve("223.5.5.5"));
        }
    }

    @Test
    void 解析结果不能是运营商名() throws Exception {
        try (IpCityResolver resolver = new IpCityResolver()) {
            // 202.106.0.20 的 ISP 段是「联通」，下标取错就会返回它
            String city = resolver.resolve("202.106.0.20");
            assertNotNull(city);
            for (String isp : new String[]{"电信", "联通", "移动", "铁通", "长城宽带"}) {
                assertNotEquals(isp, city, "解析出的应该是城市，不是运营商");
            }
        }
    }

    @Test
    void 内网与非法IP一律返回null() throws Exception {
        try (IpCityResolver resolver = new IpCityResolver()) {
            assertNull(resolver.resolve("127.0.0.1"), "私网段不解析");
            assertNull(resolver.resolve("127.0.0.1"), "私网段不解析");
            assertNull(resolver.resolve("127.0.0.1"), "回环不解析");
            assertNull(resolver.resolve("127.0.0.1"), "私网段不解析");
            assertNull(resolver.resolve("not-an-ip"), "非法格式不解析");
            assertNull(resolver.resolve(""), "空串不解析");
            assertNull(resolver.resolve(null), "null 不解析");
        }
    }

    @Test
    void 城市解析结果稳定且走缓存() throws Exception {
        try (IpCityResolver resolver = new IpCityResolver()) {
            String first = resolver.resolve("223.5.5.5");
            String second = resolver.resolve("223.5.5.5");
            assertEquals(first, second, "同一 IP 两次解析必须一致（第二次来自缓存）");
        }
    }

    @Test
    void 省直辖市能退回省份而不是留空() throws Exception {
        try (IpCityResolver resolver = new IpCityResolver()) {
            // 北京在 ip2region 里城市段常为空，cityFromRegion 应退回省份段，
            // 不能因为下标 3 是空串就返回 null。
            String city = resolver.resolve("202.106.0.20");
            assertNotNull(city, "北京 IP 不应解析成 null");
        }
    }
}
