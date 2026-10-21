package com.logsmanage.system.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "sys_users")
public class SysUser {

    @Id
    private String id;

    @Indexed(unique = true)
    private String username;

    private String password;

    private String displayName;

    private Boolean enabled;

    private Boolean canCreateAccount;

    private LocalDateTime createdAt;
}
