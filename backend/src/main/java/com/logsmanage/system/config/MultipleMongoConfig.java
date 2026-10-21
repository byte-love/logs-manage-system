package com.logsmanage.system.config;

import org.springframework.boot.autoconfigure.mongo.MongoProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;

@Configuration
public class MultipleMongoConfig {

    @Primary
    @Bean(name = "primary")
    @ConfigurationProperties(prefix = "spring.data.mongodb")
    public MongoProperties getPrimary() {
        return new MongoProperties();
    }



    @Bean(name = "datahub")
    @ConfigurationProperties(prefix = "datahub")
    public MongoProperties getDataHub() {
        return new MongoProperties();
    }

    // 同 139 服务器的另一个库 DataHub_SYS（小程序申请表 register_xiaochengxu）。
    // 用独立顶层前缀 datahubsys，完全复刻 datahub 那套无参 @ConfigurationProperties 模式 ——
    // 带 @Value 参数的 @Bean 方法在 CGLIB 代理下 uri 会注入不进去（踩过：count 恒 0、连的库不对）。
    @Bean(name = "datahubsys")
    @ConfigurationProperties(prefix = "datahubsys")
    public MongoProperties getDatahubsys() {
        return new MongoProperties();
    }

    // DemoText（demotext）那台生产 Mongo：只用来存节假日公告的备份（holiday_tip 单行文档）。
    // 同样复刻无参 @ConfigurationProperties + 独立顶层前缀 —— 带 @Value 参数的 @Bean 在 CGLIB 下会注入不进去。
    @Bean(name = "demotext")
    @ConfigurationProperties(prefix = "demotext")
    public MongoProperties getDemoText() {
        return new MongoProperties();
    }


    //-------------------------

    @Primary
    @Bean(name = "MongoTemplate")
    public MongoTemplate primaryMongoTemplate() throws Exception {
        return new MongoTemplate(primaryFactory(getPrimary()));
    }

    @Bean(name = "datahubTemplate")
    public MongoTemplate datahubMongoTemplate() throws Exception {
        return new MongoTemplate(datahubFactory(getDataHub()));
    }

    @Bean(name = "datahubSysTemplate")
    public MongoTemplate datahubSysMongoTemplate() throws Exception {
        return new MongoTemplate(datahubSysFactory(getDatahubsys()));
    }

    @Bean(name = "demotextTemplate")
    public MongoTemplate demotextMongoTemplate() throws Exception {
        return new MongoTemplate(demotextFactory(getDemoText()));
    }

    //-------------------------

    @Primary
    @Bean
    public MongoDatabaseFactory primaryFactory(final MongoProperties mongo) throws Exception {
        return new SimpleMongoClientDatabaseFactory(mongo.getUri());
    }

    @Bean
    public MongoDatabaseFactory datahubFactory(final MongoProperties mongo) throws Exception {
        return new SimpleMongoClientDatabaseFactory(mongo.getUri());
    }

    @Bean
    public MongoDatabaseFactory datahubSysFactory(final MongoProperties mongo) throws Exception {
        return new SimpleMongoClientDatabaseFactory(mongo.getUri());
    }

    @Bean
    public MongoDatabaseFactory demotextFactory(final MongoProperties mongo) throws Exception {
        return new SimpleMongoClientDatabaseFactory(mongo.getUri());
    }


}
