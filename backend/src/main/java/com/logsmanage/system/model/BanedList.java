package com.logsmanage.system.model;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.LocalDateTime;

@Data
@Document(collection = "BanedList")
public class BanedList {
    @Id
    private String id;

    @Field("UserName")
    private String userName;

    @Field("NickName")
    private String nickName;

    @Field("Institution")
    private String institution;

    @Field("BanReason")
    private String banReason;

    @Field("BanTime")
    private LocalDateTime banTime;

    @Field("IsUnbanned")
    private Boolean unbanned;
}
