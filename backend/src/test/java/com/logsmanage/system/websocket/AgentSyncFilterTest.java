package com.logsmanage.system.websocket;

import org.bson.BsonDocument;
import org.bson.BsonRegularExpression;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 智能体同步跳过 zhengshi 的判定：取数条件的 BSON 形状 + 那条正则的匹配语义。不连库。
 *
 * <p>直接调生产代码的 agentPendingFilter，而且语义测试用的正则就是从生产条件里取出来的那份
 * （不是在测试里另写一个 Pattern 或复刻一份条件）—— 复刻的写法生产代码改坏了也不会红。
 *
 * <p>跳过是下推到查询条件里的（不是在游标循环里 continue），所以这里既钉住「条件带上了 userId 的
 * $not」，也钉住「断点条件没被顺手丢掉」。漏掉 $not 就是 zhengshi 照旧同步进来；
 * 漏掉 $gt 则每次都从头全量重拉。
 *
 * <p>另：没有 userId 字段的文档由 MongoDB 的正则语义决定 —— 正则对缺失字段不匹配、$not 为真，
 * 即照常同步。实测 181 上当前 0 条这种文档，没法在这里断言。
 */
class AgentSyncFilterTest {

    /** 从生产条件里取出跳过用的正则：测的就是真正发到库上的那份。 */
    private static Pattern emittedSkipPattern() {
        BsonRegularExpression re = UserAndLogExportToMongo.agentPendingFilter(null)
                .toBsonDocument().getDocument("userId").getRegularExpression("$not");
        String options = re.getOptions();
        int flags = 0;
        if (options.contains("i")) {
            flags |= Pattern.CASE_INSENSITIVE;
        }
        if (options.contains("m")) {
            flags |= Pattern.MULTILINE;
        }
        if (options.contains("s")) {
            flags |= Pattern.DOTALL;
        }
        if (options.contains("x")) {
            flags |= Pattern.COMMENTS;
        }
        return Pattern.compile(re.getPattern(), flags);
    }

    @Test
    void 跳过条件必须真的进了查询里() {
        BsonDocument filter = UserAndLogExportToMongo.agentPendingFilter(null).toBsonDocument();
        assertTrue(filter.containsKey("userId"), "条件里必须有 userId 这条: " + filter);
        BsonRegularExpression skip =
                filter.getDocument("userId").getRegularExpression("$not");
        assertEquals("^\\s*zhengshi\\s*$", skip.getPattern(), "必须 ^...$ 锚定，不能用包含匹配");
        assertEquals("i", skip.getOptions(), "必须忽略大小写");
    }

    @Test
    void 全量同步时不带断点条件() {
        BsonDocument filter = UserAndLogExportToMongo.agentPendingFilter(null).toBsonDocument();
        assertFalse(filter.containsKey("_id"), "全量同步不该有 _id 条件: " + filter);
    }

    @Test
    void 增量同步断点与跳过条件都要在() {
        ObjectId lastId = new ObjectId("6a2b697b0ebc900fafc41a44");
        BsonDocument filter = UserAndLogExportToMongo.agentPendingFilter(lastId).toBsonDocument();
        // 这个驱动版本的 BsonDocument.getObjectId 返回的是 BsonObjectId 而不是 ObjectId，
        // 所以往里取一层 getValue()。断点必须是 _id 大于书签，且类型跟着源主键（ObjectId）走。
        assertEquals(lastId, filter.getDocument("_id").getObjectId("$gt").getValue(),
                "断点必须是 _id 大于书签，且类型跟着源主键（ObjectId）走");
        assertTrue(filter.containsKey("userId"), "增量同步同样要带跳过条件: " + filter);
    }

    @Test
    void 全小写命中() {
        assertTrue(emittedSkipPattern().matcher("zhengshi").matches());
    }

    @Test
    void 大小写变体与首尾空格都算命中() {
        assertTrue(emittedSkipPattern().matcher("Zhengshi").matches());
        assertTrue(emittedSkipPattern().matcher("ZHENGSHI").matches());
        assertTrue(emittedSkipPattern().matcher("  zhengshi  ").matches(),
                "源表是 SQL Server 迁过来的那套 CHAR 字段，值可能带填充空格");
    }

    @Test
    void 名字相近或带后缀的真实账号不受影响() {
        // zhengzhenji 是库里真实存在的账号（v 和 n 差一个字母），用「包含 zhengshi」就会误伤它
        assertFalse(emittedSkipPattern().matcher("zhengzhenji").matches());
        // 只认精确相等，不是前缀：这些是别人
        assertFalse(emittedSkipPattern().matcher("zhengshi01").matches());
        assertFalse(emittedSkipPattern().matcher("zhanghuiying-gj").matches());
        assertFalse(emittedSkipPattern().matcher("xumingzhen").matches());
    }
}
