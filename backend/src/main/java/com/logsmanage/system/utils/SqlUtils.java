package com.logsmanage.system.utils;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public class SqlUtils {

    static {
        try {
            Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver");
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        }
    }

    public static Connection getConnection(String url,String user,String password) throws SQLException {

        return DriverManager.getConnection(url, user, password);
    }
}
