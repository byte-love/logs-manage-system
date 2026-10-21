package com.logsmanage.system;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.security.Security;

@SpringBootApplication
public class LogsManageSystemApplication {

    public static void main(String[] args) {
        // 正确写法：所有算法用逗号分隔，不要换行
        String disabledAlgorithms = "SSLv3, RC4, DES, MD5withRSA, DH keySize < 1024, EC keySize < 224, anon, NULL";
        Security.setProperty("jdk.tls.disabledAlgorithms", disabledAlgorithms);

        // 启用 TLS 1.0 和 1.1
        System.setProperty("jdk.tls.client.protocols", "TLSv1,TLSv1.1,TLSv1.2");

        SpringApplication.run(LogsManageSystemApplication.class, args);
    }
}
