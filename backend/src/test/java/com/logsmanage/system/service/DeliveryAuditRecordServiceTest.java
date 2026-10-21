package com.logsmanage.system.service;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeliveryAuditRecordServiceTest {

    private static final String AUDIT = "delivery_auditRecord";
    private static final String APPLY = "delivery_apply";

    @Test
    void readsDeliveryApplyFromDataHubSysAndDoesNotUseUserDataForProtocol() {
        MongoTemplate sys = mock(MongoTemplate.class);
        Document source = new Document("deliveryApplyId", "A-1").append("operateTime", 1720000000000L);
        Document apply = new Document("_id", "A-1").append("name", "申请人");
        when(sys.count(any(Query.class), eq(AUDIT))).thenReturn(1L);
        when(sys.find(any(Query.class), eq(Document.class), eq(AUDIT)))
                .thenReturn(Collections.singletonList(source));
        when(sys.find(any(Query.class), eq(Document.class), eq(APPLY)))
                .thenReturn(Collections.singletonList(apply));
        DeliveryAuditRecordService service = new DeliveryAuditRecordService(sys);

        Map<String, Object> result = service.page(1, 10, null);

        assertThat(result.get("total")).isEqualTo(1L);
        assertThat((java.util.List<?>) result.get("records")).singleElement()
                .extracting("apply").asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("name", "申请人");
        verify(sys).find(any(Query.class), eq(Document.class), eq(APPLY));
    }

    @Test
    void keepsAuditRowWhenApplyIsNotFound() {
        MongoTemplate sys = mock(MongoTemplate.class);
        Document source = new Document("deliveryApplyId", "missing").append("account", "u-1");
        when(sys.count(any(Query.class), eq(AUDIT))).thenReturn(1L);
        when(sys.find(any(Query.class), eq(Document.class), eq(AUDIT)))
                .thenReturn(Collections.singletonList(source));
        when(sys.find(any(Query.class), eq(Document.class), eq(APPLY)))
                .thenReturn(Collections.<Document>emptyList());

        Map<String, Object> result = new DeliveryAuditRecordService(sys).page(1, 10, null);

        // 联查没命中不能把审批行丢掉，也不能凭空造出 apply
        assertThat((java.util.List<?>) result.get("records")).singleElement()
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("account", "u-1")
                .doesNotContainKey("apply");
    }

    @Test
    void dedupesApplyIdsAndQueriesApplyOnlyOnce() {
        MongoTemplate sys = mock(MongoTemplate.class);
        Document first = new Document("deliveryApplyId", "A-1");
        Document second = new Document("deliveryApplyId", "A-1");
        Document third = new Document("deliveryApplyId", "A-2");
        when(sys.count(any(Query.class), eq(AUDIT))).thenReturn(3L);
        when(sys.find(any(Query.class), eq(Document.class), eq(AUDIT)))
                .thenReturn(Arrays.asList(first, second, third));
        when(sys.find(any(Query.class), eq(Document.class), eq(APPLY)))
                .thenReturn(Collections.singletonList(new Document("_id", "A-1")));

        new DeliveryAuditRecordService(sys).page(1, 10, null);

        ArgumentCaptor<Query> captor = ArgumentCaptor.forClass(Query.class);
        verify(sys, times(1)).find(captor.capture(), eq(Document.class), eq(APPLY));
        // 本页三个申请 ID 去重成两个，一次 $in 查完，不逐行打公网
        String queryText = captor.getValue().getQueryObject().toJson();
        assertThat(queryText).contains("A-1").contains("A-2");
    }

    @Test
    void escapesKeywordSoRegexSpecialCharactersCannotWidenMatch() {
        MongoTemplate sys = mock(MongoTemplate.class);
        when(sys.count(any(Query.class), eq(AUDIT))).thenReturn(0L);
        when(sys.find(any(Query.class), eq(Document.class), eq(AUDIT)))
                .thenReturn(Collections.<Document>emptyList());

        new DeliveryAuditRecordService(sys).page(1, 10, "a[");

        ArgumentCaptor<Query> captor = ArgumentCaptor.forClass(Query.class);
        verify(sys).count(captor.capture(), eq(AUDIT));
        // JSON 里 \Q 会被转义成 \\Q，出现它就说明走了 Pattern.quote
        assertThat(captor.getValue().getQueryObject().toJson()).contains("\\\\Q");
    }
}
