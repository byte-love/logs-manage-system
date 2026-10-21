package com.logsmanage.system.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class CreateAccountRequest {

    private String userName;
    private String password;
    private String nickName;
    private String phone;
    private String mail;
    private String institution;
    private String college;
    private String collegeDept;
    private String position;
    private Integer userType;
    private String dataCode;
    private String receiveMail;
    private String contactors;
    private String contactPhone;
    private String contactMail;
    private String contactPosition;
    private String addresses;
    private String expireDateTime;
    private String ipRange;
    private String xuezhi;
    private String ruxueYear;
    private String endDate;
    private String xueEnddate;
    private Boolean sendEmail;
    private Boolean sendMessage;
    private String marketInitiator;
    private String otherDescription;
    private List<AccountPermission> permissions = new ArrayList<>();

    @Data
    public static class AccountPermission {
        private String code;
        private Boolean selected;
        private String expiryDate;
    }
}
