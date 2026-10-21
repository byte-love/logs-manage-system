package com.logsmanage.system.model;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Data
@Document(collection = "User_Data_UpdateTime")
public class UserDataUpdateTime {

    @Id
    private String id;

    private String type;

    private LocalDateTime updateTime;
}
