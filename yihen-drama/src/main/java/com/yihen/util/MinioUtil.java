package com.yihen.util;

 import com.yihen.constant.MinioConstant;
 import com.yihen.config.properties.MinioProperties;
import io.minio.*;
import io.minio.errors.*;
import io.minio.http.Method;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.TimeUnit;

@Component
public class MinioUtil {
    @Resource
    private  MinioClient minioClient;

    @Resource
    private MinioProperties minioProperties;

     public String getRealObjectName(String pathOrUrl) {
         return parseObjectName(pathOrUrl);
     }

     public String parseObjectName(String pathOrUrl) {
         if (pathOrUrl == null || pathOrUrl.isBlank()) {
             return null;
         }

         String clean = pathOrUrl;
         int q = clean.indexOf('?');
         if (q >= 0) {
             clean = clean.substring(0, q);
         }
         int f = clean.indexOf('#');
         if (f >= 0) {
             clean = clean.substring(0, f);
         }

         String bucket = (minioProperties == null) ? null : minioProperties.getBucketName();
         if (bucket == null || bucket.isBlank()) {
             bucket = MinioConstant.BUCKET_NAME;
         }

         String marker = "/" + bucket + "/";
         int index = clean.indexOf(marker);
         if (index != -1) {
             return clean.substring(index + marker.length());
         }

         return clean;
     }

    // 上传文件到指定位置
    public  void uploadFile(MultipartFile file, String bucketName, String objectName) throws IOException, ServerException, InsufficientDataException, ErrorResponseException, NoSuchAlgorithmException, InvalidKeyException, InvalidResponseException, XmlParserException, InternalException {
        try(InputStream inputStream = file.getInputStream();) {
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucketName)
                            .object(objectName)
                            .stream(inputStream, file.getSize(), -1)
                            .build()
            );
        }

    }


    // 上传文件到指定位置
    public  void uploadFile(InputStream file,int size, String bucketName, String objectName) throws IOException, ServerException, InsufficientDataException, ErrorResponseException, NoSuchAlgorithmException, InvalidKeyException, InvalidResponseException, XmlParserException, InternalException {
        try(file) {
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucketName)
                            .object(objectName)
                            .stream(file,size, -1)
                            .build()
            );
        } catch (ErrorResponseException e) {
            createBucket(bucketName);
            uploadFile(file,size,bucketName,objectName);
        }


    }

    public void uploadFile(InputStream stream, long size, String bucket, String objectName, String contentType) throws Exception {
        try {
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucket)
                            .object(objectName)
                            .stream(stream, size, -1)
                            .contentType(contentType)
                            .build()
            );
        }
        catch (ErrorResponseException e) {
            createBucket(bucket);
            uploadFile(stream, size, bucket, objectName, contentType);
        }
    }


    // 桶是否存在
    public boolean bucketExists(String bucketName)  {
        try {
            return minioClient.bucketExists(
                    BucketExistsArgs.builder()
                            .bucket(bucketName)
                            .build()
            );
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // 删除对象
    public void deleteObject(String bucketName, String Object) {
        try {
            String realObjectName = parseObjectName(Object);
            minioClient.removeObject(
                    RemoveObjectArgs.builder()
                            .bucket(bucketName)
                            .object(realObjectName)
                            .build()
            );
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // 创建桶
    public void createBucket(String bucketName) {
        try {
            if (!bucketExists(bucketName)) {
                minioClient.makeBucket(
                        MakeBucketArgs.builder()
                                .bucket(bucketName)
                                .build()
                );

                String policyJsonString = """
                        {
                          "Version": "2012-10-17",
                          "Statement": [
                            {
                              "Effect": "Allow",
                              "Principal": { "AWS": ["*"] },
                              "Action": [ "s3:GetObject", "s3:PutObject" ],
                              "Resource": [ "arn:aws:s3:::%s/*" ]
                            }
                          ]
                        }
                        """.formatted(bucketName);

                minioClient.setBucketPolicy(SetBucketPolicyArgs.builder()
                        .bucket(bucketName)
                        .config(policyJsonString)
                        .build());
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // 获取对象
    public GetObjectResponse getObject(String bucketName, String Object) {
        try {
            String realObjectName = parseObjectName(Object);
            return minioClient.getObject(
                    GetObjectArgs.builder()
                            .bucket(bucketName)
                            .object(realObjectName)
                            .build()
            );
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // 获取对象链接
    public String getObjectUrl(String bucketName, String objectName) {
        try {
            return minioClient.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .method(Method.GET)
                            .bucket(bucketName)
                            .object(objectName)
                            .expiry(1, TimeUnit.HOURS)
                            .build());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
