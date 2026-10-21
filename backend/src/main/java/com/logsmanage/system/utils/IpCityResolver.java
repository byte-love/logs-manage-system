package com.logsmanage.system.utils;

import org.lionsoul.ip2region.xdb.Searcher;
import org.springframework.core.io.ClassPathResource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 使用 ip2region 离线数据库解析公网 IPv4 所在城市。
 *
 * 用 ip2region 2.7.0 的 xdb 格式：1.7.2 需要的老 ip2region.db 已从上游仓库删除，
 * 只剩不可校验的第三方镜像，所以升级到官方仍在维护的 xdb（数据文件随 jar 打包）。
 *
 * 非线程安全（内部有 LRU 缓存）。同步任务里每个会话各自 new 一个，够用。
 */
public class IpCityResolver implements AutoCloseable {

    private static final int MAX_CACHE_SIZE = 200000;
    private static final String DEFAULT_DATABASE_PATH = "ip2region_v4.xdb";

    private final Searcher searcher;
    private final Map<String, String> cache = new LinkedHashMap<String, String>(16384, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > MAX_CACHE_SIZE;
        }
    };

    public IpCityResolver() throws Exception {
        String configuredPath = setting("datahub.ip.db", "DataHub_IP_DB_PATH", DEFAULT_DATABASE_PATH);
        byte[] databaseBytes = loadDatabaseBytes(configuredPath);
        // 整库读进内存，查询不碰磁盘（xdb 约 11MB）。
        searcher = Searcher.newWithBuffer(databaseBytes);
    }

    private byte[] loadDatabaseBytes(String path) throws IOException {
        // 1. 尝试从 classpath 加载（打包在 JAR 内时走这里）
        ClassPathResource resource = new ClassPathResource(path);
        if (resource.exists()) {
            try (InputStream is = resource.getInputStream()) {
                return readFully(is);
            }
        }

        // 2. 尝试从文件系统加载（支持绝对路径或相对路径）
        Path filePath = Paths.get(path);
        if (Files.isRegularFile(filePath)) {
            return Files.readAllBytes(filePath);
        }

        // 3. 都找不到则报错
        throw new IOException("找不到 ip2region 数据库文件: " + path +
                " (classpath 和文件系统均不存在)");
    }

    /**
     * InputStream.readAllBytes() 是 Java 9+ API，生产机是 JDK 8，只能自己抄一遍。
     * （pom 已改成 release=8，这类误用现在会在编译期就报错。）
     */
    private static byte[] readFully(InputStream is) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(12 * 1024 * 1024);
        byte[] chunk = new byte[8192];
        int read;
        while ((read = is.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

//    public IpCityResolver() throws Exception {
//        String configuredPath = setting("datahub.ip.db", "DataHub_IP_DB_PATH", DEFAULT_DATABASE_PATH);
//        Path databasePath = Paths.get(configuredPath);
//        if (!Files.isRegularFile(databasePath)) {
//            throw new IOException("找不到 ip2region 数据库文件: " + databasePath.toAbsolutePath());
//        }
//
//        // 一次性载入内存，避免为每条日志反复打开文件。
//        byte[] databaseBytes = Files.readAllBytes(databasePath);
//        searcher = new DbSearcher(new DbConfig(), databaseBytes);
//        System.out.println("已加载 IP 归属地数据库: " + databasePath.toAbsolutePath());
//    }

    public String resolve(String ip) {
        if (ip == null) {
            return null;
        }
        ip = ip.trim();
        if (cache.containsKey(ip)) {
            return cache.get(ip);
        }

        String city = null;
        if (isPublicIpv4(ip)) {
            try {
                // 2.x 的 search 直接返回 region 字符串，格式仍是 国家|区域|省份|城市|运营商
                city = cityFromRegion(searcher.search(ip));
            } catch (Exception e) {
                System.err.println("IP 城市解析失败: " + ip + "，" + e.getMessage());
            }
        }
        cache.put(ip, city);
        return city;
    }

    private static String cityFromRegion(String region) {
        if (region == null || region.trim().isEmpty()) {
            return null;
        }
        String[] parts = region.split("\\|", -1);
        // v4 xdb 格式：国家|省份|城市|ISP|国家代码，城市在下标 2。
        //
        // 注意别照 1.7.2 的老格式（国家|区域|省份|城市|运营商）取下标 3 ——
        // 两者都是 5 段，但整体错开一位，取 3 拿到的是运营商。
        // 实测 180.165.9.229 会解析成「电信」而不是「上海市」，
        // 而 ISP 段为「0」的 IP（如 114.114.114.114）又会碰巧回退到正确城市，
        // 所以这个错误只在部分 IP 上显形，很容易漏。
        String city = part(parts, 2);
        if (city != null) {
            return city;
        }
        String province = part(parts, 1);
        if (province != null) {
            return province;
        }
        return part(parts, 0);
    }

    private static String part(String[] parts, int index) {
        if (index >= parts.length) {
            return null;
        }
        String value = parts[index].trim();
        return value.isEmpty() || "0".equals(value) ? null : value;
    }

    private static boolean isPublicIpv4(String ip) {
        String[] parts = ip.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        int[] numbers = new int[4];
        try {
            for (int i = 0; i < parts.length; i++) {
                if (parts[i].isEmpty() || (parts[i].length() > 1 && parts[i].charAt(0) == '0')) {
                    return false;
                }
                numbers[i] = Integer.parseInt(parts[i]);
                if (numbers[i] < 0 || numbers[i] > 255) {
                    return false;
                }
            }
        } catch (NumberFormatException e) {
            return false;
        }

        int first = numbers[0];
        int second = numbers[1];
        return first != 0
                && first != 10
                && first != 127
                && first < 224
                && !(first == 100 && second >= 64 && second <= 127)
                && !(first == 169 && second == 254)
                && !(first == 172 && second >= 16 && second <= 31)
                && !(first == 192 && second == 168);
    }

    @Override
    public void close() {
        // 2.7.0 的 close() 会先判空再关文件句柄，buffer 模式下调用是安全的
        // （1.7.2 在这种模式下会 NPE，所以旧代码故意不调）。
        try {
            searcher.close();
        } catch (IOException e) {
            System.err.println("关闭 ip2region searcher 失败: " + e.getMessage());
        }
        cache.clear();
    }

    private static String setting(String propertyName, String environmentName, String defaultValue) {
        String value = System.getProperty(propertyName);
        if (value == null || value.trim().isEmpty()) {
            value = System.getenv(environmentName);
        }
        return value == null || value.trim().isEmpty() ? defaultValue : value.trim();
    }
}
