package com.logsmanage.system.utils;

import com.aliyun.oss.ClientException;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.model.PutObjectResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;

/**
 * OSS 工具类，用于 register 同步时上传文件到阿里云 OSS。
 */
public class OssClientDataHub {
    private static final Logger log = LoggerFactory.getLogger(OssClientDataHub.class);

    private final String endpoint;
    private final String accessKeyId;
    private final String accessKeySecret;
    private final String bucketName;

    public OssClientDataHub(String endpoint, String accessKeyId, String accessKeySecret, String bucketName) {
        this.endpoint = endpoint;
        this.accessKeyId = accessKeyId;
        this.accessKeySecret = accessKeySecret;
        this.bucketName = bucketName;
    }

    /**
     * 上传文件到 OSS。
     *
     * @param inputStream       文件输入流
     * @param objectPathAndName OSS 对象路径（如 "register/AgreeFile_123"）
     * @return 成功返回对象路径，失败返回 null
     */
    public String upload(InputStream inputStream, String objectPathAndName) {
        OSS ossClient = new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);
        try {
            PutObjectResult result = ossClient.putObject(bucketName, objectPathAndName, inputStream);
            if (result != null) {
                return objectPathAndName;
            }
        } catch (OSSException oe) {
            log.error("OSS 上传失败 [{}]: ErrorCode={}, ErrorMessage={}, RequestID={}, HostID={}",
                    objectPathAndName, oe.getErrorCode(), oe.getErrorMessage(), oe.getRequestId(), oe.getHostId());
        } catch (ClientException ce) {
            log.error("OSS 客户端异常 [{}]: {}", objectPathAndName, ce.getMessage());
        } finally {
            ossClient.shutdown();
        }
        return null;
    }
}
